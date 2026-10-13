package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.ruskcoder.messages.data.model.GroupMember

/**
 * What tapping someone's face or name in a group conversation can do.
 *
 * A composition local rather than a parameter threaded through the thread, the row and all three
 * bubble styles: the thing that knows how to open a chat or place a call is the screen, and the
 * things that draw a face are four layers down.
 */
class MemberActions(
    val onMessage: (GroupMember) -> Unit,
    val onCall: (GroupMember) -> Unit,
    val onDetails: (GroupMember) -> Unit,
)

val LocalMemberActions = compositionLocalOf<MemberActions?> { null }

/**
 * Makes [content] — a face or a name — open a menu of what can be done with [member].
 *
 * Inert where nothing provides [LocalMemberActions], such as the appearance preview and the
 * lifted-message overlay, so the same bubble draws everywhere.
 */
@Composable
fun MemberMenuTarget(member: GroupMember, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val actions = LocalMemberActions.current
    if (actions == null) {
        Box(modifier) { content() }
        return
    }
    var open by remember(member.id) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .semantics { onClick(label = "Open menu for ${member.name}") { open = true; true } }
            // On the way down, ahead of the row it sits in: the row answers a tap anywhere by
            // opening the message's time and status, and that is for the message, not for the face
            // or name beside it. Taking the touch here leaves the row nothing to answer.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        open = true
                    }
                }
            },
    ) {
        content()
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuItem(
                text = { Text("Message") },
                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
                onClick = { open = false; actions.onMessage(member) },
            )
            AppMenuItem(
                text = { Text("Call") },
                trailingIcon = { Icon(Icons.Outlined.Call, contentDescription = null) },
                onClick = { open = false; actions.onCall(member) },
            )
            AppMenuItem(
                text = { Text("Show contact") },
                trailingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                onClick = { open = false; actions.onDetails(member) },
            )
        }
    }
}
