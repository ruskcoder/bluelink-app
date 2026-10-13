package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The line at the top of the conversation list saying messages are being held up, and offering the
 * one tap that fixes it.
 *
 * A banner rather than a dialog because the condition is not urgent and not new — it is true from
 * the moment the app is installed until somebody grants the exemption, and a modal that appeared
 * every launch for a standing condition is a modal that gets dismissed without being read. Here it
 * sits above the conversations, says what is actually wrong in the user's own terms ("messages
 * arrive late"), and can be waved away for good.
 *
 * It deliberately does not describe the mechanism. "Android is firewalling your push socket during
 * Doze" is the true answer and the wrong thing to put in front of someone who just wants their
 * messages on time; the settings row carries the longer explanation for anyone who goes looking.
 */
@Composable
fun BackgroundDeliveryBanner(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            // The whole strip is the target, not just a button on it: the action is the reason the
            // banner exists, and the dismiss is the deliberate one.
            .clickable(onClick = onAllow)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(
            Icons.Outlined.BatteryAlert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(22.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                "Messages are arriving late",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Allow background delivery so they arrive when they are sent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDismiss)
                .padding(8.dp)
                .size(18.dp),
        )
    }
}
