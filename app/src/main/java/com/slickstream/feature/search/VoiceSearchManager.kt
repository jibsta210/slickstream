package com.slickstream.feature.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live state of the voice recognizer, surfaced to the UI as a single immutable snapshot.
 *
 * - [isListening] drives the pulsing mic animation.
 * - [partialTranscript] is the in-flight (non-final) hypothesis to show live under the field.
 * - [finalTranscript] is set once the engine commits a result; the ViewModel consumes it then
 *   the manager clears it back to null on the next [start].
 * - [rmsDb] is the smoothed mic loudness (~ -2f..12f), useful for reactive ring scaling.
 * - [error] is a human-readable, non-fatal message (e.g. "No match, try again").
 */
data class VoiceState(
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String? = null,
    val rmsDb: Float = 0f,
    val error: String? = null,
    val available: Boolean = true,
)

/**
 * Thin, lifecycle-safe wrapper around [android.speech.SpeechRecognizer].
 *
 * The screen observes [state] and calls [start]/[stop]. Microphone permission is NOT requested
 * here — instead [hasPermission] lets the screen decide to launch the system permission flow via
 * `rememberLauncherForActivityResult`. All SpeechRecognizer calls are marshalled to the main
 * thread because the platform API is not thread-safe.
 */
@Singleton
class VoiceSearchManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : RecognitionListener {

    private val _state = MutableStateFlow(
        VoiceState(available = SpeechRecognizer.isRecognitionAvailable(context)),
    )
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Whether on-device speech recognition is usable at all. */
    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** True when RECORD_AUDIO is already granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val intent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /**
     * Begin listening. No-op (with an error state) if recognition is unavailable or permission
     * has not been granted — the screen is responsible for requesting it first.
     */
    fun start() {
        if (!isAvailable) {
            _state.value = _state.value.copy(
                available = false,
                isListening = false,
                error = "Voice search isn't available on this device.",
            )
            return
        }
        if (!hasPermission()) {
            _state.value = _state.value.copy(
                isListening = false,
                error = "Microphone permission is required for voice search.",
            )
            return
        }
        runOnMain {
            // Fresh recognizer each session avoids stuck-state bugs on some OEM engines.
            destroyRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
            _state.value = VoiceState(
                isListening = true,
                partialTranscript = "",
                finalTranscript = null,
                error = null,
                available = true,
            )
            runCatching { recognizer?.startListening(intent) }
                .onFailure { fail("Couldn't start voice search.") }
        }
    }

    /** Stop listening and finalize whatever the engine has so far. Safe to call when idle. */
    fun stop() {
        runOnMain {
            runCatching { recognizer?.stopListening() }
            _state.value = _state.value.copy(isListening = false)
        }
    }

    /** Hard cancel + release the recognizer (e.g. when the screen leaves composition). */
    fun cancel() {
        runOnMain {
            destroyRecognizer()
            _state.value = _state.value.copy(isListening = false, partialTranscript = "")
        }
    }

    /** Clear a consumed final transcript / dismissed error so it doesn't re-fire. */
    fun acknowledge() {
        _state.value = _state.value.copy(finalTranscript = null, error = null)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(isListening = false, error = message)
    }

    // --- RecognitionListener ---

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = _state.value.copy(isListening = true, error = null)
    }

    override fun onBeginningOfSpeech() {
        _state.value = _state.value.copy(isListening = true)
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Smooth a little so the UI ring doesn't jitter, and only emit on a VISIBLE change —
        // recognizers fire this at 10-50Hz, and each emission recomposes whatever collects the
        // state. Sub-0.5dB steps are invisible on the pulse ring anyway.
        val prev = _state.value.rmsDb
        val next = prev * 0.6f + rmsdB * 0.4f
        if (kotlin.math.abs(next - prev) >= 0.5f) {
            _state.value = _state.value.copy(rmsDb = next)
        }
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        _state.value = _state.value.copy(isListening = false)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            _state.value = _state.value.copy(partialTranscript = text)
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        _state.value = _state.value.copy(
            isListening = false,
            partialTranscript = text.ifEmpty { _state.value.partialTranscript },
            finalTranscript = text.ifEmpty { _state.value.partialTranscript.takeIf { it.isNotEmpty() } },
        )
        runOnMain { destroyRecognizer() }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — tap the mic and try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice search."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network issue — voice search couldn't reach the recognizer."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone error — please try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice search is busy — try again in a moment."
            else -> "Voice search failed — tap the mic to retry."
        }
        // A "no match" is benign; surface it but don't treat as fatal availability loss.
        _state.value = _state.value.copy(isListening = false, error = message)
        runOnMain { destroyRecognizer() }
    }
}
