package com.ruskcoder.messages.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.LinkPreview
import com.ruskcoder.messages.util.LinkMetaParser

/**
 * The link card the message being drawn carries, for [BubbleText] to put under its words.
 *
 * A composition local for the same reason [LocalAttachmentTransfer] is: the three bubble styles each
 * draw their own text, and threading a card through all of them — and through every overlay that
 * also draws a bubble — is a lot of plumbing for something only a text message with a link has.
 * [BubbleText] clears it for anything it draws inside, so a quote in the card never grows one.
 */
val LocalLinkPreview = compositionLocalOf<LinkPreview?> { null }

/**
 * A link's card: the page's picture, its title, and where it is from.
 *
 * Tinted from the bubble's own ink rather than given a colour of its own, so the card belongs to the
 * bubble it sits in whichever style and theme the conversation is in.
 */
@Composable
fun LinkPreviewCard(preview: LinkPreview, textColor: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(textColor.copy(alpha = 0.10f))
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, preview.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
    ) {
        preview.imageUri?.takeIf { it.isNotBlank() }?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(CardImageAspect),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            preview.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    title,
                    color = textColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            (preview.siteName ?: LinkMetaParser.hostOf(preview.url))?.let { site ->
                Text(
                    site,
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The 1.91:1 of an Open Graph image, which is what almost every site's card picture is cut to. */
private const val CardImageAspect = 1.91f
