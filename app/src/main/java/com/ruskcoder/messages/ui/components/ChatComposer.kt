package com.ruskcoder.messages.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.BubbleStyle
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.text.TextRange
import com.ruskcoder.messages.data.model.Polls
import com.ruskcoder.messages.data.model.RichDraft
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.SendBarStyle
import com.ruskcoder.messages.data.model.sendBar
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.formatDuration

/**
 * The chat send bar: reply/edit banner, input pill with inline actions, and the send/mic button.
 * Ported from `chat-composer.tsx`.
 *
 * The RN source's Apple pill sits on a real `BlurView` that shows the message thread scrolling
 * underneath it, frosted. That is what [LocalChatBackdrop] provides here — the conversation records
 * itself and the pill replays the recording through a blur (see `ChatBackdrop.kt`). Where no
 * backdrop is available — below API 31, or on a screen that doesn't publish one — the pill falls
 * back to the flat two-layer translucency it used before. Voice-message recording isn't wired up on
 * the Android side yet — `onMicClick` is a no-op — so only the idle/typing states are implemented
 * here; the record/preview waveform states from the RN source aren't reachable and aren't ported.
 */

private val ComposerHeight = 46.dp
private val AppleComposerHeight = 40.dp
private val ComposerDark = Color(0xFF1F2C34)
private val AppleAccent = Color(0xFF3291EF)

private val ApplePlaceholder = Color(0xFF9CA3AF)

/**
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *  The Apple send bar's tint. This is the knob: change this one colour to recolour the bar.
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The bar is smoked glass, not coloured glass. It used to carry a wash of the conversation's own
 * accent, which on a blue chat made the pill read as a blue panel with the draft sitting on it —
 * and a light grey tint underneath left it looking lit from within rather than dark.
 *
 * A dark grey rather than the near-black the menus are filmed with. The bar is tinted *over* a
 * darkening base, so a near-black here compounded with that one and the pill came out reading as
 * flat black instead of as glass.
 */
val AppleComposerTint = Color(0xFF2C3036)

/** How much of [AppleComposerTint] the pill carries. The second knob, if it still reads too heavy. */
private val AppleBlurTint = AppleComposerTint.copy(alpha = 0.60f)

/** Over a real blur the tint only has to say "frosted glass", not stand in for the blur itself. */
private val AppleBlurTintOverBackdrop = AppleComposerTint.copy(alpha = 0.45f)

/** The darkening under the tint, which is what stops light wallpaper showing through as glare. */
private val AppleBlurBase = Color(0x22000000)

/** The Apple bar's send button: fully rounded ends but wider than tall, sitting inside the pill. */
private val AppleSendWidth = 34.dp
private val AppleSendHeight = 26.dp

/** Shared by the draft and its placeholder, so an empty pill is exactly as tall as a typed one. */
private val ComposerTextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)

/**
 * The draft and the bar's buttons follow the conversation's own zoom.
 *
 * Pinching the thread has always resized the bubbles; the bar underneath them stayed at 16sp
 * whatever the messages above it were set to, so a conversation zoomed up for legibility was typed
 * into at a size the same eyes could not read. The whole bar scales rather than the words alone —
 * a "+" left at its fixed size beside a grown pill reads as a control that failed to load.
 *
 * Sizes derive from the same [Settings.textScale] the bubbles use, so the draft is drawn at the
 * size the message will be once it is sent.
 */
private fun TextStyle.scaledBy(scale: Float): TextStyle =
    copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

/** The Material bar's inline "+" and camera buttons, before the conversation's zoom is applied. */
private val MaterialPlusSize = 38.dp

/** The glyph inside a "+" button. `IconButton`'s own 24dp default, made explicit so it can scale. */
private val PlusGlyph = 24.dp

/**
 * The composer's "+" and its "x" are the same glyph 45 degrees apart, so the button spins between
 * them instead of swapping icons: a hard cut on a control the thumb is already resting on reads as
 * a flicker, and the turn is what says the two states are one thing.
 */
@Composable
private fun PlusCloseIcon(
    closed: Boolean,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val angle by animateFloatAsState(
        targetValue = if (closed) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "plusRotation",
    )
    Icon(
        Icons.Filled.Add,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = angle },
    )
}


/** The arrow inside the Apple bar's send button, and the camera beside the Material draft. */
private val AppleSendGlyph = 18.dp
private val CameraGlyph = 22.dp

/** How far the bar fades back while the conversation is being edited somewhere above it. */
private const val DisabledAlpha = 0.4f

/** How far the Apple bar floats above the bottom edge, which the other styles sit flush against. */
private val AppleBottomGap = 8.dp

/**
 * Eats every touch aimed at the bar, on the initial pass, so nothing under it ever sees one — the
 * only way to make something that is still drawn genuinely untouchable.
 */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** The send button turns this while the microphone is live — `RECORDING_RED` in the RN source. */
private val RecordingRed = Color(0xFFE53935)

/** How long the bar takes to rearrange itself around a recording. */
private const val PILL_MS = 260

/** Material's emphasized-decelerate: the bar arrives at its new shape rather than easing out of it. */
private val PillEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * A button that belongs to the recording, arriving and leaving along the bar's own axis: it grows
 * its width from nothing, so whatever sits beside it gives up that width over the same frames.
 */
private val SatelliteEnter =
    expandHorizontally(tween(PILL_MS, easing = PillEasing), clip = false) +
        fadeIn(tween(PILL_MS)) +
        slideInHorizontally(tween(PILL_MS, easing = PillEasing)) { -it }
private val SatelliteExit =
    shrinkHorizontally(tween(PILL_MS, easing = PillEasing), clip = false) +
        fadeOut(tween(120)) +
        slideOutHorizontally(tween(PILL_MS, easing = PillEasing)) { -it }

