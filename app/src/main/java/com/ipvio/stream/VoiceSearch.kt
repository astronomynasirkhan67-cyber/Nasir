package com.ipvio.stream

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice search.
 * - Devices with speech recognition (Google TV, Android TV, phones): microphone button → system speech recognition.
 * - Fire TV: Amazon does not let apps use their own speech recognition (the microphone key belongs to Alexa);
 *   there is no microphone button there.
 */
object VoiceSearch {
    /** Is there a speech recognizer that apps may use? */
    fun available(c: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(c) &&
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(c.packageManager) != null
    }.getOrDefault(false)

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.locale().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, Lang.tr("Film, Serie oder Sender sagen …"))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

