package com.nasir.voicechat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/**
 * Voice engine.
 *  - English listening: Vosk (offline).
 *  - Urdu listening: the phone's own speech recognizer (ur-PK).
 *  - Brain: Gemma 4 or Qwen3 through LiteRT-LM (CPU), chosen in Setup.
 *  - Speaking: Android text-to-speech.
 *
 * Crash fixes compared with the old version:
 *  1. Vosk is now stopped properly (stop() + shutdown() + recognizer.close()).
 *     The old code only called shutdown(), so the recording thread kept reading from a
 *     released microphone and crashed the app.
 *  2. The Gemma conversation is restarted every few turns so memory does not keep growing.
 *  3. Every callback and background task is wrapped so one error cannot close the app.
 */
class VoiceEngine(private val ctx: Context, private val st: AppState) : RecognitionListener {

    // ---- English speech (Vosk) ----
    @Volatile private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null

    // ---- Urdu speech (Android) ----
    private var urdu: SpeechRecognizer? = null

    // ---- Gemma 4. Every access happens on the single `io` thread. ----
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var turns = 0
    @Volatile private var visionOk = false

    // ---- streaming / speaking state ----
    @Volatile private var generating = false
    private val pending = AtomicInteger(0)   // sentences queued in TTS but not finished yet
    private var spokenTo = 0
    private var lastUi = 0L
    private class StopGen : RuntimeException()

    @Volatile private var lang = "en"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLang = ""
    private val io = Executors.newSingleThreadExecutor()

    companion object {
        private val SENT = Regex("[.!?۔؟\\n]")
        // A new Gemma conversation is started after this many questions (keeps memory stable).
        private const val MAX_TURNS = 3
    }

