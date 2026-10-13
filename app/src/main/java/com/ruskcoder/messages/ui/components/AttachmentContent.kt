package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.genmojiLabel
import com.ruskcoder.messages.data.model.MultiMediaStyle
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.data.model.formatBytes
import com.ruskcoder.messages.data.model.gameThumbnailColor
import com.ruskcoder.messages.imessage.TransferProgress
import com.ruskcoder.messages.ui.components.bubbles.BubbleColors
import com.ruskcoder.messages.util.formatDuration
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ruskcoder.messages.util.openAttachment
import com.ruskcoder.messages.util.saveAttachmentToDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True for attachment kinds that fill the whole bubble edge-to-edge (photo/video), no text padding. */
fun isEdgeToEdge(attachment: Attachment): Boolean = when (attachment) {
    // A Genmoji is the exception among pictures: it does not fill a bubble edge to edge, it stands
    // on the conversation with nothing around it at all — see [isGenmoji].
    is Attachment.Image -> attachment.genmoji == null
    is Attachment.Video, is Attachment.MediaGroup -> true
    // A game bubble *is* its artwork, the way OpenBubbles draws one: the board render fills the
    // whole shape and the status line sits in a scrim across the bottom of it. A coloured bubble
    // behind that is a frame around a picture and nothing else. Two exceptions — a turn the game
    // has moved past is a one-line summary rather than a board, and an invite whose picture has
    // not arrived has nothing to go edge to edge with.
    is Attachment.GameInvite -> !attachment.superseded && !attachment.previewPath.isNullOrBlank()
    else -> false
}

/** The words sent alongside a photo or a video, if there were any. */
fun mediaCaption(attachment: Attachment): String? = when (attachment) {
    is Attachment.Image -> attachment.caption
    is Attachment.Video -> attachment.caption
    else -> null
}

/**
 * True for a photo or a video that is the whole message rather than something sitting inside one.
 *
 * A bare picture has no business wearing a bubble: there is no text for the fill to sit behind, and
 * the padding around it only reads as a coloured frame. Anything the message is *also* carrying —
 * a quote panel above it, a caption below — puts the picture back inside a bubble, since those do
 * need the ground to sit on.
 */
fun isFlushMedia(attachment: Attachment?, hasQuote: Boolean): Boolean =
    attachment != null && isEdgeToEdge(attachment) && !hasQuote && mediaCaption(attachment).isNullOrBlank()

/**
 * True for media that draws its own shapes and must not be clipped to, or painted under, a bubble:
 * a group's tiles have gaps between them, and a card stack's cards lean out past its edges.
 */ 
fun isBareMedia(attachment: Attachment?): Boolean =
    attachment is Attachment.MediaGroup || isGenmoji(attachment) ||
        // A sticker is a cut-out: clipped to a bubble outline it loses whatever pokes past the corners.
        (attachment is Attachment.Image && attachment.sticker)

/**
 * Whether this is a Genmoji: a glyph the sender typed into their sentence, not a picture they sent.
 *
 * It gets no bubble for the same reason a message of nothing but emoji gets none — what is being
 * drawn is a character, and a frame around it would turn it back into an enclosure.
 */
fun isGenmoji(attachment: Attachment?): Boolean =
    attachment is Attachment.Image && attachment.genmoji != null

/**
 * How far from square a media tile is allowed to get.
 *
 * A phone screenshot is around 9:19.5 and a panorama can be wider than 3:1; rendered at their true
 * ratio either one takes over the whole conversation. Cropping to these bounds keeps a thread
 * scrollable, and the viewer shows the real thing at full size.
 */
private const val MIN_ASPECT = 0.62f
private const val MAX_ASPECT = 1.8f

/** What a tile falls back to when the sender told us no dimensions. */
private const val DEFAULT_ASPECT = 4f / 3f

private fun aspectOf(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) DEFAULT_ASPECT else (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)

/**
 * The send this attachment belongs to, while it is still going out.
 *
 * A composition local rather than a parameter because the three bubble styles each call
 * [AttachmentContent] themselves, from inside their own layout, and threading one nullable through
 * all of them — plus the overlay and the preview that also draw bubbles — to say "this one is busy"
 * is a lot of plumbing for a fact that is true of at most one or two rows at a time.
 */
