package com.ruskcoder.messages.transcribe

import android.util.Log
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the bubble should be showing about a clip's transcript right now. */
enum class TranscriptionState {
    /**
     * This surface does not do transcripts at all — a preview, a chat-list peek.
     *
     * Distinct from [IDLE], and the distinction is the whole point: a bubble lifted by a long press
     * has no action to offer, but it must still draw the row, or the bubble changes height in the
     * middle of the animation lifting it out of the conversation.
     */
    OFF,

    /** Nothing has been asked, or the answer is already stored on the message. */
    IDLE,

    /** The model is working on this clip. */
    RUNNING,

    /** The last attempt did not produce anything, and the button should offer to try again. */
    FAILED,

    /** No speech model is downloaded yet, so the action sends you to settings rather than running. */
    NO_MODEL,
}

/**
 * Runs transcriptions on behalf of the whole app, and remembers which are in flight.
 *
 * App-scoped rather than owned by the conversation, because a transcription outlives the screen it
 * was started from: `base.en` takes seconds on a phone, and backing out of a conversation while it
 * runs should not throw the work away. The result goes to the database, so whatever is on screen
 * when it lands picks it up through the same flow every other message change arrives on.
 *
 * Failures are held per message rather than raised: the only place the news is useful is on the
 * bubble somebody pressed, and a dialog over the conversation would be a heavier answer than the
 * question deserves.
 */
class VoiceTranscriptions(
    private val transcriber: VoiceTranscriber,
    private val chats: ChatRepository,
    private val scope: CoroutineScope,
) {
    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Message ids currently being transcribed. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _failed = MutableStateFlow<Set<String>>(emptySet())

    /** Message ids whose last attempt failed, cleared when one is retried. */
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    /** False where the native library is missing, which hides the button entirely. */
    val isSupported: Boolean get() = transcriber.isSupported

    /**
     * Transcribe one voice message, if it is not already being transcribed.
     *
     * Returns immediately; watch [running] for progress and the message's own `transcript` for the
     * result.
     */
    fun transcribe(message: Message.Audio) {
        if (message.uri.isBlank()) return

        // The set is the lock, claimed with a compare-and-set rather than `update`: `update` may
        // run its lambda more than once under contention, so counting the claim inside it could
        // report a run started that a retry then decided against.
        val current = _running.value
        if (message.id in current) return
        if (!_running.compareAndSet(current, current + message.id)) return
        _failed.update { it - message.id }

        scope.launch {
            try {
                when (val result = transcriber.transcribe(message.uri)) {
                    is VoiceTranscriber.Result.Text -> chats.setTranscript(message.id, result.text)
                    // Stored as an answer of its own. Left unset it would look untranscribed, and
                    // the button would invite you to spend another few seconds finding out the
                    // same nothing.
                    VoiceTranscriber.Result.NoSpeech -> chats.setTranscript(message.id, "")
                    // Only reachable if the model went away between the press and the run — the
                    // UI decides readiness up front, from what is installed, so it never offers a
                    // press that would land here.
                    VoiceTranscriber.Result.NoModel,
                    VoiceTranscriber.Result.Unavailable,
                    VoiceTranscriber.Result.Failed -> {
                        Log.w(TAG, "could not transcribe ${message.id}: $result")
                        _failed.update { it + message.id }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "transcription of ${message.id} failed", e)
                _failed.update { it + message.id }
            } finally {
                _running.update { it - message.id }
            }
        }
    }

    private companion object {
        const val TAG = "VoiceTranscriptions"
    }
}
