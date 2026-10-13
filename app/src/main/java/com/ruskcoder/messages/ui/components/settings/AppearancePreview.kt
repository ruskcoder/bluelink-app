package com.ruskcoder.messages.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.data.model.sendBar
import com.ruskcoder.messages.ui.components.ChatComposer
import com.ruskcoder.messages.ui.components.ChatWallpaper
import com.ruskcoder.messages.ui.components.MessageBubble

/** The two lines of the sample conversation, so the shapes have something to hold. */
private val SAMPLE_THEIRS = Message.Text(
    id = "preview-theirs",
    mine = false,
    time = "9:41",
    text = "How does this look?",
    status = DeliveryStatus.READ,
)
private val SAMPLE_MINE = Message.Text(
    id = "preview-mine",
    mine = true,
    time = "9:41",
    text = "Just right",
    status = DeliveryStatus.READ,
)

/**
 * A sample of the appearance settings as a working chat: the real wallpaper, the real bubbles, and
 * the real send bar, drawn from the same components the conversation screen uses so the preview
 * cannot drift from the thing it is previewing. Nothing here is interactive — the composer's
 * callbacks go nowhere — but every pixel is produced by the code that will draw the real screen.
 */
@Composable
fun AppearancePreview(settings: Settings, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        PreviewHeader()
        Box(modifier = Modifier.fillMaxWidth()) {
            // `matchParentSize` rather than the wallpaper's own `fillMaxSize`: inside a scrolling
            // settings page the height is unbounded, and filling an unbounded height measures to
            // nothing. The sample conversation sets the height and the wallpaper takes it.
            ChatWallpaper(modifier = Modifier.matchParentSize()) {}
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                MessageBubble(message = SAMPLE_THEIRS, isFirstInRun = true, isLastInRun = true)
                MessageBubble(message = SAMPLE_MINE, isFirstInRun = true, isLastInRun = true)
                ChatComposer(
                    text = "",
                    onTextChange = {},
                    onSend = {},
                    onAttachmentClick = {},
                    onMicClick = {},
                    sendBarType = settings.bubbleType.sendBar(),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * The one part with no component of its own to borrow: the chat screen builds its header inline
 * from a `TopAppBar`. This is the same treatment in miniature — translucent black over the
 * wallpaper.
 */
@Composable
private fun PreviewHeader() {
    val container = Color.Black.copy(alpha = 0.45f)
    val content = Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(container).height(44.dp).padding(horizontal = 10.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("Alex", color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Call, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
    }
}