val LocalAttachmentTransfer = compositionLocalOf<TransferProgress?> { null }

/**
 * Asks for every undownloaded attachment to be fetched again, or null where nothing can.
 *
 * A composition local for the same reason [LocalAttachmentTransfer] is. Provided by the conversation
 * screen; a bubble drawn anywhere else — a preview, a forward sheet — has no download to retry.
 */
val LocalAttachmentRetry = compositionLocalOf<(() -> Unit)?> { null }

/**
 * How dark a picture goes while its bytes are still going out.
 *
 * A black film laid *over* the attachment, not transparency applied to it. Faded with an alpha, an
 * image thins out until whatever is behind it shows through — and what is behind it is the bubble,
 * so a photo halfway out came up tinted the conversation's own blue. Black over the top is the
 * darkening this was always meant to be, and it is the same colour whatever the chat is set to.
 */
private val SendingFilm = Color.Black.copy(alpha = 0.55f)

/** Dispatches to a per-kind renderer. */
@Composable
fun AttachmentContent(attachment: Attachment, colors: BubbleColors, flush: Boolean = false) {
    val transfer = LocalAttachmentTransfer.current
    if (transfer == null) {
        AttachmentBody(attachment, colors, flush)
        return
    }
    // The picture stays exactly where it is and is darkened, not replaced by a placeholder: what is
    // being sent is the thing you are looking at, and swapping it for a grey box while it uploads
    // loses the only confirmation you have that the right file was picked.
    Box(contentAlignment = Alignment.Center) {
        AttachmentBody(attachment, colors, flush)
        Box(modifier = Modifier.matchParentSize().background(SendingFilm))
        SendingOverlay(transfer)
    }
}

/**
 * The ring and the count, over the middle of whatever is being sent.
 *
 * No card behind it. The film over the picture is already the ground these sit on, and a second
 * panel inside it made the progress read as a widget dropped onto the photo rather than as the
 * photo's own state.
 *
 * The ring is a real fraction while the file is being copied out of the picker and a spinner while
 * it is being uploaded — see [TransferProgress.determinate] for why the second half has no number
 * behind it yet. The line underneath is the same shape throughout, so the numbers do not change
 * format halfway through a send.
 */
