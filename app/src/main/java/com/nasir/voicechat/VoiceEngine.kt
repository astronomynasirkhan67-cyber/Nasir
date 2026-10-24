package com.nasir.voicechat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VoiceEngine(private val ctx: Context, private val st: AppState) : RecognitionListener {

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var llm: LlmInference? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var generating = false
    private val queued = AtomicInteger(0)

    fun init() {
        tts = TextToSpeech(ctx) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            applyTtsLang()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { partFinished() }
            override fun onDone(utteranceId: String?) { partFinished() }
        })
        loadVosk()
        loadLlm()
    }

    private fun partFinished() {
        if (queued.decrementAndGet() <= 0 && !generating) st.post { afterSpeech() }
    }

    private fun afterSpeech() {
        st.status = "Ready"
        if (st.handsFree && !st.showSetup) startListening()
    }

    private fun applyTtsLang() {
        val loc = if (st.lang == "hi") Locale("hi", "IN") else Locale("en", "IN")
        val r = tts?.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            st.post { st.note = "Install this voice in phone Settings > Text-to-speech" }
        }
    }

    fun setLang(l: String) {
        stopListening()
        st.lang = l
        applyTtsLang()
        loadVosk()
    }

    fun loadVosk() {
        io.execute {
            try {
                model?.close()
                model = null
                if (ModelStore.hasVosk(ctx, st.lang)) {
                    model = Model(ModelStore.voskDir(ctx, st.lang).absolutePath)
                } else {
                    st.post { st.note = "Vosk model not found. Download it in Setup." }
                }
            } catch (e: Throwable) {
                st.post { st.note = "Vosk error: ${e.message}" }
            }
        }
    }

    fun loadLlm() {
        io.execute {
            try {
                if (ModelStore.hasLlm(ctx)) {
                    llm?.close()
                    llm = LlmInference.createFromOptions(
                        ctx,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(ModelStore.llmFile(ctx).absolutePath)
                            .setMaxTokens(1024)
                            .build()
                    )
                    st.post { st.note = "AI model loaded" }
                }
            } catch (e: Throwable) {
                st.post { st.note = "AI model load error: ${e.message}" }
            }
        }
    }

    fun startListening() {
        if (speech != null || generating) return
        val m = model
        if (m == null) {
            st.note = "Vosk model not loaded yet"
            return
        }
        try {
            val s = SpeechService(Recognizer(m, 16000f), 16000f)
            speech = s
            s.startListening(this)
            st.listening = true
            st.status = "Listening…"
        } catch (e: Throwable) {
            speech = null
            st.note = "Mic error: ${e.message}"
        }
    }

    fun stopListening() {
        try { speech?.shutdown() } catch (_: Throwable) {}
        speech = null
        st.listening = false
        st.status = "Ready"
    }

    override fun onResult(hypothesis: String?) {
        if (speech == null || hypothesis == null) return
        val text = try { JSONObject(hypothesis).optString("text").trim() } catch (_: Throwable) { "" }
        if (text.isEmpty()) return
        submitText(text)
    }

    // ---------- public actions (call on main thread) ----------

    fun submitText(text: String) {
        if (generating) return
        stopListening()
        st.messages.add(Msg(true, text))
        st.status = "Thinking…"
        val code = isCodeRequest(text)
        generating = true
        io.execute { if (code) generateCode(text) else generateChat(chatPrompt(text)) }
    }

    fun summarize(label: String, ex: Extracted) {
        if (generating) return
        stopListening()
        st.messages.add(Msg(true, "Summarize: $label"))
        st.status = "Thinking…"
        generating = true
        val body = ex.text.trim()
        if (body.isEmpty()) {
            io.execute { reply("I could not find any readable content in this ${if (ex.isImage) "picture" else "file"}.") }
            return
        }
        val used = body.take(Extractor.MAX_CHARS)
        val cut = body.length > used.length
        val langName = if (st.lang == "hi") "Hindi" else "English"
        val prompt = wrap(
            if (ex.isImage)
                "These details were extracted from a picture:\n$used\n\nDescribe what the picture shows in $langName in 2 to 3 short sentences. If there is text, summarize it."
            else
                "Summarize this document in $langName in 3 to 4 short sentences.\n\n$used"
        )
        if (cut) st.note = "Long file: only the first part was summarized."
        io.execute { generateChat(prompt) }
    }

    // ---------- prompts ----------

    private fun isCodeRequest(t: String): Boolean {
        if (st.codeMode) return true
        val l = t.lowercase()
        return listOf("code", "कोड", "script", "program").any { l.contains(it) }
    }

    private fun wrap(user: String): String =
        if (ModelStore.kind(ctx) == "gemma")
            "<start_of_turn>user\n$user<end_of_turn>\n<start_of_turn>model\n"
        else
            "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    private fun chatPrompt(text: String): String {
        val langName = if (st.lang == "hi") "Hindi" else "English"
        return wrap("You are a voice assistant. Reply in $langName in 1 or 2 very short sentences. $text")
    }

    private fun clean(s: String) =
        s.replace("<end_of_turn>", "").replace("<|im_end|>", "").trim()

    // ---------- speaking ----------

    private fun speakPart(s: String) {
        if (!ttsReady || s.isEmpty()) return
        queued.incrementAndGet()
        val r = tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "p" + System.nanoTime())
        if (r != TextToSpeech.SUCCESS) queued.decrementAndGet()
    }

    private fun sentenceEnd(sb: StringBuilder): Int {
        var idx = -1
        for (i in sb.indices) if (sb[i] in ".!?।؟\n") idx = i
        return if (idx >= 11) idx else -1
    }

    private fun reply(msg: String) {
        queued.set(0)
        st.post { st.messages.add(Msg(false, msg)); st.status = "Speaking…" }
        speakPart(msg)
        generating = false
        if (queued.get() <= 0) st.post { afterSpeech() }
    }

    // ---------- model calls ----------

    private fun runModel(prompt: String, onChunk: (String, Boolean) -> Unit) {
        val l = llm ?: throw IllegalStateException("AI model is not loaded")
        val session = LlmInferenceSession.createFromOptions(
            l,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.7f)
                .build()
        )
        session.addQueryChunk(prompt)
        session.generateResponseAsync { partial, done ->
            onChunk(partial ?: "", done)
            if (done) io.execute { try { session.close() } catch (_: Throwable) {} }
        }
    }

    private fun generateChat(prompt: String) {
        queued.set(0)
        if (llm == null) {
            reply("The AI model is missing. Download it in Setup.")
            return
        }
        st.post { st.messages.add(Msg(false, "…")) }
        val full = StringBuilder()
        val pending = StringBuilder()
        try {
            runModel(prompt) { chunk, done ->
                full.append(chunk)
                pending.append(chunk)
                val idx = sentenceEnd(pending)
                if (idx >= 0) {
                    val s = clean(pending.substring(0, idx + 1))
                    pending.delete(0, idx + 1)
                    st.post { st.status = "Speaking…" }
                    speakPart(s)
                }
                val shown = clean(full.toString())
                st.post {
                    if (st.messages.isNotEmpty() && shown.isNotEmpty())
                        st.messages[st.messages.size - 1] = Msg(false, shown)
                }
                if (done) {
                    speakPart(clean(pending.toString()))
                    pending.setLength(0)
                    generating = false
                    if (queued.get() <= 0) st.post { afterSpeech() }
                }
            }
        } catch (e: Throwable) {
            generating = false
            st.post { st.note = "Error: ${e.message}"; st.status = "Ready" }
        }
    }

    private fun generateCode(request: String) {
        queued.set(0)
        if (llm == null) {
            reply("The AI model is missing. Download it in Setup.")
            return
        }
        val prompt = wrap(
            "You are a coding assistant. Write the code for this request: $request\n" +
                "Output every file in exactly this format and nothing else:\n" +
                "### FILE: path/name.ext\n```\ncode\n```\n" +
                "Keep the project small but complete."
        )
        st.post { st.messages.add(Msg(false, "Writing code…")); st.status = "Writing code…" }
        val full = StringBuilder()
        try {
            runModel(prompt) { chunk, done ->
                full.append(chunk)
                val n = full.length
                if (!done) {
                    st.post {
                        if (st.messages.isNotEmpty())
                            st.messages[st.messages.size - 1] = Msg(false, "Writing code… $n characters")
                    }
                } else {
                    io.execute {
                        val msg = try {
                            val files = ZipMaker.parse(clean(full.toString()))
                            val name = "nkvoice-code-" + (System.currentTimeMillis() / 1000) + ".zip"
                            val where = ZipMaker.save(ctx, name, files, request)
                            "Done! ${files.size} file(s) saved in a ZIP: $where"
                        } catch (e: Throwable) {
                            "Could not save the ZIP: ${e.message}"
                        }
                        st.post { st.messages[st.messages.size - 1] = Msg(false, msg) }
                        speakPart("Your code ZIP is ready in the Downloads folder.")
                        generating = false
                        if (queued.get() <= 0) st.post { afterSpeech() }
                    }
                }
            }
        } catch (e: Throwable) {
            generating = false
            st.post { st.note = "Error: ${e.message}"; st.status = "Ready" }
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { st.note = "Error: ${exception?.message}" }
    override fun onTimeout() {}

    fun release() {
        stopListening()
        try { model?.close() } catch (_: Throwable) {}
        try { llm?.close() } catch (_: Throwable) {}
        tts?.shutdown()
        io.shutdown()
    }
}
