package com.nasir.voicechat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale
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

    @Volatile private var lang = "en"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsLang = ""
    private val io = Executors.newSingleThreadExecutor()

    companion object {
        // A new Gemma conversation is started after this many questions (keeps memory stable).
        private const val MAX_TURNS = 5
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
            override fun onError(utteranceId: String?) {
                st.post { st.status = "Ready" }
            }
            override fun onDone(utteranceId: String?) {
                st.post {
                    st.status = "Ready"
                    if (st.handsFree && !st.showSetup) startListening()
                }
            }
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

    private fun openEngine(path: String, backend: Backend): Engine {
        val e = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = ctx.cacheDir.path))
        try {
            e.initialize()
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) {}
            throw t
        }
        return e
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
                val path = ModelStore.llmFile(ctx).absolutePath
                // CPU is slower but stable. The GPU backend crashed on the first reply on the Vivo Y200.
                engine = openEngine(path, Backend.CPU())
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

    private fun think(text: String): String {
        if (conversation == null) {
            return if (lang == "ur") "ابھی ماڈل تیار نہیں ہے۔ براہ کرم کچھ دیر انتظار کریں۔"
            else "The model is not ready yet. Please wait for it to finish downloading and loading."
        }
        // Fresh conversation every few questions: long histories are what slowed and crashed the app.
        if (turns >= MAX_TURNS) {
            try { newConversation() } catch (_: Throwable) {}
        }
        return try {
            val r = ask(conversation ?: throw IllegalStateException("no conversation"), text)
            turns++
            r
        } catch (e: Throwable) {
            // The native engine may be stuck in a failed state: rebuild it once and retry.
            try {
                rebuildEngine()
                val r = ask(conversation ?: throw IllegalStateException("engine not available"), text)
                turns++
                r
            } catch (e2: Throwable) {
                "I could not generate a reply: ${e2.message ?: e.message ?: "unknown error"}"
            }
        }
    }

    private fun ask(c: Conversation, text: String): String {
        // Qwen3 can "think" first; "/no_think" switches that off so replies are short and fast.
        val prompt = if (ModelStore.selected(ctx).id == "qwen3") "$text /no_think" else text
        return c.sendMessage(prompt).toString()
            .replace("<end_of_turn>", "")
            .replace("<|im_end|>", "")
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
            .ifEmpty { "I have no answer for that. Please try asking differently." }
    }

    private fun rebuildEngine() {
        closeLlm()
        System.gc()
        engine = openEngine(ModelStore.llmFile(ctx).absolutePath, Backend.CPU())
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

    private fun respond(text: String) {
        st.responding = true
        st.status = "Thinking…"
        val ok = runIo {
            val reply = try {
                think(text)
            } catch (_: Throwable) {
                "Sorry, something went wrong. Please try again."
            }
            st.post {
                st.messages.add(Msg(false, reply))
                st.responding = false
                speak(reply)
            }
        }
        if (!ok) {
            st.responding = false
            st.status = "Ready"
        }
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) {
            st.status = "Ready"
            return
        }
        try {
            if (ttsLang != st.lang) applyTtsLang()
            st.status = "Speaking…"
            val spoken = text.replace(Regex("[*#`_~>]"), " ").take(3500)
            val r = t.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
            if (r != TextToSpeech.SUCCESS) st.status = "Ready"
        } catch (_: Throwable) {
            st.status = "Ready"
        }
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