@Composable
private fun SendingOverlay(transfer: TransferProgress) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (transfer.determinate && transfer.total > 0) {
            CircularProgressIndicator(
                progress = { (transfer.sent.toFloat() / transfer.total).coerceIn(0f, 1f) },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
        } else {
            CircularProgressIndicator(
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
        }
        if (transfer.total > 0L) {
            Text(
                "${formatBytes(transfer.sent)} / ${formatBytes(transfer.total)}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AttachmentBody(attachment: Attachment, colors: BubbleColors, flush: Boolean) {
    when (attachment) {
        is Attachment.Image -> if (attachment.genmoji != null) GenmojiGlyph(attachment, colors)
        else if (attachment.sticker) StickerTile(attachment, colors) else MediaTile(
            uri = attachment.uri,
            aspect = aspectOf(attachment.width, attachment.height),
            fallbackIcon = Icons.Default.Image,
            fallbackLabel = "Photo",
            caption = attachment.caption,
            colors = colors,
            flush = flush,
        )
        is Attachment.Video -> MediaTile(
            uri = attachment.uri,
            aspect = aspectOf(attachment.width, attachment.height),
            fallbackIcon = Icons.Default.Videocam,
            fallbackLabel = "Video",
            caption = attachment.caption,
            colors = colors,
            // Coil pulls a frame out of the file for the still; the badge says it will play, and
            // the length says how long for.
            playable = true,
            durationMs = attachment.durationMs,
            flush = flush,
        )
        is Attachment.File -> FileRow(attachment, colors)
        is Attachment.Location -> AttachmentRow(Icons.Default.PinDrop, attachment.label ?: "Location", colors)
        is Attachment.ContactCard -> AttachmentRow(Icons.Default.Person, attachment.name, colors, attachment.phone)
        is Attachment.GameInvite -> GameInviteTile(attachment, colors, flush)
        is Attachment.MediaGroup ->
            if (LocalSettings.current.multiMediaStyle == MultiMediaStyle.STACK) MediaCardStack(attachment) else MediaGroupBubble(attachment)
        // Never reached in a conversation: a poll is drawn in place of the bubble entirely, not as
        // a tile inside one — see [PollCard]. This is what a surface that has a poll and no poll
        // state shows, which is a chat-list preview or a forward sheet.
        is Attachment.Poll -> Text(
            attachment.ldText?.takeIf { it.isNotBlank() } ?: "Poll",
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A game's icon and name in a chat bubble: the same square-tile shape the games picker uses.
 *
 * No tap handling here -- opening a game invite goes through [MessageThread]'s `onTap`, the same
 * dispatch point a photo or an in-thread reply uses (see ChatScreen's `onTap` for the actual
 * launch), since a bubble tap here would collide with the thread's own tap-to-show-time gesture.
 */
@Composable
private fun GameInviteTile(attachment: Attachment.GameInvite, colors: BubbleColors, flush: Boolean) {
    // A turn the game has since moved past. Drawn as a line rather than a board: the exchange
    // stays legible as having happened without a column of near-identical pictures burying
    // whatever else was being said. See MessageThread's collapse pass.
    if (attachment.superseded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.SportsEsports,
                contentDescription = null,
                tint = colors.text.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                attachment.displayName,
                color = colors.text.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    val previewPath = attachment.previewPath
    if (previewPath.isNullOrBlank()) {
        // No picture yet — the icon tile, with the name under it as before.
        Column(horizontalAlignment = Alignment.Start) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(gameThumbnailColor(attachment.gameId)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(
                attachment.displayName,
                color = colors.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        return
    }

    Box {
        MediaTile(
            uri = previewPath,
            aspect = 1f,
            fallbackIcon = Icons.Default.SportsEsports,
            fallbackLabel = attachment.displayName,
            caption = null,
            colors = colors,
            flush = flush,
        )
        // The name and the game's own status line, over the bottom of the board.
        //
        // Drawn on a gradient rather than a flat bar: a board render is bright in some corners and
        // dark in others, and a solid strip either hides too much of it or leaves the text
        // unreadable over whatever happens to be behind it. The gradient reaches full strength only
        // under the text.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                attachment.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            val status = attachment.caption?.takeIf { it.isNotBlank() }
            if (status != null) {
                Text(
                    status,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A photo or a video's first frame, filling the bubble.
 *
 * The icon-and-label placeholder is drawn *underneath* the image rather than instead of it, so a
 * message whose bytes have not finished downloading — or whose file has been cleared off the
 * device — shows what kind of thing it is rather than an empty rectangle.
 */
@Composable
private fun MediaTile(
    uri: String,
    aspect: Float,
    fallbackIcon: ImageVector,
    fallbackLabel: String,
    caption: String?,
    colors: BubbleColors,
    playable: Boolean = false,
    durationMs: Long? = null,
    /**
     * True when the picture *is* the message: the caller has already masked it to whatever shape it
     * wants — a bubble outline, a plain rounded rectangle — so the tile must not round itself a
     * second time inside that.
     */
    flush: Boolean = false,
) {
    // The ground and the icon are only there until the picture arrives. Left underneath, a PNG with
    // transparency in it showed them through its empty parts — the conversation's blue or grey
    // filling in around a cut-out that was sent without any background at all.
    var loaded by remember(uri) { mutableStateOf(false) }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = 180.dp)
                .aspectRatio(aspect)
                .then(if (flush) Modifier else Modifier.clip(RoundedCornerShape(14.dp)))
                .then(
                    when {
                        loaded -> Modifier
                        // A flush tile has no bubble under it, so it lays down the bubble's own
                        // colour to stand in for one while it waits.
                        flush -> Modifier.background(colors.background).background(colors.text.copy(alpha = 0.14f))
                        else -> Modifier.background(colors.text.copy(alpha = 0.14f))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!loaded) {
                Icon(fallbackIcon, contentDescription = fallbackLabel, tint = colors.text.copy(alpha = 0.75f), modifier = Modifier.size(34.dp))
            }
            if (uri.isNotBlank()) {
                MediaThumbnail(
                    uri = uri,
                    contentDescription = fallbackLabel,
                    onSuccess = { loaded = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (playable) {
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                if (durationMs != null && durationMs > 0) {
                    Text(
                        formatDuration(durationMs / 1000f),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                color = colors.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A sticker: the picture itself, small and unframed, the way iMessage sets one down.
 *
 * No aspect-ratio tile and no crop — a sticker is a cut-out with transparency around it, and cropping
 * it into a rectangle or painting a fill behind it would make it a photo of a sticker.
 */
@Composable
private fun StickerTile(image: Attachment.Image, colors: BubbleColors) {
    Box(modifier = Modifier.size(StickerSize), contentAlignment = Alignment.Center) {
        if (image.uri.isBlank()) {
            Icon(Icons.Default.Image, contentDescription = "Sticker", tint = colors.text.copy(alpha = 0.5f), modifier = Modifier.size(34.dp))
        } else {
            AsyncImage(
                model = image.uri,
                contentDescription = "Sticker",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** How large a sticker is drawn: about what Apple's own clients give one on a phone. */
private val StickerSize = 120.dp

/**
 * A Genmoji: the glyph itself, unframed, about the size an iPhone draws one sent on its own.
 *
 * Not a media tile. A Genmoji has transparency around it and belongs to the text — cropping it to an
 * aspect ratio and painting the conversation's colour behind it is what made it read as a photo of
 * an emoji rather than as an emoji. Its description is what a screen reader says and what shows while
 * the bytes are still on their way, since a glyph nobody can see yet still said something.
 */
@Composable
private fun GenmojiGlyph(image: Attachment.Image, colors: BubbleColors) {
    val label = genmojiLabel(image.genmoji)
    Box(modifier = Modifier.size(GenmojiSize), contentAlignment = Alignment.Center) {
        if (image.uri.isBlank()) {
            Icon(
                Icons.Default.Image,
                contentDescription = label,
                tint = colors.text.copy(alpha = 0.5f),
                modifier = Modifier.size(34.dp),
            )
        } else {
            AsyncImage(
                model = image.uri,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * How large a Genmoji sent on its own is drawn: the box a lone jumbo emoji takes up (52sp glyph in
 * its 1.22 line box), so the two sit the same size side by side.
 */
private val GenmojiSize = 64.dp

@Composable
private fun AttachmentRow(icon: ImageVector, label: String, colors: BubbleColors, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.text)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, color = colors.text, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, color = colors.meta, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Where saving a file to the phone has got to, which is what its button offers next. */
private enum class FileSave { IDLE, SAVING, SAVED }

/**
 * A file: its name and size, and a button on the trailing side that saves it to the phone and, once
 * saved, opens it.
 *
 * The name gives way to the button rather than pushing it out of the bubble: it takes whatever width
 * is left inside the bubble's own maximum, and a name longer than that runs past, pausing between
 * passes, so the whole of it can be read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: Attachment.File, colors: BubbleColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var save by rememberSaveable(file.uri) { mutableStateOf(FileSave.IDLE) }
    // A received file whose bytes never arrived has no uri. Its button fetches it rather than
    // offering to save a file that is not on the phone — which used to fail with "Couldn't save".
    val missing = file.uri.isBlank()
    val retry = LocalAttachmentRetry.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Description, contentDescription = null, tint = colors.text)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                file.name,
                color = colors.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            formatBytes(file.size)?.let { size ->
                Text(size, color = colors.meta, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = save != FileSave.SAVING && (!missing || retry != null)) {
                    if (missing) {
                        retry?.invoke()
                        Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
                    } else if (save == FileSave.SAVED) {
                        if (!openAttachment(context, file.uri)) {
                            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        save = FileSave.SAVING
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                saveAttachmentToDevice(context, file.uri, file.name)
                            }
                            save = if (saved != null) FileSave.SAVED else FileSave.IDLE
                            if (saved == null) {
                                Toast.makeText(context, "Couldn't save this one", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                missing -> Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry download",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                save == FileSave.SAVING -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                save == FileSave.SAVED -> Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                else -> Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
