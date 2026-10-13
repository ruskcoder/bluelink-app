package com.ruskcoder.messages.ui.components

import android.media.MediaPlayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How often the progress bar is asked where the clip has got to. */
private const val PROGRESS_MS = 60L

/**
 * A voice message in the conversation: play button, waveform scrubbed by playback, duration.
 *
 * The player is created lazily on the first press and released when the row leaves the screen —
 * a conversation with thirty clips in it would otherwise hold thirty decoders open for the sake of
 * a waveform nobody has asked to hear.
 *
 * Only one clip plays at a time, which is what [VoicePlayback] enforces: pressing play on a second
 * message stops the first. Two people talking over each other is what the conversation was, not
 * how you listen back to it.
 */
@Composable
fun VoiceMessage(
    uri: String,
    duration: Float,
    waveform: List<Float>,
    accent: Color,
    onAccent: Color,
    muted: Color,
    /** What the clip says. Null means nobody has asked; empty means asked, and there were no words. */
    transcript: String? = null,
    transcribing: Boolean = false,
    transcriptFailed: Boolean = false,
    /** No speech model is downloaded, so the action offers to go and get one instead. */
    transcriptNeedsModel: Boolean = false,
    /** Whether the transcript row is drawn at all — see [AudioClip.transcriptOffered]. */
    transcriptOffered: Boolean = false,
    /** Null leaves the clip exactly as it was — no action, no room taken for one. */
    onTranscribe: (() -> Unit)? = null,
    /** Playback started. See [com.ruskcoder.messages.ui.components.bubbles.AudioClip.onPlay]. */
    onPlay: (() -> Unit)? = null,
) {
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var elapsed by remember(uri) { mutableFloatStateOf(0f) }

    // Everything goes when the row is disposed — scrolled away, or the conversation closed.
    DisposableEffect(uri) {
        onDispose {
            VoicePlayback.release(uri)
            player?.release()
            player = null
        }
    }

    // Someone else took the floor: stop, and rewind to the start rather than leaving a half-played
    // clip that looks paused.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (isActive && playing) {
            val active = player
            if (active == null || !active.isPlaying) break
            elapsed = active.currentPosition / 1000f
            delay(PROGRESS_MS)
        }
    }

    // Created on first press *or* first scrub — reaching for the waveform before the play button is a
    // perfectly ordinary way to start listening halfway in.
    fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val created = MediaPlayer()
        runCatching {
            created.setDataSource(uri)
            created.prepare()
        }.onFailure {
            created.release()
            return null
        }
        created.setOnCompletionListener {
            playing = false
            elapsed = 0f
            created.seekTo(0)
        }
        player = created
        return created
    }

    fun toggle() {
        val existing = player
        if (existing != null && existing.isPlaying) {
            existing.pause()
            playing = false
            return
        }
        val active = ensurePlayer() ?: return
        // Replaying a clip that has run to the end starts it over rather than doing nothing.
        if (active.currentPosition >= active.duration - 50) active.seekTo(0)
        VoicePlayback.claim(uri) {
            runCatching { active.pause() }
            playing = false
        }
        active.start()
        playing = true
        onPlay?.invoke()
    }

    // Dragging along the waveform moves the clip to that point. Works whether or not it is playing:
    // paused, the bar just jumps there ready to resume; playing, the sound follows the finger.
    fun scrubTo(fraction: Float) {
        val active = ensurePlayer() ?: return
        val totalMs = active.duration.takeIf { it > 0 } ?: (duration * 1000f).toInt()
        val target = (fraction.coerceIn(0f, 1f) * totalMs).toInt()
        runCatching { active.seekTo(target) }
        elapsed = target / 1000f
    }

    // The clip's real length once it is known: what was recorded and what the file decodes to can
    // differ by a hair, and a bar that never quite fills reads as playback that never finished.
    val length = player?.duration?.takeIf { it > 0 }?.div(1000f) ?: duration
    val progress = if (length > 0f) (elapsed / length).coerceIn(0f, 1f) else 0f

    // The clip and whatever it says are one block, so the bubble grows around both. Animated
    // because the transcript arrives seconds after the press, into a bubble somebody is looking at.
    Column(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(32.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp).clickable(onClick = ::toggle),
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = onAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // The waveform takes what's left after the play button and the duration, so the row fits
            // whatever width the bubble allows instead of running past its edge.
            AudioWaveform(
                waveform = waveform,
                progress = progress,
                color = accent,
                mutedColor = muted,
                onScrub = ::scrubTo,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Counts down as it plays, the way every voice note does, and shows the whole length at rest.
            Text(
                formatDuration(if (playing) (length - elapsed).coerceAtLeast(0f) else length),
                color = muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Transcript(
            transcript = transcript,
            transcribing = transcribing,
            failed = transcriptFailed,
            needsModel = transcriptNeedsModel,
            offered = transcriptOffered,
            text = accent,
            muted = muted,
            onTranscribe = onTranscribe,
        )
    }
}

/**
 * What the clip says, under the clip — or the offer to find out.
 *
 * Deliberately quiet: the label is meta text in the bubble's muted colour, the size of a timestamp,
 * because a voice message is the message and this is a note about it. Once there is a transcript it
 * replaces the button entirely rather than sitting under one — the offer has been taken up, and a
 * button that stays around inviting you to do the same thing again is just clutter.
 *
 * Showing the words and offering the action are two separate questions, and conflating them is what
 * made a long press stutter: the lifted copy of a message is drawn without an action — a press picks
 * a message out of the conversation, it does not get to redraw it — so gating the whole section on
 * [onTranscribe] took the transcript away at the moment the bubble was being animated out of the
 * thread, and the bubble changed height mid-flight. The words belong to the message and are drawn
 * wherever it is; only the button needs somewhere to send the press.
 */
@Composable
private fun Transcript(
    transcript: String?,
    transcribing: Boolean,
    failed: Boolean,
    needsModel: Boolean,
    offered: Boolean,
    text: Color,
    muted: Color,
    onTranscribe: (() -> Unit)?,
) {
    // Whether the row exists is the surface's decision, not the callback's. Deciding it from
    // [onTranscribe] is what made a long press stutter: the lifted copy of a message is drawn
    // without an action, so the row vanished at the moment the bubble was being animated out of
    // the thread and the bubble changed height mid-flight.
    if (!offered) return

    if (transcript != null) {
        Spacer(modifier = Modifier.height(TranscriptGap))
        // An empty transcript is a real answer rather than a missing one, and saying so is what
        // stops it reading as a transcription that silently failed.
        if (transcript.isBlank()) {
            Text(
                "No speech detected",
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(transcript, color = text, style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Spacer(modifier = Modifier.height(TranscriptGap))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Only enabled while there is something to press: tapping again mid-run would be a second
        // request for the answer already being worked out.
        modifier = Modifier
            .clip(RoundedCornerShape(TranscriptCorner))
            // Inert where there is nowhere to send the press — a lifted bubble still reports that
            // the model is working, it just cannot be asked to start.
            .then(
                if (onTranscribe != null) Modifier.clickable(enabled = !transcribing, onClick = onTranscribe)
                else Modifier,
            )
            .padding(vertical = 2.dp),
    ) {
        if (transcribing) {
            CircularProgressIndicator(
                color = muted,
                strokeWidth = TranscriptStroke,
                modifier = Modifier.size(TranscriptIcon),
            )
        } else {
            Icon(
                if (needsModel) Icons.Filled.Download else Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(TranscriptIcon),
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            when {
                transcribing -> "Transcribing…"
                // Not an error: nothing has gone wrong, there is simply no model on the phone yet.
                needsModel -> "Set up transcription"
                failed -> "Couldn't transcribe · Try again"
                else -> "Transcribe"
            },
            color = muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The gap between the clip and what it says. */
private val TranscriptGap = 6.dp
private val TranscriptIcon = 13.dp
private val TranscriptStroke = 1.5.dp
private val TranscriptCorner = 6.dp

/**
 * Which clip currently has the floor.
 *
 * A process-wide single slot rather than state threaded through the thread: any two voice messages
 * that can be on screen together can be started together, and they are composed independently of
 * one another, so the only place the rule "one at a time" can live is outside all of them.
 */
private object VoicePlayback {
    private var current: String? = null
    private var stop: (() -> Unit)? = null

    /** Takes the floor for [uri], stopping whoever had it. */
    fun claim(uri: String, onStop: () -> Unit) {
        if (current != uri) stop?.invoke()
        current = uri
        stop = onStop
    }

    /** Gives it up, if this clip still holds it. */
    fun release(uri: String) {
        if (current != uri) return
        current = null
        stop = null
    }
}
