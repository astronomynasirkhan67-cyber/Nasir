package com.nasir.voicechat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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
    private var llm: LlmInference? = null
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
            st.post { st.note = "This text-to-speech voice is unavailable. Install the selected language voice in your phone settings." }
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
                            .setMaxTokens(512)
                            .build()
                    )
                }
            } catch (e: Throwable) {
                st.post { st.note = "Gemma load error: ${e.message}" }
            }
        }
    }

    fun startListening() {
        if (speech != null) return
        val m = model
        if (m == null) {
            st.note = "Vosk model is not loaded yet. Please wait or download it in Setup."
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
        st.messages.add(Msg(true, text))
        st.status = "Thinking…"
        io.execute {
            val reply = think(text)
            st.post {
                st.messages.add(Msg(false, reply))
                speak(reply)
            }
        }
    }

    private fun think(text: String): String {
        val l = llm
        if (l == null) {
            return if (st.lang == "hi") "Gemma model is missing. You said: $text" else "Gemma model is missing. You said: $text"
        }
        val langName = if (st.lang == "hi") "Hindi" else "English"
        val prompt = "<start_of_turn>user\nYou are a friendly voice assistant. " +
            "Answer in $langName in 1 to 3 short sentences. $text<end_of_turn>\n<start_of_turn>model\n"
        return try {
            l.generateResponse(prompt).replace("<end_of_turn>", "").trim()
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            st.status = "Ready"
            return
        }
        st.status = "Speaking…"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
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