    fun init() {
        ModelStore.removeOldHindi(ctx)
        tts = TextToSpeech(ctx) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            st.post { applyTtsLang() }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { sentenceDone() }
            override fun onDone(utteranceId: String?) { sentenceDone() }
        })
        loadVosk()
        loadLlm()
    }

    // ---------------------------------------------------------------- language / TTS

    private fun applyTtsLang() {
        val t = tts ?: return
        if (!ttsReady) return
        try {
            val loc = if (st.lang == "ur") Locale("ur", "PK") else Locale("en", "IN")
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                st.note = if (st.lang == "ur")
                    "Urdu voice is missing. Install it: Phone Settings > Text-to-speech > Google > Install voice data > Urdu."
                else
                    "Install the English text-to-speech voice in your phone settings."
            }
            ttsLang = st.lang
        } catch (_: Throwable) {}
    }

    fun setLang(l: String) {
        if (l == st.lang) return
        stopListening()
        try { tts?.stop() } catch (_: Throwable) {}
        st.lang = l
        lang = l
        st.note = ""
        applyTtsLang()
        // New conversation so Gemma answers in the newly selected language.
        runIo { newConversation() }
    }

    // ---------------------------------------------------------------- Vosk model

    fun loadVosk() {
        runIo {
            if (model != null) return@runIo
            try {
                if (ModelStore.hasVosk(ctx)) {
                    model = Model(ModelStore.voskDir(ctx).absolutePath)
                } else {
                    st.post { st.note = "English speech model not found yet." }
                }
            } catch (e: Throwable) {
                st.post { st.note = "Vosk error: ${e.message}" }
            }
        }
    }

    // ---------------------------------------------------------------- Gemma

    private fun openEngine(path: String, backend: Backend, vision: Boolean): Engine {
        val cfg = if (vision)
            EngineConfig(modelPath = path, backend = backend, visionBackend = Backend.CPU(), cacheDir = ctx.cacheDir.path)
        else
            EngineConfig(modelPath = path, backend = backend, cacheDir = ctx.cacheDir.path)
        val e = Engine(cfg)
        try {
            e.initialize()
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) {}
            throw t
        }
        return e
    }

    // Opens the chosen model. Gemma 4 is opened with the picture reader; if that fails it opens without it.
    private fun buildEngine(): Engine {
        val path = ModelStore.llmFile(ctx).absolutePath
        val wantVision = ModelStore.selected(ctx).id == "gemma4"
        if (!wantVision) { visionOk = false; return openEngine(path, Backend.CPU(), false) }
        return try {
            val e = openEngine(path, Backend.CPU(), true); visionOk = true; e
        } catch (_: Throwable) {
            visionOk = false
            openEngine(path, Backend.CPU(), false)
        }
    }

    private fun newConversation() {
        val e = engine ?: return
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        turns = 0
        val langName = if (lang == "ur") "Urdu (written in Urdu script)" else "English"
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are NK Voice AI, a helpful and careful assistant. Reply only in $langName. " +
                        "Keep every answer short: at most three short sentences. " +
                        "Only when solving a maths or science problem, show the steps in at most eight short lines. " +
                        "Write maths in plain text like d2i/dt2, e^(-t), 1/2, sqrt(x). Never use LaTeX, backslashes or dollar signs. " +
                        "Use plain text only, no markdown, no lists, no emojis, " +
                        "because your reply is read aloud."
                ),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )
        )
    }

    private fun modelName() = ModelStore.selected(ctx).label

    // Switch between Gemma 4 and Qwen3. Frees the old model first (RAM).
    fun switchModel(id: String) {
        if (id == ModelStore.selected(ctx).id) return
        ModelStore.select(ctx, id)
        st.modelId = id
        st.gemmaReady = false
        runIo { closeLlm(); System.gc() }
        loadLlm()
    }

    fun loadLlm() {
        runIo {
            try {
                if (!ModelStore.hasLlm(ctx)) { st.post { st.gemmaReady = false }; return@runIo }
                val name = modelName()
                st.post { st.gemmaReady = false; st.note = "Loading $name… (first time can take a minute)" }
                closeLlm()
                // CPU is slower but stable. The GPU backend crashed on the first reply on the Vivo Y200.
                engine = buildEngine()
                newConversation()
                st.post { st.gemmaReady = true; st.note = "$name is ready ✓" }
            } catch (e: Throwable) {
                st.post { st.gemmaReady = false; st.note = "Model load error: ${e.message}" }
            }
        }
    }

    private fun closeLlm() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
    }

    private fun notReadyText() =
        if (lang == "ur") "ابھی ماڈل تیار نہیں ہے۔ براہ کرم کچھ دیر انتظار کریں۔"
        else "The model is not ready yet. Please wait for it to finish downloading and loading."

    private fun cleanOut(s: String, math: Boolean = true): String {
        val t = s.replace("<end_of_turn>", "").replace("<|im_end|>", "")
            .replace(Regex("(?s)<think>.*?(</think>|$)"), "")
            .trim()
        return if (math) PlainMath.clean(t) else t
    }

    private fun cutAtSentence(t: String): String {
        val i = t.indexOfLast { it in ".!?۔؟\n" }
        return if (i > t.length * 0.4) t.substring(0, i + 1).trim() else t
    }

    // Sends one message and receives the answer piece by piece (streaming).
    // Stops early when the answer gets longer than maxChars (keeps replies fast).
    private fun streamAsk(c: Conversation, msg: Contents, maxChars: Int, math: Boolean = true, onText: (String) -> Unit): String {
        val sb = StringBuilder()
        try {
            runBlocking {
                c.sendMessageAsync(msg).collect { m ->
                    sb.append(m.toString())
                    val t = cleanOut(sb.toString(), math)
                    onText(t)
                    if (t.length >= maxChars) throw StopGen()
                }
            }
        } catch (e: Throwable) {
            var x: Throwable? = e
            var stopped = false
            while (x != null) { if (x is StopGen) stopped = true; x = x.cause }
            if (!stopped) throw e
        }
        val out = cleanOut(sb.toString(), math)
        return (if (out.length >= maxChars) cutAtSentence(out) else out)
            .ifEmpty { "I have no answer for that. Please try asking differently." }
    }

    // Runs one streamed answer; if the native engine is stuck it is rebuilt once and the question is asked again.
    private fun generate(idx: Int, msg: () -> Contents, maxChars: Int, speakIt: Boolean): String {
        fun once(): String {
            val c = conversation ?: throw IllegalStateException("no conversation")
            spokenTo = 0
            return streamAsk(c, msg(), maxChars) { t ->
                val now = SystemClock.uptimeMillis()
                if (now - lastUi > 120) { lastUi = now; setMsg(idx, t) }
                if (speakIt) speakReady(t, false)
            }
        }
        return try {
            once().also { turns++ }
        } catch (e: Throwable) {
            try {
                rebuildEngine()
                once().also { turns++ }
            } catch (e2: Throwable) {
                "I could not generate a reply: ${e2.message ?: e.message ?: "unknown error"}"
            }
        }
    }

    private fun rebuildEngine() {
        closeLlm()
        System.gc()
        engine = buildEngine()
        newConversation()
    }

    // ---------------------------------------------------------------- listening

    fun startListening() {
        if (st.listening || speech != null || urdu != null) return
        if (st.responding) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            st.note = "Microphone permission is needed. Allow it in the pop-up, or in Settings > Apps > NK Voice AI > Permissions."
            return
        }
        try { tts?.stop() } catch (_: Throwable) {}
        pending.set(0)
        try {
            if (st.lang == "ur") startUrdu() else startEnglish()
        } catch (e: Throwable) {
            stopListening()
            st.note = "Mic error: ${e.message}"
        }
    }

    private fun startEnglish() {
        val m = model
        if (m == null) {
            st.note = "The speech model is still loading. Please try again."
            return
        }
        var r: Recognizer? = null
        try {
            r = Recognizer(m, 16000f)
            val s = SpeechService(r, 16000f)
            recognizer = r
            speech = s
            s.startListening(this)
            st.listening = true
            st.status = "Listening…"
        } catch (e: Throwable) {
            speech = null
            recognizer = null
            try { r?.close() } catch (_: Throwable) {}
            st.listening = false
            st.status = "Ready"
            st.note = "Mic error: ${e.message}"
        }
    }

    private fun startUrdu() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            st.note = "Speech recognition is not available on this phone (install or enable the Google app)."
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        urdu = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (urdu !== r) return
                stopListening()
                st.note = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "I did not catch that. Tap the microphone and try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "Urdu listening needs internet (or the Urdu offline pack in Google voice settings)."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission is needed."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "The speech service is busy. Try again in a moment."
                    else -> "Urdu listening error (code $error). Try again."
                }
            }

            override fun onResults(results: Bundle?) {
                if (urdu !== r) return
                val text = try {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                } catch (_: Throwable) { "" }
                stopListening()
                if (text.isNotEmpty()) acceptText(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(i)
        st.listening = true
        st.status = "Listening…"
    }

    // Stops everything in the right order. Safe to call any number of times.
    fun stopListening() {
        val s = speech
        val r = recognizer
        speech = null
        recognizer = null
        if (s != null) {
            try { s.stop() } catch (_: Throwable) {}      // 1. stop the recording thread
            try { s.shutdown() } catch (_: Throwable) {}  // 2. release the microphone
        }
        try { r?.close() } catch (_: Throwable) {}       // 3. free the native recognizer last

        val u = urdu
        urdu = null
        if (u != null) {
            st.post {
                try { u.cancel() } catch (_: Throwable) {}
                try { u.destroy() } catch (_: Throwable) {}
            }
        }
        st.listening = false
        if (!st.responding) st.status = "Ready"
    }

    // Vosk callbacks
    override fun onResult(hypothesis: String?) {
        if (speech == null || hypothesis == null) return
        val text = try { JSONObject(hypothesis).optString("text").trim() } catch (_: Throwable) { "" }
        if (text.isEmpty()) return
        stopListening()
        acceptText(text)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        st.post {
            stopListening()
            st.note = "Speech recognition error: ${exception?.message ?: "Unknown error"}"
        }
    }
    override fun onTimeout() {}

    // ---------------------------------------------------------------- chat

    private fun acceptText(text: String) {
        st.post {
            if (!st.responding) {
                st.messages.add(Msg(true, text))
                respond(text)
            }
        }
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || st.responding) return
        stopListening()
        acceptText(clean)
    }

    // ---- UI helpers ----
    private fun setMsg(idx: Int, t: String, fileUri: String? = null, mime: String? = null, fileName: String? = null) {
        st.post { if (idx < st.messages.size) st.messages[idx] = Msg(false, t.ifBlank { "…" }, fileUri, mime, fileName) }
    }

    // Adds an empty answer bubble that is filled while the model writes. Main thread only.
    private fun beginResponse(): Int {
        st.responding = true
        st.status = "Thinking…"
        try { tts?.stop() } catch (_: Throwable) {}
        pending.set(0)
        generating = true
        spokenTo = 0
        lastUi = 0L
        st.messages.add(Msg(false, "…"))
        return st.messages.size - 1
    }

    private fun endResponse() {
        generating = false
        st.post { st.responding = false }
        if (pending.get() <= 0) finishSpeaking()
    }

    private fun finishSpeaking() {
        st.post {
            st.status = "Ready"
            if (st.handsFree && !st.showSetup) startListening()
        }
    }

    private fun sentenceDone() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            if (!generating) finishSpeaking()
        }
    }

    // Speaks every finished sentence while the model is still writing the next one.
    private fun speakReady(t: String, final: Boolean) {
        if (spokenTo > t.length) spokenTo = t.length
        while (spokenTo < t.length) {
            val m = SENT.find(t, spokenTo) ?: break
            val end = m.range.last + 1
            val s = t.substring(spokenTo, end).trim()
            spokenTo = end
            if (s.length > 1) speakPart(s)
        }
        if (final && spokenTo < t.length) {
            val r = t.substring(spokenTo).trim()
            spokenTo = t.length
            if (r.isNotEmpty()) speakPart(r)
        }
    }

    private fun speakPart(text: String) {
        val t = tts
        if (t == null || !ttsReady) return
        pending.incrementAndGet()
        st.post {
            try {
                if (ttsLang != st.lang) applyTtsLang()
                st.status = "Speaking…"
                val spoken = text.replace(Regex("[*#`_~>]"), " ").take(1500)
                val r = t.speak(spoken, TextToSpeech.QUEUE_ADD, null, "p" + System.nanoTime())
                if (r != TextToSpeech.SUCCESS) sentenceDone()
            } catch (_: Throwable) {
                sentenceDone()
            }
        }
    }

    // ---------------------------------------------------------------- chat

    private fun respond(text: String) {
        val idx = beginResponse()
        val kind = FileMaker.detect(text)
        val ok = runIo {
            try {
                when (kind) {
                    "led" -> makeLedZip(idx)
                    "pdf", "doc" -> makeDocument(idx, text, kind)
                    "zip" -> makeCodeZip(idx, text)
                    else -> runChat(idx, text)
                }
            } catch (e: Throwable) {
                setMsg(idx, "Sorry, something went wrong: ${e.message ?: "unknown error"}")
            }
            endResponse()
        }
        if (!ok) { setMsg(idx, "Sorry, please try again."); endResponse() }
    }

    private fun runChat(idx: Int, text: String) {
        if (conversation == null) { setMsg(idx, notReadyText()); return }
        // Fresh conversation every few questions: long histories are what slowed and crashed the app.
        if (turns >= MAX_TURNS) { try { newConversation() } catch (_: Throwable) {} }
        // Qwen3 can "think" first; "/no_think" switches that off so replies are fast.
        val prompt = if (ModelStore.selected(ctx).id.startsWith("qwen3")) "$text /no_think" else text
        val reply = generate(idx, { Contents.of(prompt) }, 1100, true)
        setMsg(idx, reply)
        speakReady(reply, true)
    }

    // ---------------------------------------------------------------- pictures

    fun askImage(uri: Uri, question: String) = askFile(uri, question, "image")

    private fun describe(uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment ?: "file"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        } catch (_: Throwable) {}
        return name to (ctx.contentResolver.getType(uri) ?: "")
    }

    private fun kindOf(name: String, mime: String): String {
        val n = name.lowercase()
        return when {
            mime.startsWith("image/") || Regex(".*\\.(jpg|jpeg|png|webp|bmp|gif)").matches(n) -> "image"
            mime.startsWith("video/") || Regex(".*\\.(mp4|mkv|3gp|webm|mov)").matches(n) -> "video"
            mime == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            n.endsWith(".docx") -> "docx"
            mime.startsWith("text/") || Regex(".*\\.(txt|md|csv|json|xml|kt|java|py|ino|c|cpp|js|html|log)").matches(n) -> "text"
            else -> "other"
        }
    }

    // Picture, video, PDF, Word (.docx) or text file -> the model reads it and answers.
    fun askFile(uri: Uri, question: String, forced: String? = null) {
        if (st.responding) return
        val (name, mime) = describe(uri)
        val kind = forced ?: kindOf(name, mime)
        if (kind == "other") {
            st.note = "I cannot read this kind of file yet. Try a picture, video, PDF, Word (.docx) or text file."
            return
        }
        if (kind in listOf("image", "video", "pdf") && !visionOk) {
            st.note = "Pictures, videos and PDFs need Gemma 4. Choose Gemma 4 in Setup (download it first)."
            return
        }
        stopListening()
        val q = question.trim()
        val icon = when (kind) { "image" -> "🖼"; "video" -> "🎬"; "pdf" -> "📄"; else -> "📎" }
        st.messages.add(Msg(true, if (kind == "image" && forced != null && name == "file") "$icon Picture" + (if (q.isEmpty()) "" else ": $q")
            else "$icon $name" + (if (q.isEmpty()) "" else ": $q")))
        val idx = beginResponse()
        val ok = runIo {
            try {
                var images: List<String> = emptyList()
                var text: String? = null
                when (kind) {
                    "image" -> images = listOf(prepareImage(uri))
                    "video" -> images = videoFrames(uri)
                    "pdf" -> images = pdfPages(uri)
                    "docx" -> text = readDocx(uri)
                    else -> text = readText(uri)
                }
                if (images.isEmpty() && text.isNullOrBlank()) throw IllegalStateException("nothing readable in this file")
                val base = when (kind) {
                    "image" -> if (q.isEmpty()) "Look at this picture. Tell me what it shows and give useful information about it. If there is text or a question in it, read it and answer it." else "$q (Look at the picture.)"
                    "video" -> "These are 3 frames taken from one video. " + (if (q.isEmpty()) "Describe what the video shows and give useful information." else q)
                    "pdf" -> "These are the first pages of a PDF document. " + (if (q.isEmpty()) "Summarize it and answer any question or problem in it." else q)
                    else -> (if (q.isEmpty()) "Summarize this file." else q) + "\n\nFile content:\n" + text
                }
                val noThink = if (images.isEmpty() && ModelStore.selected(ctx).id.startsWith("qwen3")) " /no_think" else ""
                val ask = base + (if (kind == "image" || kind == "video") " Reply in at most six short sentences." else "") + noThink
                if (conversation == null) { setMsg(idx, notReadyText()); return@runIo }
                if (turns >= MAX_TURNS) { try { newConversation() } catch (_: Throwable) {} }
                val reply = generate(idx, {
                    if (images.isEmpty()) Contents.of(ask)
                    else {
                        val parts = ArrayList<Content>()
                        images.forEach { parts.add(Content.ImageFile(it)) }
                        parts.add(Content.Text(ask))
                        Contents.of(*parts.toTypedArray())
                    }
                }, 1400, true)
                setMsg(idx, reply)
                speakReady(reply, true)
                // Files make the history heavy: start fresh on the next question.
                turns = MAX_TURNS
            } catch (e: Throwable) {
                setMsg(idx, "Could not read the file: ${e.message ?: "unknown error"}")
            } finally {
                endResponse()
            }
        }
        if (!ok) { setMsg(idx, "Sorry, please try again."); endResponse() }
    }

    private fun saveJpeg(bmp: Bitmap, file: String): String {
        val out = File(ctx.cacheDir, file)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return out.absolutePath
    }

    private fun shrink(bmp: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(bmp.width, bmp.height)
        if (side <= maxSide) return bmp
        val sc = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
    }

    // 3 frames from the video (20%, 50%, 80%).
    private fun videoFrames(uri: Uri): List<String> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val out = ArrayList<String>()
            for ((i, f) in listOf(0.2, 0.5, 0.8).withIndex()) {
                val bmp = r.getFrameAtTime((dur * f).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                out.add(saveJpeg(shrink(bmp, 768), "video_$i.jpg"))
            }
            return out
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    // First 3 pages of the PDF as pictures.
    private fun pdfPages(uri: Uri): List<String> {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open PDF")
        val renderer = PdfRenderer(pfd)
        try {
            val out = ArrayList<String>()
            for (i in 0 until minOf(renderer.pageCount, 3)) {
                val page = renderer.openPage(i)
                val w = 900
                val h = maxOf(1, (900f * page.height / page.width).toInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                out.add(saveJpeg(bmp, "pdf_$i.jpg"))
            }
            return out
        } finally {
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    private fun readText(uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return String(bytes, Charsets.UTF_8).take(6000)
    }

    private fun readDocx(uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val z = ZipInputStream(ins)
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = String(z.readBytes(), Charsets.UTF_8)
                    return xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .take(6000)
                }
                e = z.nextEntry
            }
        }
        return ""
    }

    // Shrinks the picture (faster + less memory), fixes camera rotation, saves it as a small JPEG.
    private fun prepareImage(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("cannot open picture")
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) { 0f }
        val longSide = maxOf(bmp.width, bmp.height)
        val scale = if (longSide > 896) 896f / longSide else 1f
        if (rot != 0f || scale != 1f) {
            val m = Matrix().apply { postRotate(rot); postScale(scale, scale) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = File(ctx.cacheDir, "ask_image.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return out.absolutePath
    }

    // ---------------------------------------------------------------- files (PDF / document / ZIP)

    private fun fileDone(idx: Int, reply: String, name: String, mime: String, bytes: ByteArray) {
        val uri = FileMaker.save(ctx, name, mime, bytes)
        setMsg(idx, reply, uri.toString(), mime, name)
        speakReady(reply.substringBefore("\n"), true)
    }

    private fun makeLedZip(idx: Int) {
        val ur = lang == "ur"
        fileDone(
            idx,
            if (ur) "LED بلنکنگ کوڈ کی ZIP فائل تیار ہے۔ اس میں Arduino، ESP32، Raspberry Pi اور Pico کا کوڈ ہے۔ ڈاؤن لوڈ کا بٹن دبائیں۔"
            else "Your LED blinking ZIP is ready. It has code for Arduino, ESP32, Raspberry Pi and Raspberry Pi Pico, plus wiring notes.\nTap Download to save it in your phone Downloads, or Open / Share.",
            "LED_blinking_code_${FileMaker.stamp()}.zip", "application/zip", FileMaker.ledZip()
        )
    }

    // Asks the model for a short document, then builds the PDF / .doc file on the phone.
    private fun makeDocument(idx: Int, request: String, kind: String) {
        val e = engine
        if (e == null || conversation == null) { setMsg(idx, notReadyText()); return }
        val topic = FileMaker.topicOf(request)
        val langName = if (lang == "ur") "Urdu (written in Urdu script)" else "English"
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        var body = ""
        try {
            val c = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You write short, clear study documents in $langName. Plain text only. " +
                            "Format: first line '# Title'. Then at most 4 sections, each starting with a line '## Heading', " +
                            "followed by 2 to 4 short lines starting with '- ' or one short paragraph. About 250 words in total. " +
                            "No other markdown, no emojis, no LaTeX or dollar signs: write maths in plain text like x^2 or 1/2."
                    ),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6)
                )
            )
            conversation = c
            val prompt = if (ModelStore.selected(ctx).id.startsWith("qwen3")) "Write a study document about: $topic /no_think" else "Write a study document about: $topic"
            setMsg(idx, if (lang == "ur") "دستاویز لکھ رہا ہوں… کچھ منٹ لگ سکتے ہیں" else "Writing your document… this can take a few minutes on a phone.")
            spokenTo = 0
            body = streamAsk(c, Contents.of(prompt), 2600) { t ->
                val now = SystemClock.uptimeMillis()
                if (now - lastUi > 600) { lastUi = now; setMsg(idx, "Writing your document… ${t.length} characters so far") }
            }
        } finally {
            try { newConversation() } catch (_: Throwable) {}
        }
        val title = FileMaker.titleOf(topic)
        val pdf = kind == "pdf"
        val name = FileMaker.safeName(topic) + "_" + FileMaker.stamp() + if (pdf) ".pdf" else ".doc"
        val bytes = if (pdf) FileMaker.pdf(title, body) else FileMaker.doc(title, body)
        fileDone(
            idx,
            (if (lang == "ur") "آپ کی ${if (pdf) "PDF" else "دستاویز"} تیار ہے۔ ڈاؤن لوڈ کا بٹن دبائیں۔"
            else "Your ${if (pdf) "PDF" else "document"} on \"$title\" is ready.\nTap Download to save it in your phone Downloads, or Open / Share."),
            name, if (pdf) "application/pdf" else "application/msword", bytes
        )
    }

    // Code request that should come as a ZIP: the model writes the code, the app zips it with a README.
    private fun makeCodeZip(idx: Int, request: String) {
        val e = engine
        if (e == null || conversation == null) { setMsg(idx, notReadyText()); return }
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        var code = ""
        try {
            val c = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are a coding assistant. Output ONLY the complete source code for the request, in one file, " +
                            "with short comments. No explanation before or after."
                    ),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)
                )
            )
            conversation = c
            val prompt = if (ModelStore.selected(ctx).id.startsWith("qwen3")) "$request /no_think" else request
            setMsg(idx, "Writing your code… this can take a few minutes on a phone.")
            code = streamAsk(c, Contents.of(prompt), 3500, false) { t ->
                val now = SystemClock.uptimeMillis()
                if (now - lastUi > 600) { lastUi = now; setMsg(idx, "Writing your code… ${t.length} characters so far") }
            }
        } finally {
            try { newConversation() } catch (_: Throwable) {}
        }
        val (fname, clean) = FileMaker.codeFile(request, code)
        val bytes = FileMaker.zip(listOf(
            fname to clean,
            "README.txt" to "Request: $request\n\nThis code was written by NK Voice AI on your phone. Please read it and test it before using it.\n"
        ))
        fileDone(idx, "Your code ZIP is ready.\nTap Download to save it in your phone Downloads, or Open / Share.",
            "code_${FileMaker.stamp()}.zip", "application/zip", bytes)
    }

    // Runs work on the single background thread. Never throws.
    private fun runIo(block: () -> Unit): Boolean {
        return try {
            io.execute {
                try { block() } catch (_: Throwable) {}
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun release() {
        stopListening()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        val m = model
        model = null
        runIo {
            closeLlm()
            try { m?.close() } catch (_: Throwable) {}
        }
        try { io.shutdown() } catch (_: Throwable) {}
    }
}
