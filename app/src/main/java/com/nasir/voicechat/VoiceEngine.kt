package com.nasir.voicechat

import android.content.Context
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

class VoiceEngine(private val ctx: Context, private val st: AppState) : RecognitionListener {

    private var model: Model? = null
    private var speech: SpeechService? = null

    // Gemma 4 (LiteRT-LM). Every access happens on the single `io` thread.
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val io = Executors.newSingleThreadExecutor()

    fun init() {
        tts = TextToSpeech(ctx) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            applyTtsLang()
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

    private fun applyTtsLang() {
        val loc = if (st.lang == "hi") Locale("hi", "IN") else Locale("en", "IN")
        val r = tts?.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            st.post { st.note = "Install the selected text-to-speech voice in your phone settings." }
        }
    }

    fun setLang(l: String) {
        stopListening()
        st.lang = l
        applyTtsLang()
        loadVosk()
        // New conversation so Gemma answers in the newly selected language.
        io.execute { try { newConversation() } catch (_: Throwable) {} }
    }

    fun loadVosk() {
        io.execute {
            try {
                model?.close()
                model = null
                if (ModelStore.hasVosk(ctx, st.lang)) {
                    model = Model(ModelStore.voskDir(ctx, st.lang).absolutePath)
                } else {
                    st.post { st.note = "Speech model not found yet." }
                }
            } catch (e: Throwable) {
                st.post { st.note = "Vosk error: ${e.message}" }
            }
        }
    }

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
        val langName = if (st.lang == "hi") "Hindi" else "English"
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are NK Voice AI, a helpful and careful assistant. Reply only in $langName. " +
                        "Keep answers short and clear. Use plain text only, no markdown, no emojis, " +
                        "because your reply is read aloud."
                ),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )
        )
    }

    fun loadLlm() {
        io.execute {
            try {
                if (!ModelStore.hasLlm(ctx)) return@execute
                st.post { st.gemmaReady = false; st.note = "Loading Gemma 4… (first time can take a minute)" }
                try { conversation?.close() } catch (_: Throwable) {}
                conversation = null
                try { engine?.close() } catch (_: Throwable) {}
                engine = null
                val path = ModelStore.llmFile(ctx).absolutePath
                // CPU is slower but works on every phone. The GPU backend loaded fine on
                // the Vivo Y200 but crashed on the first reply ("Failed to invoke the compiled model").
                engine = openEngine(path, Backend.CPU())
                newConversation()
                st.post { st.gemmaReady = true; st.note = "Gemma 4 is ready ✓" }
            } catch (e: Throwable) {
                st.post { st.gemmaReady = false; st.note = "Gemma load error: ${e.message}" }
            }
        }
    }

    fun startListening() {
        if (speech != null) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            st.note = "Microphone permission is needed. Allow it in the pop-up, or in Settings > Apps > NK Voice AI > Permissions."
            return
        }
        val m = model
        if (m == null) {
            st.note = "The speech model is still loading. Please try again."
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
        stopListening()
        st.post { st.messages.add(Msg(true, text)); respond(text) }
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || st.responding) return
        stopListening()
        st.post { st.messages.add(Msg(true, clean)); respond(clean) }
    }

    private fun respond(text: String) {
        st.responding = true
        st.status = "Thinking…"
        io.execute {
            val reply = think(text)
            st.post {
                st.messages.add(Msg(false, reply))
                st.responding = false
                speak(reply)
            }
        }
    }

    private fun think(text: String): String {
        val c = conversation
            ?: return "Gemma is not ready yet. Please wait for the model to finish downloading and loading."
        return try {
            ask(c, text)
        } catch (e: Throwable) {
            // The native engine may be stuck in a failed state: rebuild it once and retry.
            try {
                rebuildEngine()
                val c2 = conversation ?: throw IllegalStateException("engine not available")
                ask(c2, text)
            } catch (e2: Throwable) {
                "I could not generate a reply: ${e2.message ?: e.message ?: "unknown error"}"
            }
        }
    }

    private fun ask(c: Conversation, text: String): String =
        c.sendMessage(text).toString().replace("<end_of_turn>", "").trim()
            .ifEmpty { "I have no answer for that. Please try asking differently." }

    private fun rebuildEngine() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        engine = openEngine(ModelStore.llmFile(ctx).absolutePath, Backend.CPU())
        newConversation()
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            st.status = "Ready"
            return
        }
        st.status = "Speaking…"
        val spoken = text.replace(Regex("[*#`_~>]"), " ")
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { st.post { st.note = "Speech recognition error: ${exception?.message ?: "Unknown error"}"; st.listening = false } }
    override fun onTimeout() {}

    fun release() {
        stopListening()
        try { model?.close() } catch (_: Throwable) {}
        io.execute {
            try { conversation?.close() } catch (_: Throwable) {}
            try { engine?.close() } catch (_: Throwable) {}
        }
        tts?.shutdown()
        io.shutdown()
    }
}