@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    /**
     * Held rather than tapped, with something written: the same message, sent later.
     *
     * Only offered while there is text — a long press on the microphone means "record", and a
     * long press on a voice clip has nothing to schedule that the clip itself does not already
     * decide.
     */
    onScheduleSend: () -> Unit = {},
    /** Opens the attachment sheet. Null leaves the "+" off the bar entirely — see [ChatComposer]. */
    onAttachmentClick: (() -> Unit)? = null,
    /**
     * Tapped in the idle phase: the screen asks for the microphone and starts the recorder.
     *
     * Null on a bar that has no recorder behind it — the recipient picker, where there is no
     * conversation to record into yet. The primary button is then always Send, and it is disabled
     * rather than turned into a microphone when there is nothing written: a microphone that opens
     * nothing is a button that lies about what the bar can do.
     */
    onMicClick: (() -> Unit)? = null,
    /** Tapped in preview: the screen turns the clip into a message. */
    onSendVoice: () -> Unit = {},
    /** Drives the record/preview states; `null` leaves the bar text-only. */
    voice: VoiceRecorderState? = null,
    sendBarType: SendBarStyle,
    /**
     * What the draft is wearing, character by character.
     *
     * The plain string above is still the draft — this only says how it is dressed — so a bar
     * nobody has formatted anything in behaves exactly as it always did.
     */
    richDraft: RichDraft = RichDraft(),
    /** Raised as the caret moves, so the formatting panel knows what it would act on. */
    onSelectionChange: ((TextRange) -> Unit)? = null,
    /**
     * Holds the draft open for reading and formatting, but not for typing.
     *
     * What this is for is the formatting card, which takes the keyboard's own slot. The field keeps
     * its focus and its selection the whole time that card is up — the buttons on it act on that
     * selection, and a field that had let go of it would have nothing to format. But an editable
     * focused field also keeps an input session, and every press on the card rewrites the styling
     * the field is showing: each of those restarts the session, and a restarted session brings the
     * keyboard back up over the card that is still open. Read-only ends the session and leaves
     * everything else exactly where it was.
     */
    readOnly: Boolean = false,
    /**
     * Whether the draft has been lifted out of the bar and is being shown as a bubble elsewhere.
     *
     * The pill goes out of focus with the conversation — it is part of what you are no longer
     * working in, and the message it held is up on the screen being dressed. What stays sharp is
     * what you can still press: the "+" that closes the mode, and the send button.
     *
     * Nothing moves. The pill keeps its box and its place in the row, so the send button is exactly
     * where the thumb already on it expects it to be.
     */
    draftLifted: Boolean = false,
    replyPreview: ReplyPreview? = null,
    onCancelReply: () -> Unit = {},
    editing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    attachmentsOpen: Boolean = false,
    onCapture: (() -> Unit)? = null,
    /**
     * Pictures and clips waiting in the draft, oldest first — pasted, dropped, or chosen from the
     * gallery.
     *
     * Held by the screen rather than by the bar, for the same reason the draft's text is: they are
     * half of a message being composed, and the thing that sends them is the same button.
     */
    draftMedia: List<Uri> = emptyList(),
    /**
     * A picture arrived — from a paste, the keyboard, or a drop onto the field.
     *
     * Null on a bar with nowhere to put one, and that is not the same as an empty lambda: the field
     * only takes a picture out of the clipboard when somebody is going to do something with it, so
     * on those bars a paste behaves exactly as it always did instead of quietly eating the image.
     */
    onPasteImages: ((List<Uri>) -> Unit)? = null,
    /**
     * How many things the screen is holding that are not pictures — a shared PDF, say.
     *
     * The bar draws thumbnails for [draftMedia] and nothing at all for these, but it still has to
     * know they exist: the send button is lit by whether there is *anything* to send, and a share
     * consisting of one document and no words left it dark with a perfectly good file waiting
     * behind it.
     */
    draftAttachments: Int = 0,
    /**
     * Documents waiting in the draft, oldest first — picked from the "+" card's file browser.
     *
     * In the same strip as the pictures and sent by the same button. A file has no picture of
     * itself to show, so each is a tile carrying its name instead of a thumbnail.
     */
    draftFiles: List<Attachment.File> = emptyList(),
    /** A file tile's cross was pressed. */
    onRemoveDraftFile: (Attachment.File) -> Unit = {},
    /**
     * A game the extension has composed and is waiting in the draft, if there is one.
     *
     * It rides in the pill for the same reason a pasted picture does: it is a message being
     * written, and the thing that sends it is the bar's own send button. A full-width card rather
     * than a thumbnail in the strip — a game is one attachment, never six, and its artwork is a
     * board worth actually looking at before it goes.
     */
    draftGame: DraftGame? = null,
    /** The game card's cross was pressed. */
    onRemoveDraftGame: () -> Unit = {},
    /**
     * The choices of a poll being built in the draft, in order, or null when there is no poll.
     *
     * Held by the screen for the same reason the draft's text and its pictures are: this is one
     * message being composed, and the thing that sends it is the bar's own send button. The words
     * typed alongside it become the poll's question — see [encodePoll].
     */
    pollDraft: List<String>? = null,
    /**
     * A choice was typed into. The whole list comes back, already grown or collapsed by the rule
     * the card enforces: a filled-in last choice earns an empty one after it.
     */
    onPollChange: (List<String>) -> Unit = {},
    /** The cross in the poll card's corner. */
    onRemovePoll: () -> Unit = {},
    /** Its cross was pressed. */
    onRemoveDraftMedia: (Uri) -> Unit = {},
    /** The thumbnail itself was pressed: open it full screen, which is all a thumbnail can show. */
    onOpenDraftMedia: (Uri) -> Unit = {},
    /** Lets the screen put the cursor in the draft — a focused reply opens the keyboard with it. */
    focusRequester: FocusRequester? = null,
    /**
     * The draft was swiped down, after the keyboard has been sent away — see [keyboardSwipe].
     *
     * For a screen with something of its own standing in the keyboard's slot, the "+" card being
     * the one that exists: the bar knows nothing about it, but a flick down the draft means "give
     * me the conversation back", and leaving the card up would only half answer that.
     */
    onSwipeDismiss: (() -> Unit)? = null,
    /**
     * False while a message up in the thread is being edited. The bar stays where it is — it is
     * part of the shape of the screen — but it is not what you are typing into any more, so it
     * dims and stops answering touches.
     */
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    // Somewhere for the swipe to put the caret on a bar whose screen never asked for a handle of
    // its own — the recipient picker. Where the screen did hand one down, that is the one used, or
    // a swipe up would focus a field the screen thinks it is still holding.
    val ownFocusRequester = remember { FocusRequester() }
    val draftFocus = focusRequester ?: ownFocusRequester
    /**
     * Whether the draft has grown into its own scroll — its height cap, reported by the field.
     *
     * Past that point a vertical drag on the pill is the words moving under the finger, so the
     * keyboard swipe stands down rather than fighting the field for the same gesture.
     */
    var draftScrolls by remember { mutableStateOf(false) }
    // The bar rides the conversation's pinch zoom along with the bubbles — see [scaledBy]. The
    // buttons scale with the words so the bar keeps its proportions at every size.
    val zoom = settings.textScale()
    // The pill's own geometry goes with them. Growing the draft alone would have worked — the pill
    // wraps its contents — but shrinking it would not: the pill would hold its 40dp floor while the
    // buttons beside it got smaller, and the bar would come apart at anything under 1x.
    val appleHeight = AppleComposerHeight * zoom
    val appleRadius = appleHeight / 2
    val appleSendWidth = AppleSendWidth * zoom
    val appleSendHeight = AppleSendHeight * zoom
    val appleSendInset = (appleHeight - appleSendHeight) / 2
    val composerHeight = ComposerHeight * zoom
    val plusSize = appleHeight
    val materialPlusSize = MaterialPlusSize * zoom
    val isDark = settings.mode == ThemeMode.DARK
    val isApple = sendBarType == SendBarStyle.APPLE
    // The bar takes its accent from the bubbles it sends: the send button is the colour your
    // messages come out, and the pill and the "+" beside it carry a trace of the same. Read off the
    // Apple palette rather than a fixed blue, so recolouring a conversation recolours its send bar.
    val accent = useAppleBubbleColors(mine = true).background
    // The Apple bar never reads this — its own controls are drawn from the blur surface below —
    // so under that style this is only what the "+" card asks for.
    val composerSurface = composerSurfaceFor(settings)
    // Rebuilt only when the formatting actually changes, not on every keystroke: an unformatted
    // draft produces null and the field is left exactly as it was.
    val effectInk = useAppleBubbleColors(mine = true).background
    val styling = remember(richDraft, effectInk) { richOutputTransformation(richDraft, effectInk) }
    val phase = voice?.phase ?: RecorderPhase.IDLE
    val recording = phase == RecorderPhase.RECORDING
    val previewing = phase == RecorderPhase.PREVIEW
    // A recorded-and-not-yet-sent clip acts like typed text: the button reads as "ready to send".
    val isTyping = text.isNotBlank() || previewing
    val paused = voice?.isPaused == true
    // The big button ends things — stop while recording, send once there is a clip. Holding the
    // recording is the small button inside the bar's trailing edge.
    // A draft with a picture in it and no words is still a message to send, so the button is a
    // send button whenever either half of the draft has anything in it.
    // A poll counts only once two of its choices have something in them — one choice is not a poll,
    // and a send button lit by an empty card would send a question with nothing to answer.
    val pollReady = (pollDraft?.count { it.isNotBlank() } ?: 0) >= 2
    // With a poll in the draft the bar is *about* the poll, and the words are its question rather
    // than a message of their own. Gated on the words alone, typing a question and stopping at one
    // choice lit the send button — and pressing it sent the question as an ordinary message and
    // dropped the poll on the floor without saying so.
    val hasDraft = if (pollDraft != null) {
        pollReady
    } else {
        text.isNotBlank() || draftMedia.isNotEmpty() || draftFiles.isNotEmpty() || draftAttachments > 0 ||
            draftGame != null
    }
    val onPrimary: () -> Unit = when {
        recording && voice != null -> ({ voice.stop() })
        previewing -> onSendVoice
        hasDraft -> onSend
        else -> onMicClick ?: ({})
    }
    // A bar with no recorder has exactly one thing its button can do, and nothing to do it to until
    // something is written.
    val primaryEnabled = enabled && (onMicClick != null || hasDraft || previewing || recording)
    // Only the send-a-typed-message face of this button has a long press behind it.
    // Only a plain text draft can be scheduled; there is nowhere to put a queued picture.
    val onPrimaryLongPress: (() -> Unit)? =
        if (!recording && !previewing && text.isNotBlank() && draftMedia.isEmpty() && draftFiles.isEmpty() &&
            draftGame == null && pollDraft == null
        ) {
            onScheduleSend
        } else {
            null
        }
    val primaryLabel = when {
        recording -> "Stop recording"
        previewing -> "Send voice message"
        hasDraft -> "Send message"
        onMicClick == null -> "Send message"
        else -> "Record voice message"
    }
    val primaryIcon = when {
        recording -> Icons.Filled.Stop
        previewing -> Icons.AutoMirrored.Filled.Send
        hasDraft -> Icons.AutoMirrored.Filled.Send
        onMicClick == null -> Icons.AutoMirrored.Filled.Send
        else -> Icons.Filled.Mic
    }

    /**
     * Up for the keyboard, down to put it away — carried by the pill under both styles.
     *
     * Off while the bar is not yours to type in: disabled, holding a clip, lifted into the effects
     * overlay, or scrolling a draft too long to fit. Read-only keeps the downward half — that state
     * is the formatting card standing in the keyboard's slot, and a flick down should close it like
     * anything else down there — but not the upward one, which would ask a field that cannot take
     * input for a keyboard it would then have to send away again.
     */
    val keyboardSwipe = Modifier.keyboardSwipe(
        enabled = enabled && !draftLifted && !draftScrolls && phase == RecorderPhase.IDLE,
        onOpen = {
            if (!readOnly) {
                if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                // A bar whose field has gone by the time the gesture lands — a send that emptied
                // the pill into a voice row — has nothing to focus, and that is not a crash.
                runCatching { draftFocus.requestFocus() }.onSuccess { keyboardController?.show() }
            }
        },
        onClose = {
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            // The caret stays where it was, as it does behind the "+": what this puts away is the
            // keyboard, not the message being written.
            keyboardController?.hide()
            onSwipeDismiss?.invoke()
        },
    )

    // `composerWrap` in the RN source: 8pt either side and 4pt of headroom. The bottom gap belongs
    // to the screen, which sizes it from the keyboard/navigation-bar slot the bar rides on.
    CompositionLocalProvider(LocalComposerContentAlpha provides if (enabled) 1f else DisabledAlpha) {
    Column(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .padding(top = 4.dp)
            // The Apple bar floats clear of the bottom edge rather than sitting on it; the other
            // styles are flush, and the screen owns whatever slot the bar is riding on.
            .padding(bottom = if (isApple) AppleBottomGap else 0.dp)
            // Dimmed and deaf as one: gating each button and the field separately would leave the
            // draft still focusable and the bar still looking live.
            //
            // The dim is applied to the *content* in the Apple bar, not to the whole subtree: the
            // glass is a layer of its own behind the controls, and fading the lot took the blur
            // down with it — the bar stopped being frosted and became a translucent grey slab.
            .then(if (isApple || enabled) Modifier else Modifier.graphicsLayer { alpha = DisabledAlpha })
            .then(if (enabled) Modifier else Modifier.swallowTouches()),
    ) {
        AnimatedVisibility(visible = editing, enter = fadeIn(), exit = fadeOut()) {
            ComposerCard(background = composerSurface) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Editing message", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("Send to save your changes", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onCancelEdit) { Icon(Icons.Filled.Close, contentDescription = "Cancel edit", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // Held past the moment the reply is cancelled so the band has something to draw on its way
        // out — cleared on the same frame, it emptied and then shrank, which reads as two events.
        val shownReply = remember { mutableStateOf(replyPreview) }
        LaunchedEffect(replyPreview) { if (replyPreview != null) shownReply.value = replyPreview }
        // No `spacedBy` here: a hidden `AnimatedVisibility` is still a zero-width child, and the
        // arrangement would keep a gap for it. Each control carries its own margin instead.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // While a clip is in hand, the "+" is the way out of it — as in the RN bar — so the
            // button stands whenever *either* job is live. On a bar with no attachments to offer and
            // no recorder, there is nothing behind it and it is left off; a disabled "+" would say
            // the screen has an attachment sheet it is refusing to open.
            val cancelsRecording = voice != null && phase != RecorderPhase.IDLE
            val showPlus = onAttachmentClick != null || cancelsRecording
            // The style decides which bar this is; the "+" only decides whether the bar has one.
            // Tested together, a screen with nothing to attach and no recorder — the recipient
            // picker — fell through to the Material bar while every other screen wore Apple's,
            // which read as the setting not applying to that page at all.
            if (isApple) {
                if (showPlus) {
                AppleBlurSurface(
                    shape = CircleShape,
                    modifier = Modifier.padding(end = 8.dp).size(plusSize),
                ) {
                    IconButton(
                        onClick = if (cancelsRecording) ({ voice.cancel() }) else (onAttachmentClick ?: {}),
                        // `fillMaxSize`, not `matchParentSize`: the surface hands its own size down
                        // as a minimum, and a child measuring itself against a parent that is
                        // measuring itself against that child resolves to nothing at all.
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlusCloseIcon(
                            closed = attachmentsOpen || phase != RecorderPhase.IDLE,
                            contentDescription = when {
                                phase != RecorderPhase.IDLE -> "Discard recording"
                                attachmentsOpen -> "Close attachment menu"
                                else -> "More options"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(PlusGlyph * zoom),
                        )
                    }
                }
                }

                AppleBlurSurface(
                    shape = RoundedCornerShape(appleRadius),
                    // The cap has to make room for a strip of thumbnails on top of the draft, or
                    // the pill clips the pictures it was grown to hold. Only when there are any:
                    // an empty draft's ceiling is what it always was.
                    modifier = Modifier
                        .weight(1f)
                        .liftedOutOfFocus(draftLifted)
                        .then(keyboardSwipe)
                        .heightIn(
                            min = appleHeight,
                            // Room for a strip of thumbnails on top of the draft, and for a quote
                            // above that — both live inside the pill, and a ceiling set for the
                            // draft alone clipped whichever of them was there.
                            max = 160.dp * zoom +
                                (if (draftMedia.isEmpty() && draftFiles.isEmpty()) 0.dp else PastedThumb + 24.dp) +
                                (if (draftGame == null) 0.dp else DraftGameRoom) +
                                (if (pollDraft == null) 0.dp else PollDraftRoom) +
                                (if (replyPreview == null) 0.dp else AppleQuoteRoom),
                        ),
                ) {
                    // The pill grows and shrinks over a quarter of a second rather than between two
                    // frames. It is a floating bar with a keyboard under it, and everything it does
                    // — a quote arriving, a picture pasted, a draft wrapping onto a second line —
                    // moves the send button and the "+" beside it. Cut to, that read as the bar
                    // jumping; eased, it reads as the bar making room.
                    //
                    // Anchored to the bottom. The contents are laid out at the size the pill is
                    // heading for while the pill itself is still on its way there, and the default
                    // pins them to its top edge — so the draft's own row, the last thing in the
                    // column, hung off the bottom of a growing pill and the send button rode down
                    // out of it and back. From the bottom, the draft and the button never move and
                    // it is the top of the pill that opens over them.
                    Column(
                        modifier = Modifier.animateContentSize(
                            animationSpec = tween(PILL_MS, easing = PillEasing),
                            alignment = Alignment.BottomStart,
                        ),
                    ) {
                        DraftGameCard(
                            game = draftGame,
                            onRemove = onRemoveDraftGame,
                            pillRadius = appleRadius,
                            textColor = Color.White,
                            subtitleColor = Color.White.copy(alpha = 0.6f),
                            dividerColor = Color.White.copy(alpha = 0.18f),
                            tileColor = Color.White.copy(alpha = 0.12f),
                        )
                        DraftMediaStrip(
                            media = draftMedia,
                            files = draftFiles,
                            onRemove = onRemoveDraftMedia,
                            onRemoveFile = onRemoveDraftFile,
                            onOpen = onOpenDraftMedia,
                            pillRadius = appleRadius,
                            dividerColor = Color.White.copy(alpha = 0.18f),
                            textColor = Color.White,
                            tileColor = Color.White.copy(alpha = 0.12f),
                        )
                        // Under the pictures, over the words: the choices are what the message is,
                        // and the draft below them is the question being asked about them.
                        PollDraftCard(
                            options = pollDraft,
                            onChange = onPollChange,
                            onRemove = onRemovePoll,
                            pillRadius = appleRadius,
                            textColor = Color.White,
                            hintColor = Color.White.copy(alpha = 0.4f),
                            subtitleColor = Color.White.copy(alpha = 0.6f),
                            dividerColor = Color.White.copy(alpha = 0.18f),
                            fillColor = Color.White.copy(alpha = 0.07f),
                        )
                        // Under the pictures and their rule, over the words. The order is the
                        // order the message will be read in: what you are attaching, then what you
                        // are answering, then what you are saying. No rule under this one — the
                        // quote is already a drawn shape and a line beneath it would be a second
                        // boundary around one thing.
                        AnimatedVisibility(
                            visible = replyPreview != null && !editing,
                            enter = expandVertically(tween(PASTED_MS)) + fadeIn(tween(PASTED_MS)),
                            exit = shrinkVertically(tween(PASTED_MS)) + fadeOut(tween(PASTED_MS)),
                        ) {
                            shownReply.value?.let { AppleReplyPerch(preview = it, onCancel = onCancelReply) }
                        }
                        // The row claims the pill's full minimum height, so a one-line draft and its
                        // send button sit centered in the pill instead of hugging its top edge —
                        // which is what left them riding above the "+" button beside them.
                        Row(modifier = Modifier.heightIn(min = appleHeight)) {
                            PillContent(
                                voice = voice,
                                phase = phase,
                                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                            ) { livePhase ->
                                if (voice != null && livePhase != RecorderPhase.IDLE) {
                                    // The draft's slot becomes the clip's: stop, live bars and a
                                    // running clock while recording; play and a scrubbed waveform
                                    // once there is something to hear.
                                    VoiceRow(
                                        voice = voice,
                                        recording = livePhase == RecorderPhase.RECORDING,
                                        color = Color.White,
                                        mutedColor = Color.White.copy(alpha = 0.35f),
                                        controlSize = 32.dp,
                                        controlBackground = Color.Transparent,
                                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                    )
                                } else {
                                    ComposerTextField(
                                        value = text,
                                        onValueChange = onTextChange,
                                        placeholder = if (pollDraft == null) "Message" else "Ask a question",
                                        textColor = Color.White,
                                        placeholderColor = ApplePlaceholder,
                                        cursorColor = Color.White,
                                        enterToSend = settings.enterToSend,
                                        onSend = onSend,
                                        onReceiveImages = onPasteImages,
                                        focusRequester = draftFocus,
                                        onScrollableChange = { draftScrolls = it },
                                        outputTransformation = styling,
                                        onSelectionChange = onSelectionChange,
                                        readOnly = readOnly,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 2.dp)
                                            .padding(vertical = 7.dp),
                                    )
                                }
                            }
                            // A plain clickable box rather than an `IconButton`: the button is only
                            // 34x26 and `IconButton`'s 48dp minimum touch target would inflate the
                            // pill around it. Bottom-aligned, with the gap chosen so it lands dead
                            // center at rest and stays by the send edge as the draft grows.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Bottom)
                                    .padding(end = appleSendInset, bottom = appleSendInset)
                                    .size(width = appleSendWidth, height = appleSendHeight)
                                    .clip(RoundedCornerShape(appleSendHeight / 2))
                                    .background(if (recording) RecordingRed else accent)
                                    // Faded rather than hidden while there is nothing to send: the
                                    // pill is built around the button, and taking it away resizes
                                    // the field on the first character typed.
                                    .graphicsLayer { alpha = if (primaryEnabled) 1f else DisabledAlpha }
                                    .combinedClickable(
                                        enabled = primaryEnabled,
                                        onClick = onPrimary,
                                        onClickLabel = primaryLabel,
                                        onLongClick = onPrimaryLongPress,
                                        onLongClickLabel = "Send later",
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Crossfade(targetState = primaryIcon, animationSpec = tween(140), label = "applePrimaryIcon") { icon ->
                                    Icon(
                                        // The bar's own send glyph, which is an arrow rather than
                                        // the Material paper plane.
                                        if (icon == Icons.AutoMirrored.Filled.Send) Icons.Filled.ArrowUpward else icon,
                                        contentDescription = primaryLabel,
                                        tint = Color.White,
                                        modifier = Modifier.size(AppleSendGlyph * zoom),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Discarding a clip is its own button out here rather than a change of face on the
                // "+", which the Material bar keeps inside its pill. It grows in from nothing, so
                // the pill beside it gives up its width over the same frames instead of jumping.
                AnimatedVisibility(
                    visible = voice != null && phase != RecorderPhase.IDLE,
                    enter = SatelliteEnter,
                    exit = SatelliteExit,
                ) {
                    IconButton(
                        onClick = { voice?.cancel() },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(composerHeight)
                            .composerGlass(CircleShape, composerSurface),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard recording", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                // A column rather than a row, so a pasted picture can sit above the words instead
                // of beside them. With nothing pasted the strip has no height and this is the row it
                // always was. The max height is off the pill entirely: a picture and three lines of
                // text is taller than a draft ever used to get, and capping the two together clipped
                // the picture rather than scrolling the words.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .liftedOutOfFocus(draftLifted)
                        .then(keyboardSwipe)
                        .composerGlass(RoundedCornerShape(composerHeight / 2), composerSurface)
                        .padding(horizontal = 4.dp)
                        // The pill's own height follows its contents rather than cutting to them,
                        // from the bottom — see the Apple pill for why.
                        .animateContentSize(
                            animationSpec = tween(PILL_MS, easing = PillEasing),
                            alignment = Alignment.BottomStart,
                        ),
                ) {
                  DraftGameCard(
                      game = draftGame,
                      onRemove = onRemoveDraftGame,
                      pillRadius = composerHeight / 2,
                      textColor = MaterialTheme.colorScheme.onSurface,
                      subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                      tileColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                  )
                  DraftMediaStrip(
                      media = draftMedia,
                      files = draftFiles,
                      onRemove = onRemoveDraftMedia,
                      onRemoveFile = onRemoveDraftFile,
                      onOpen = onOpenDraftMedia,
                      pillRadius = composerHeight / 2,
                      dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                      textColor = MaterialTheme.colorScheme.onSurface,
                      tileColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                  )
                  PollDraftCard(
                      options = pollDraft,
                      onChange = onPollChange,
                      onRemove = onRemovePoll,
                      pillRadius = composerHeight / 2,
                      textColor = MaterialTheme.colorScheme.onSurface,
                      hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                      subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                      fillColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                  )
                  // Inside the pill, over the words — the same place the Apple bar keeps its quote,
                  // and for the same reason: what you are answering is part of the message being
                  // written. It used to be a full-width lid stuck on top of the bar, which read as
                  // a second control that happened to be touching the first.
                  AnimatedVisibility(
                      visible = replyPreview != null && !editing,
                      enter = expandVertically(tween(PASTED_MS)) + fadeIn(tween(PASTED_MS)),
                      exit = shrinkVertically(tween(PASTED_MS)) + fadeOut(tween(PASTED_MS)),
                  ) {
                      shownReply.value?.let {
                          MaterialReplyPerch(
                              preview = it,
                              bubbleType = settings.bubbleType,
                              background = composerSurface,
                              onCancel = onCancelReply,
                          )
                      }
                  }
                  Row(
                      modifier = Modifier.fillMaxWidth().heightIn(min = composerHeight),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                      PillContent(voice = voice, phase = phase, modifier = Modifier.weight(1f)) { livePhase ->
                          if (voice != null && livePhase != RecorderPhase.IDLE) {
                              VoiceRow(
                                  voice = voice,
                                  recording = livePhase == RecorderPhase.RECORDING,
                                  color = MaterialTheme.colorScheme.secondary,
                                  mutedColor = MaterialTheme.colorScheme.outlineVariant,
                                  timeColor = MaterialTheme.colorScheme.onSurface,
                                  controlSize = 36.dp,
                                  modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
                              )
                          } else {
                              // Bottom-aligned, each button centred in a box one line tall: on a
                              // one-line draft that is the middle of the pill, and as the draft
                              // wraps they stay on its last line instead of drifting up to the
                              // middle of it.
                              Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                                  if (onAttachmentClick != null) {
                                      Box(modifier = Modifier.height(composerHeight), contentAlignment = Alignment.Center) {
                                      IconButton(onClick = onAttachmentClick, modifier = Modifier.size(materialPlusSize)) {
                                          PlusCloseIcon(
                                              closed = attachmentsOpen,
                                              contentDescription = if (attachmentsOpen) "Close attachment menu" else "More options",
                                              // Reads off the pill it sits on: white would disappear
                                              // into the light theme's white composer.
                                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                              modifier = Modifier.size(PlusGlyph * zoom),
                                          )
                                      }
                                      }
                                  }
                                  ComposerTextField(
                                      value = text,
                                      onValueChange = onTextChange,
                                      placeholder = if (pollDraft == null) "Message" else "Ask a question",
                                      textColor = MaterialTheme.colorScheme.onSurface,
                                      placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                      cursorColor = MaterialTheme.colorScheme.onSurface,
                                      enterToSend = settings.enterToSend,
                                      onSend = onSend,
                                      onReceiveImages = onPasteImages,
                                      focusRequester = draftFocus,
                                      onScrollableChange = { draftScrolls = it },
                                      outputTransformation = styling,
                                      onSelectionChange = onSelectionChange,
                                      readOnly = readOnly,
                                      // 12 above and below a 22sp line is the pill's own 46dp, so
                                      // a one-line draft sits where a centred one would.
                                      modifier = Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 2.dp),
                                  )
                                  if (onCapture != null) {
                                      Box(modifier = Modifier.height(composerHeight), contentAlignment = Alignment.Center) {
                                          IconButton(onClick = onCapture, modifier = Modifier.size(materialPlusSize)) {
                                              Icon(Icons.Outlined.CameraAlt, contentDescription = "Take a photo", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(CameraGlyph * zoom))
                                          }
                                      }
                                  }
                              }
                          }
                      }
                  }
                }

                // A clickable box rather than an `IconButton`: the button needs a long press on
                // it, and `IconButton` takes a click and nothing else.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(composerHeight)
                        // Solid, alone in the bar. Everything else here is glass because it is a
                        // surface the conversation shows through; this is the one control that has
                        // to read as a button at a glance, and letting the wallpaper into it turned
                        // the accent into a pale patch that changed colour as the thread scrolled
                        // under it.
                        .clip(CircleShape)
                        .background(if (recording) RecordingRed else MaterialTheme.colorScheme.secondary)
                        .graphicsLayer { alpha = if (primaryEnabled) 1f else DisabledAlpha }
                        .combinedClickable(
                            enabled = primaryEnabled,
                            onClick = onPrimary,
                            onClickLabel = primaryLabel,
                            onLongClick = onPrimaryLongPress,
                            onLongClickLabel = "Send later",
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Crossfaded so the button changes job without blinking.
                    Crossfade(targetState = primaryIcon, animationSpec = tween(140), label = "primaryIcon") { icon ->
                        Icon(
                            icon,
                            contentDescription = primaryLabel,
                            tint = if (recording) Color.White else MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size((if (isTyping && !recording) 22.dp else 24.dp) * zoom),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * The send bar's two end controls, drawn on their own, in the bar's own geometry.
 *
 * For a surface that stands in front of the bar rather than beside it — the effects mode, where the
 * whole screen including the bar goes out of focus and the only things left sharp are the two
 * buttons that end the mode. There is no way to punch a hole in a blur around two controls in the
 * middle of a row, so they are re-drawn on top of it.
 *
 * *Re-drawn*, not re-invented. Every number here is the one the bar itself lays out with, read from
 * the same constants and scaled by the same zoom, and the styles diverge in exactly the places the
 * bar diverges:
 *
 * - **Apple.** A frosted circle where the "+" is, then a stand-in for the pill — empty, but the
 *   right width and height — with the send button in its bottom-right corner at the same inset. The
 *   send button is a wide rounded rectangle in the conversation's accent with an up arrow in it,
 *   because that is what this bar's send button is.
 * - **Material.** The pill's box with the "+" at its leading edge as a bare glyph, and the circular
 *   accent send button after it carrying the paper plane.
 *
 * The empty box in the middle is the point: it is what puts each button where its counterpart
 * underneath already is, so nothing appears to move as the mode opens and the thumb that was on the
 * send button has not been asked to go looking for it.
 */
@Composable
fun ComposerActionRow(
    onClose: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    closeLabel: String = "Close",
    sendLabel: String = "Send message",
) {
    val settings = LocalSettings.current
    val zoom = settings.textScale()
    val isApple = settings.bubbleType.sendBar() == SendBarStyle.APPLE
    val accent = useAppleBubbleColors(mine = true).background
    val appleHeight = AppleComposerHeight * zoom
    val appleSendHeight = AppleSendHeight * zoom
    val appleSendWidth = AppleSendWidth * zoom
    val appleSendInset = (appleHeight - appleSendHeight) / 2
    val composerHeight = ComposerHeight * zoom
    val materialPlusSize = MaterialPlusSize * zoom

    Row(
        // The bar's own outer insets, so this row occupies exactly the strip the bar does.
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(top = 4.dp)
            .padding(bottom = if (isApple) AppleBottomGap else 0.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isApple) {
            AppleBlurSurface(shape = CircleShape, modifier = Modifier.padding(end = 8.dp).size(appleHeight)) {
                IconButton(onClick = onClose, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = closeLabel,
                        tint = Color.White,
                        modifier = Modifier.size(PlusGlyph * zoom),
                    )
                }
            }
            // Nothing is drawn in here. It stands in for the pill so the send button lands in the
            // pill's bottom-right corner, which is the only place this bar's send button ever is.
            Box(modifier = Modifier.weight(1f).height(appleHeight)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = appleSendInset, bottom = appleSendInset)
                        .size(width = appleSendWidth, height = appleSendHeight)
                        .clip(RoundedCornerShape(appleSendHeight / 2))
                        .background(accent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = sendLabel,
                            onClick = onSend,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // An arrow, not the Material paper plane — see the bar's own send button.
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = sendLabel,
                        tint = Color.White,
                        modifier = Modifier.size(AppleSendGlyph * zoom),
                    )
                }
            }
        } else {
            // The pill's box, with its leading control where the pill keeps it. Bare, as it is in
            // the bar: on this style the glass belongs to the pill and not to the button on it.
            Box(modifier = Modifier.weight(1f).height(composerHeight), contentAlignment = Alignment.CenterStart) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(start = MaterialPillInset).size(materialPlusSize),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = closeLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(PlusGlyph * zoom),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(composerHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = sendLabel,
                        onClick = onSend,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = sendLabel,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(MaterialSendGlyph * zoom),
                )
            }
        }
    }
}

/** The padding the Material pill keeps inside itself, which is what insets its "+". */
private val MaterialPillInset = 4.dp

/** The bar's send glyph at rest — the size it is drawn at with something to send. */
private val MaterialSendGlyph = 22.dp

/**
 * What the draft's slot turns into while a voice message is being recorded or auditioned: bars,
 * a clock, and — once there is a clip — the play control that scrubs it. Ported from the
 * `recordingRow` blocks of `chat-composer.tsx`, which draw the same thing in both bar styles.
 */
@Composable
private fun VoiceRow(
    voice: VoiceRecorderState,
    recording: Boolean,
    color: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
    timeColor: Color = color,
    /** Diameter of the trailing control — a real button, not a glyph you have to aim at. */
    controlSize: Dp = 36.dp,
    /**
     * Both bars sit the control on nothing: the pill around it is surface enough, and the tinted
     * disc the Material one used to carry read as a second button inside the first.
     */
    controlBackground: Color = Color.Transparent,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        AudioWaveform(
            // While recording the bars are all "live", so nothing is drawn as un-played.
            waveform = if (recording) voice.liveWaveform else voice.waveform,
            progress = if (recording) 1f else voice.playbackProgress,
            color = color,
            mutedColor = mutedColor,
            // The trailing control is a button with its glyph inset inside it, so the row already
            // breathes on that end; the bars start flush and read as pushed up against the pill.
            // Matching the button's own inset on the leading edge, plus a little more because the
            // bars are much lighter than the glyph and read as closer to the edge than they are.
            modifier = Modifier.weight(1f).padding(start = ((controlSize - 24.dp) / 2).coerceAtLeast(0.dp) + 4.dp),
        )
        Text(
            formatDuration(if (recording || !voice.isPlaying) voice.recordingSeconds else voice.playbackSeconds),
            color = timeColor,
            fontSize = 12.sp,
        )
        // Inside the bar's trailing edge: hold the recording while it runs, audition the clip once
        // it has stopped. Ending the recording is the big button outside.
        IconButton(
            onClick = {
                when {
                    recording && voice.isPaused -> voice.resume()
                    recording -> voice.pause()
                    else -> voice.togglePlayback()
                }
            },
            modifier = Modifier
                .size(controlSize)
                .clip(CircleShape)
                .background(controlBackground),
        ) {
            Icon(
                when {
                    recording && voice.isPaused -> Icons.Filled.Mic
                    recording -> Icons.Filled.Pause
                    voice.isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = when {
                    recording && voice.isPaused -> "Resume recording"
                    recording -> "Pause recording"
                    voice.isPlaying -> "Pause playback"
                    else -> "Play recording"
                },
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The pill's contents, swapped with a cross-slide rather than cut: the draft leaves toward the
 * leading edge as the clip's controls arrive from it, which is the same direction the discard
 * button is growing in from.
 */
@Composable
private fun PillContent(
    voice: VoiceRecorderState?,
    phase: RecorderPhase,
    modifier: Modifier = Modifier,
    content: @Composable (RecorderPhase) -> Unit,
) {
    // Recording and preview share one layout, so only idle-vs-clip is a change of content.
    val target = if (voice == null || phase == RecorderPhase.IDLE) RecorderPhase.IDLE else phase
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            val entering = fadeIn(tween(PILL_MS, delayMillis = 60)) +
                slideInHorizontally(tween(PILL_MS, easing = PillEasing)) { it / 6 }
            val leaving = fadeOut(tween(120)) + slideOutHorizontally(tween(PILL_MS, easing = PillEasing)) { -it / 6 }
            (entering togetherWith leaving).using(SizeTransform(clip = false))
        },
        contentKey = { it == RecorderPhase.IDLE },
        modifier = modifier,
        label = "pillContent",
    ) { livePhase ->
        content(livePhase)
    }
}


/**
 * The quote a reply is being written against, inside the Material pill.
 *
 * It used to be a full-width lid stuck on top of the whole bar — the "+" and the send button
 * included — which read as a second control that happened to be touching the first, and said the
 * quote in a language nothing else in the bar spoke. Inside the pill it is what it is in the Apple
 * bar and what a pasted picture already is here: part of the message being written, held by the
 * thing that holds the message being written.
 *
 * Each style still says the quote its own way, exactly as it says it inside a bubble: WhatsApp's
 * accent-barred card, sized to its own contents rather than stretched across the bar, and Google's
 * inset panel with the control in its corner.
 */
@Composable
private fun MaterialReplyPerch(
    preview: ReplyPreview,
    bubbleType: BubbleStyle,
    background: Color,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (bubbleType) {
            // Google's own control is in the card's corner, so nothing is added around it.
            BubbleStyle.GOOGLE -> Box(modifier = Modifier.weight(1f)) {
                GoogleComposerQuote(
                    name = preview.name,
                    text = preview.text,
                    onCancel = onCancel,
                )
            }
            // WhatsApp's card sizes to its contents and never fills the bubble it sits in, which
            // is exactly as true here — so it takes the width it needs and no more, and the
            // control takes the end of the row.
            else -> {
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    ReplyQuote(
                        name = preview.name,
                        text = preview.text,
                        mutedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        bubbleColor = background,
                    )
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 10.dp, end = 4.dp)
                        .size(20.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCancel,
                        ),
                )
            }
        }
    }
}


/**
 * The quote a reply is being written against, inside the Apple pill.
 *
 * iMessage answers "what am I replying to?" with the message itself, redrawn hollow — that is what
 * the conversation does above every reply in it, and there is no reason for the send bar to answer
 * the same question in a different language. It used to borrow WhatsApp's accent-barred card,
 * which was the one place in the Apple bar that looked like a different app.
 *
 * *Inside* the pill, not floating over it. A quote is part of the message being written, exactly as
 * a pasted picture is, and the pill is what holds the message being written — parked above it the
 * quote read as a separate control that happened to be nearby, and left a strip of conversation
 * showing between two halves of one thing.
 *
 * The bubble keeps the side of the row the quoted message is on: theirs to the left, yours to the
 * right. The control sits on the *inboard* side of it, toward the middle of the pill, which is the
 * half with room for it — outboard it collided with the edge the bubble is already flush against.
 */
@Composable
private fun AppleReplyPerch(preview: ReplyPreview, onCancel: () -> Unit) {
    val mine = preview.mine == true
    // A ripple and nothing else: a filled disc would be a second shape beside a bubble that is
    // already an outline, and the only thing this needs to say when pressed is that it was pressed.
    val cancel: @Composable () -> Unit = {
        IconButton(onClick = onCancel, modifier = Modifier.size(CancelTouchTarget)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel reply",
                tint = ApplePlaceholder,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mine) cancel()
        AppleQuotePreview(text = preview.text, quotedMine = mine, onGlass = true)
        if (!mine) cancel()
    }
}

/** Big enough to hit without being big enough to set the row's height on its own. */
private val CancelTouchTarget = 32.dp

/**
 * The room the pill keeps for a quote, on top of everything else it holds.
 *
 * A ceiling rather than a measurement — the pill caps its own height, and a cap that did not know
 * about the quote clipped it the moment the draft ran to more than a line or two.
 */
private val AppleQuoteRoom = 56.dp

/**
 * The pill, out of focus while its message is being dressed elsewhere.
 *
 * A blur rather than a fade, so the bar still reads as a bar with something in it — emptied, it
 * looked like the draft had been lost. Applied to the pill alone: the "+" beside it and the send
 * button after it stay sharp, because both are still things you press.
 */
@Composable
private fun Modifier.liftedOutOfFocus(lifted: Boolean): Modifier {
    val radius by animateDpAsState(
        targetValue = if (lifted) LiftedBlur else 0.dp,
        animationSpec = tween(PILL_MS, easing = PillEasing),
        label = "liftedBlur",
    )
    return if (radius <= 0.dp) this else this.blur(radius)
}

/** How far out of focus the pill goes while the effect picker is open. */
private val LiftedBlur = 8.dp

@Composable
private fun ComposerCard(background: Color, content: @Composable RowScope.() -> Unit) {
    // The same glass as the pill under it: the card is part of the bar, and one opaque half would
    // read as a panel sitting on a translucent one.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .composerGlass(RoundedCornerShape(16.dp), background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Mixes [toward] into this colour by [amount], for a shade of it rather than a second constant. */
private fun Color.shadeToward(toward: Color, amount: Float): Color = Color(
    red = red + (toward.red - red) * amount,
    green = green + (toward.green - green) * amount,
    blue = blue + (toward.blue - blue) * amount,
    alpha = alpha,
)

/**
 * The colour the Material bar's surfaces are drawn in, or null under the Apple bar.
 *
 * Public because the "+" card below the bar is part of the same stack and has to be made of the
 * same thing. Left as a local, the panel picked the page's neutral glass instead, and the two read
 * as unrelated surfaces that happened to be adjacent.
 */
@Composable
fun composerSurfaceFor(settings: Settings): Color = when {
    // The Apple bar's surface is a film over the conversation rather than a colour of its own, so
    // this is that film — the card below it is the same piece of glass, not a neighbour of it.
    // Darker than the bar itself, because the card is a far larger area of the same film: the
    // value that reads as a tint across a 48dp strip barely registers across a keyboard's worth
    // of screen.
    settings.bubbleType.sendBar() == SendBarStyle.APPLE -> AppleComposerTint.shadeToward(Color.Black, 0.45f)
    settings.materialYou -> MaterialTheme.colorScheme.surfaceVariant
    settings.mode == ThemeMode.DARK -> ComposerDark
    else -> Color.White
}

/**
 * A Material control made of glass: the conversation behind it, blurred, under its own colour.
 *
 * The Apple bar has had this from the start ([AppleBlurSurface]); the Material one was the last
 * opaque slab in the conversation, and a bar that does not let the wallpaper through sits *on* the
 * chat rather than in it. The difference between the two is only what the film is made of — the
 * Apple bar's is a fixed grey, and this is whatever colour the control already was.
 *
 * Falls back to the flat colour where there is nothing to blur, which is everything before Android
 * 12. A translucent bar over a sharp conversation is not a softer version of this effect; it is an
 * unreadable one.
 */
@Composable
private fun Modifier.composerGlass(shape: Shape, color: Color, alpha: Float = ComposerGlassTint): Modifier {
    val backdrop = LocalChatBackdrop.current
    return if (backdrop == null) {
        this.clip(shape).background(color)
    } else {
        this.frostedFill(shape, blur = ComposerBlur, tint = color.copy(alpha = alpha))
    }
}

/**
 * The send bar's own material, for something that stands directly above it.
 *
 * The mention list is the one thing that does. It used to be an opaque `surfaceVariant` slab — the
 * last flat surface left in the conversation — sitting on top of a bar made of glass, which read as
 * a menu from a different app having landed on the composer. Which glass it is depends on which bar
 * is underneath: the Apple bar's is a fixed film over the conversation, and every other style's is
 * the bar's own colour blurred. Asking the setting here rather than at the call site is what keeps
 * the two in step when the style changes.
 */
@Composable
fun ComposerGlass(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalSettings.current
    if (settings.bubbleType.sendBar() == SendBarStyle.APPLE) {
        AppleBlurSurface(shape = shape, modifier = modifier, content = content)
    } else {
        Box(
            modifier = modifier.composerGlass(shape, composerSurfaceFor(settings)),
            content = content,
        )
    }
}

/** Whether the bar above/below is the Apple one, for anything that has to ink itself to match it. */
@Composable
fun composerGlassIsDark(): Boolean =
    LocalSettings.current.bubbleType.sendBar() == SendBarStyle.APPLE

/** How much of a surface's own colour survives the conversation showing through it. */
private const val ComposerGlassTint = 0.55f
private val ComposerBlur = 24.dp

/**
 * The frosted surface the Apple bar's controls sit on: a real blur of the conversation behind it
 * where the platform allows one, and the flat two-layer translucency otherwise.
 */
@Composable
private fun AppleBlurSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalChatBackdrop.current
    val contentAlpha = LocalComposerContentAlpha.current
    // Minimums pass through to the content, which is what a fixed-size surface — the "+" button —
    // relies on to fill itself now that the content is wrapped for dimming rather than sized to the
    // parent. Without it the wrapper sized to a child that was sizing itself to the wrapper, both
    // came out at nothing, and the button was still there but zero pixels wide.
    Box(modifier = modifier.clip(shape), propagateMinConstraints = true) {
        // The blur lives on its own empty layer *behind* the controls. Putting it in the chain
        // above them would blur the icons and the draft text along with the backdrop.
        if (backdrop != null) {
            Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, shape))
        }
        Box(modifier = Modifier.matchParentSize().background(if (backdrop != null) AppleBlurTintOverBackdrop else AppleBlurBase))
        if (backdrop == null) Box(modifier = Modifier.matchParentSize().background(AppleBlurTint))
        // Only the controls dim while the bar is out of use. The glass behind them is the
        // conversation showing through, and a bar you cannot type into is still made of glass.
        //
        // Deliberately *not* `matchParentSize`: a child sized to its parent contributes nothing to
        // that parent's own size, so with the content in one the pill had nothing left driving its
        // height and sat at its 40dp minimum forever — a draft could wrap onto a second line and
        // the bar would not grow to show it. `propagateMinConstraints` passes the pill's minimum
        // through, so a lone one-line draft still fills it.
        Box(
            modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            propagateMinConstraints = true,
            content = content,
        )
    }
}

/**
 * How solid the Apple bar's controls are — 1 in use, dimmed while something else owns the keyboard.
 *
 * A local rather than a parameter because the surfaces that need it are scattered through the bar
 * and several levels down from where the state lives.
 */
private val LocalComposerContentAlpha = compositionLocalOf { 1f }

/** The side of one thumbnail in the draft's strip. */
private val PastedThumb = 64.dp

/** How wide a document's tile is in the same strip: two short lines of a file name. */
private val PastedFileWidth = 132.dp

/**
 * The gap between a thumbnail and the pill's edge, on every side of it.
 *
 * One number, not a horizontal and a vertical one. It is what makes the strip read as *inside* the
 * composer rather than as a row that happens to sit at the top of it — and it is half of what makes
 * the corners work, since it is also the difference between the pill's radius and the thumbnail's.
 */
private val PastedInset = 8.dp

/** How long the strip takes to open and shut under the draft. */
private const val PASTED_MS = 200

/**
 * A game waiting in the draft, as the send bar needs to draw it.
 *
 * Not the [com.bluebubbles.messaging.MadridMessage] the extension actually composed: the bar has no
 * business knowing what a Madrid message is, and every game OpenPigeon ships arrives here as the
 * same three things — a name, a line under it, and the picture the extension drew of the board.
 */
data class DraftGame(
    val name: String,
    val subtitle: String? = null,
    /** The artwork the extension rendered, as JPEG bytes. Null for a game that drew none. */
    val artwork: ByteArray? = null,
) {
    // Kept by hand because the artwork is an array, and an array's identity equals would make every
    // recomposition look like a new game and restart the card's animation.
    override fun equals(other: Any?): Boolean =
        other is DraftGame &&
            name == other.name &&
            subtitle == other.subtitle &&
            artwork.contentEquals(other.artwork)

    override fun hashCode(): Int =
        (name.hashCode() * 31 + (subtitle?.hashCode() ?: 0)) * 31 + (artwork?.size ?: 0)
}

/** The side of the artwork tile on the draft's game card. */
private val DraftGameTile = 52.dp

/** What a staged game adds to the pill's ceiling — the card, its insets and its rule. */
private val DraftGameRoom = DraftGameTile + PastedInset * 2 + 1.dp

/**
 * The game waiting in the draft, above the words that will go with it.
 *
 * Inside the pill, over the picture strip, for the reason the strip itself is in there: this is one
 * message being composed and the bar grows to hold it. Full width and taller than a thumbnail
 * because what is in it is the board — the extension renders a live picture of the game it just set
 * up, and shrinking that to 64dp square throws away the only part of it worth checking before it
 * goes.
 *
 * There is no tap-to-open. A staged game is not something to preview larger; it is something to
 * send or to take back out, which is the cross.
 */
@Composable
private fun DraftGameCard(
    game: DraftGame?,
    onRemove: () -> Unit,
    pillRadius: Dp,
    textColor: Color,
    subtitleColor: Color,
    dividerColor: Color,
    tileColor: Color,
) {
    // Held past the game leaving, so the card has something to draw while it shrinks away.
    val shown = remember { mutableStateOf(game) }
    LaunchedEffect(game) { if (game != null) shown.value = game }
    AnimatedVisibility(
        visible = game != null,
        enter = expandVertically(tween(PASTED_MS)) + fadeIn(tween(PASTED_MS)),
        exit = shrinkVertically(tween(PASTED_MS)) + fadeOut(tween(PASTED_MS)),
    ) {
        val current = shown.value ?: return@AnimatedVisibility
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(PastedInset),
            ) {
                // Concentric with the pill, the same way the thumbnails are — see [DraftMediaStrip].
                val tileShape = RoundedCornerShape((pillRadius - PastedInset).coerceAtLeast(0.dp))
                Box(
                    modifier = Modifier.size(DraftGameTile).clip(tileShape).background(tileColor),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = remember(current.artwork) { current.artwork?.let(::decodeArtwork) }
                    if (art != null) {
                        Image(
                            bitmap = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = subtitleColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        current.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.subtitle?.takeIf { it.isNotBlank() } ?: "Ready to send",
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove the game from the draft",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(horizontal = PastedInset))
        }
    }
}

/**
 * How tall the poll card is allowed to get before the choices scroll inside it.
 *
 * Four rows and the header, roughly. Past that the card would be eating the conversation to show a
 * list that is perfectly readable a few rows at a time — and the whole card is inside the pill,
 * which is already sharing the screen with the keyboard.
 */
private val PollOptionsMax = 210.dp

/** The header strip above the choices — the word "Poll" and the cross that takes it back out. */
private val PollHeaderHeight = 34.dp

/** One choice's row inside the card. */
private val PollRowHeight = 42.dp

/**
 * What a poll in the draft adds to the Apple pill's ceiling: the card at full stretch, and its rule.
 *
 * Declared after the three it is made of, and not by accident: top-level properties initialize in
 * the order they are written, so a sum that runs ahead of its parts is silently zero.
 */
private val PollDraftRoom = PollOptionsMax + PollHeaderHeight + PastedInset * 2 + 1.dp

/**
 * The rule the choice list applies to itself after every keystroke.
 *
 * A poll grows as it is written: fill in the last choice and an empty one appears under it, which
 * is the only way a list with no "add" button can offer a fifth option without also offering a
 * fifteenth. Clearing a choice again collapses the spare rows back down, so backing out of a
 * mistake does not leave a column of blanks behind — never below the three it started with, and
 * never above [Polls.MAX_OPTIONS], which is where a poll stops being a poll and becomes a form.
 */
private fun normalizedPoll(options: List<String>): List<String> {
    val next = options.toMutableList()
    while (next.size > Polls.STARTING_OPTIONS &&
        next.last().isBlank() &&
        next[next.size - 2].isBlank()
    ) {
        next.removeAt(next.size - 1)
    }
    if (next.size < Polls.MAX_OPTIONS && next.all { it.isNotBlank() }) next.add("")
    return next
}

/**
 * The poll being written, above the words that will become its question.
 *
 * Inside the pill and under the same rule as the picture strip, for the reason the strip itself is
 * in there: this is one message being composed, and the bar grows to hold it. What is different is
 * that it is a *card* on the glass rather than a row of contents on it — a slightly greyer film,
 * rounded concentric with the pill, with the choices ruled off from one another inside it. Polls
 * are the one thing the composer holds that is a list, and a list needs an edge to be a list inside
 * of.
 *
 * The fields are neither outlined nor filled. Each one already has a rule under it and a card
 * around it; a box around every row on top of that would be three borders deep.
 */
@Composable
private fun PollDraftCard(
    options: List<String>?,
    onChange: (List<String>) -> Unit,
    onRemove: () -> Unit,
    pillRadius: Dp,
    textColor: Color,
    hintColor: Color,
    subtitleColor: Color,
    dividerColor: Color,
    fillColor: Color,
) {
    // Held past the poll being taken back out, so the card has something to draw while it shrinks.
    val shown = remember { mutableStateOf(options) }
    LaunchedEffect(options) { if (options != null) shown.value = options }
    AnimatedVisibility(
        visible = options != null,
        enter = expandVertically(tween(PASTED_MS)) + fadeIn(tween(PASTED_MS)),
        exit = shrinkVertically(tween(PASTED_MS)) + fadeOut(tween(PASTED_MS)),
    ) {
        val current = shown.value ?: return@AnimatedVisibility
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PastedInset)
                    // Concentric with the pill, the same way the thumbnails and the game tile are.
                    .clip(RoundedCornerShape((pillRadius - PastedInset).coerceAtLeast(0.dp)))
                    .background(fillColor),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PollHeaderHeight)
                        .padding(start = 12.dp, end = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.BarChart,
                        contentDescription = null,
                        tint = subtitleColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        "Poll",
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleColor,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    // Top right, as on a thumbnail — the one way back out of a poll you have
                    // started and thought better of.
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove the poll from the draft",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                val choicesScroll = rememberScrollState()
                // A row appended past the card's height used to land out of sight, so a poll looked
                // like it stopped at four. Follow the list down to the new empty choice.
                LaunchedEffect(current.size) {
                    androidx.compose.runtime.withFrameNanos { }
                    choicesScroll.animateScrollTo(choicesScroll.maxValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PollOptionsMax)
                        .verticalScroll(choicesScroll),
                ) {
                    current.forEachIndexed { index, option ->
                        // The rule between two choices, and under the header above the first —
                        // this is the same seam the picture strip uses, drawn once per boundary
                        // rather than once per row, so the list has no rule hanging off its end.
                        HorizontalDivider(color = dividerColor)
                        // Keyed on the row rather than on its text: a choice is only ever appended
                        // or emptied, never reordered, so the index is the identity — and keying on
                        // the text would rebuild the field's own state on every keystroke.
                        key(index) {
                            PollOptionField(
                                value = option,
                                onValueChange = { typed ->
                                    onChange(
                                        normalizedPoll(
                                            current.toMutableList().also { it[index] = typed },
                                        ),
                                    )
                                },
                                hint = "Choice ${index + 1}",
                                textColor = textColor,
                                hintColor = hintColor,
                            )
                        }
                    }
                }
            }
            // The seam between the poll and the words under it, in the pill's own margin — the
            // same rule the picture strip and the game card leave behind them.
            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(horizontal = PastedInset))
        }
    }
}

/**
 * One choice in the poll card: a line of text with a rule over it and nothing else.
 *
 * Its own `TextFieldState` rather than the caller's string, for the reason [ComposerTextField]
 * keeps one — a string-valued field rewritten from above on every keystroke fights the caret. The
 * two are kept in step by the same pair of guarded effects.
 */
@Composable
private fun PollOptionField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    textColor: Color,
    hintColor: Color,
) {
    val state = rememberTextFieldState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latestOnChange(it) } }
    LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
    val style = ComposerTextStyle.scaledBy(LocalSettings.current.textScale())
    BasicTextField(
        state = state,
        textStyle = style.copy(color = textColor),
        cursorBrush = SolidColor(textColor),
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PollRowHeight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorator = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) Text(hint, color = hintColor, style = style)
                inner()
            }
        },
    )
}

