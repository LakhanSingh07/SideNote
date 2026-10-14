package com.sidedeck.notes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceInputHelper(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onReady()
        fun onRmsChanged(rmsdB: Float)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(errorMsg: String)
        fun onStateChanged(isListening: Boolean)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set
    var currentLanguage: String = "auto"

    fun startListening(lang: String = currentLanguage) {
        currentLanguage = lang
        destroy()

        val langTag = when (lang.lowercase()) {
            "hi", "hi-in", "hindi" -> "hi-IN"
            "en", "en-in" -> "en-IN"
            "en-us" -> "en-US"
            else -> Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
        }

        try {
            // Prefer Google speech recognition component if available, else standard system
            val googleComp = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            )
            speechRecognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context, googleComp)
            } catch (_: Exception) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } ?: SpeechRecognizer.createSpeechRecognizer(context)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US", "hi"))
                // Allow longer silence before giving up
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    callback.onStateChanged(true)
                    callback.onReady()
                }

                override fun onBeginningOfSpeech() {
                    isListening = true
                    callback.onStateChanged(true)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    callback.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    callback.onStateChanged(false)
                }

                override fun onError(error: Int) {
                    isListening = false
                    callback.onStateChanged(false)
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                        SpeechRecognizer.ERROR_NETWORK -> "Network connection required"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy. Retrying…"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        else -> "Could not recognize speech ($error)"
                    }
                    callback.onError(message)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    callback.onStateChanged(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    callback.onFinalResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        callback.onPartialResult(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            callback.onStateChanged(false)
            callback.onError("Speech service not accessible: ${e.localizedMessage ?: "error"}")
        }
    }

    fun stopListening() {
        try {
            isListening = false
            speechRecognizer?.stopListening()
            callback.onStateChanged(false)
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            isListening = false
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
    }
}