/** The extension's JPEG as something Compose can draw, or null if it will not decode. */
private fun decodeArtwork(bytes: ByteArray): ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/**
 * Pictures and clips waiting in the draft, above the words that will go with them.
 *
 * Inside the pill rather than above it, so the composer *grows* to hold them: a strip floating over
 * the bar would be a second surface with its own edges, and what is being built here is one message
 * with two halves. The rule underneath is the seam between them.
 *
 * Scrolls sideways rather than wrapping. Choose six pictures and the pill would otherwise become
 * three rows of thumbnails and take half the screen away from the conversation.
 *
 * [pillRadius] is the composer's own corner, and the thumbnails are rounded to that minus the inset
 * they sit at. Two curves separated by a constant gap are concentric — they stay the same distance
 * apart the whole way round — which is what makes the first thumbnail look like it belongs in the
 * corner it is in rather than like a rounded square that happens to be near one. Any other radius
 * reads as a mistake even to someone who could not say why.
 */
@Composable
private fun DraftMediaStrip(
    media: List<Uri>,
    files: List<Attachment.File>,
    onRemove: (Uri) -> Unit,
    onRemoveFile: (Attachment.File) -> Unit,
    onOpen: (Uri) -> Unit,
    pillRadius: Dp,
    dividerColor: Color,
    textColor: Color,
    tileColor: Color,
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = media.isNotEmpty() || files.isNotEmpty(),
        enter = expandVertically(tween(PASTED_MS)) + fadeIn(tween(PASTED_MS)),
        exit = shrinkVertically(tween(PASTED_MS)) + fadeOut(tween(PASTED_MS)),
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PastedInset),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(PastedInset),
            ) {
                media.forEach { uri ->
                    key(uri) {
                        val isClip = remember(uri) { isVideoUri(context, uri) }
                        Box(modifier = Modifier.size(PastedThumb)) {
                            AsyncImage(
                                model = uri,
                                contentDescription = if (isClip) "Clip in the draft" else "Picture in the draft",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Concentric with the pill it sits in — see the note above.
                                    .clip(RoundedCornerShape((pillRadius - PastedInset).coerceAtLeast(0.dp)))
                                    .clickable { onOpen(uri) },
                            )
                            if (isClip) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            // Sunk into the corner rather than hung off it: the thumbnail is 64dp
                            // and a cross outside its edge would be the first thing a neighbouring
                            // thumbnail overlapped.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { onRemove(uri) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove from the draft",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
                // After the pictures, which is also the order they are sent in. As tall as a
                // thumbnail so the strip keeps one height whatever is in it, and wide enough for a
                // name to be read rather than guessed at.
                files.forEach { file ->
                    key(file.uri) {
                        Box(
                            modifier = Modifier
                                .size(width = PastedFileWidth, height = PastedThumb)
                                .clip(RoundedCornerShape((pillRadius - PastedInset).coerceAtLeast(0.dp)))
                                .background(tileColor),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 6.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    file.name,
                                    color = textColor,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { onRemoveFile(file) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove from the draft",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = dividerColor, modifier = Modifier.padding(horizontal = PastedInset))
        }
    }
}

/**
 * Whether a clipboard item is a picture this bar should take.
 *
 * Asked of the resolver rather than of the clip's own description: a `ClipDescription` reports the
 * types of the whole clip, and a paste out of a browser routinely carries an image *and* the html
 * and text around it. What decides is what is behind each individual uri.
 */
private fun isImageUri(context: Context, uri: Uri): Boolean =
    context.contentResolver.getType(uri)?.startsWith("image/") == true

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    /**
     * What the empty field says. "Message" everywhere except with a poll in the draft, where the
     * words being typed are the poll's question and a field inviting a message is asking for the
     * wrong thing.
     */
    placeholder: String = "Message",
    textColor: Color,
    placeholderColor: Color,
    cursorColor: Color,
    /** The return key sends instead of inserting a newline — `submitBehavior` in the RN source. */
    enterToSend: Boolean,
    onSend: () -> Unit,
    /** Pictures pasted, dropped, or committed by the keyboard; null leaves the clipboard alone. */
    onReceiveImages: ((List<Uri>) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    /** Paints the draft's own formatting onto the field, live. See [richOutputTransformation]. */
    outputTransformation: OutputTransformation? = null,
    /** Where the caret or the selection is, so the formatting controls know what they act on. */
    onSelectionChange: ((TextRange) -> Unit)? = null,
    /** Selectable and formattable, but not typeable — see [ChatComposer]'s own parameter. */
    readOnly: Boolean = false,
    /**
     * Raised when the draft grows into (or back out of) its own scroll, at [maxHeight].
     *
     * Measured rather than counted: wrapping and the conversation's zoom both decide how much of
     * the cap a given string takes, and neither is knowable from the string. The bar hands its
     * vertical drags back to the field while this holds — see [keyboardSwipe].
     */
    onScrollableChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 100.dp,
) {
    val context = LocalContext.current
    // Follows the conversation's pinch zoom — see [scaledBy].
    val composerTextStyle = ComposerTextStyle.scaledBy(LocalSettings.current.textScale())
    // The ceiling the field scrolls itself under, and the same number in pixels for the measured
    // height to be compared against.
    val fieldCap = maxHeight * LocalSettings.current.textScale()
    val fieldCapPx = with(LocalDensity.current) { fieldCap.roundToPx() }

    /**
     * The draft, as the text field's own state rather than as a plain string.
     *
     * The string-valued `BasicTextField` cannot be a content receiver — only the `TextFieldState`
     * one participates in the chain that carries a pasted or keyboard-committed picture — and a
     * text field that silently drops an image paste is the whole thing this is here to fix. The
     * caller's API is unchanged; the two are kept in step below.
     */
    val state = rememberTextFieldState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    // Typing, out to the screen.
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latestOnChange(it) } }
    // The screen writing back: a send clearing the draft, a mention being completed into it. Guarded
    // on inequality so the two effects cannot chase each other around a loop.
    LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
    // Reported rather than read: the field owns the selection, and the panel that formats against
    // it lives two screens up. Only where someone is listening — the recipient picker's bar has no
    // formatting controls and no reason to recompose on every tap of the caret.
    if (onSelectionChange != null) {
        val latestSelection by rememberUpdatedState(onSelectionChange)
        LaunchedEffect(state) { snapshotFlow { state.selection }.collect { latestSelection(it) } }
    }

    BasicTextField(
        state = state,
        readOnly = readOnly,
        outputTransformation = outputTransformation,
        // One explicit line height for the draft *and* the placeholder below. Left to the defaults
        // the two disagree — the placeholder inherits `bodyLarge`'s 24sp leading while the field's
        // own style falls back to the font's ~19sp — so the pill lost 5dp of height the moment you
        // typed the first character.
        textStyle = composerTextStyle.copy(color = textColor),
        cursorBrush = SolidColor(cursorColor),
        keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
        onKeyboardAction = { onSend() },
        // The field scrolls itself and brings the cursor back into view as it goes; the height cap
        // is what turns that on. An outer `verticalScroll` used to defeat both — the field laid out
        // at its full natural height inside a container that had no idea where the caret was, so a
        // long draft went on growing somewhere you could not see.
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .heightIn(max = fieldCap)
            .then(
                if (onScrollableChange == null) {
                    Modifier
                } else {
                    Modifier.onSizeChanged { onScrollableChange(it.height >= fieldCapPx) }
                },
            )
            .then(
                if (onReceiveImages == null) {
                    Modifier
                } else {
                    Modifier.contentReceiver { transferable ->
                        val pictures = mutableListOf<Uri>()
                        // Pictures are taken and everything else is handed back: a paste carrying an
                        // image and a caption should put the image in the strip *and* the words in
                        // the draft, which is what returning the remainder does.
                        val rest = transferable.consume { item ->
                            val uri = item.uri
                            if (uri != null && isImageUri(context, uri)) {
                                pictures += uri
                                true
                            } else {
                                false
                            }
                        }
                        if (pictures.isNotEmpty()) onReceiveImages(pictures)
                        rest
                    }
                },
            ),
        decorator = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) Text(placeholder, color = placeholderColor, style = composerTextStyle)
                inner()
            }
        },
    )
}
