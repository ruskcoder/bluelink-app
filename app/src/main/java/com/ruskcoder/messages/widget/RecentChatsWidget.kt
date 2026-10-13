package com.ruskcoder.messages.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ruskcoder.messages.MainActivity
import com.ruskcoder.messages.MessagesApp
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.imessage.MessageNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A home-screen widget with the conversations at the top of the list, each a tap away.
 *
 * It follows the app's privacy settings rather than having its own: with the app lock on it says the
 * app is locked instead of listing anyone, and with message text hidden it names the conversations
 * without printing what was said in them. A widget is readable by anyone who can see the home screen.
 */
class RecentChatsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as MessagesApp).container
        val repository = container.chatRepository
        // A widget woken with the process cold asks before the list has been read off disk.
        withTimeoutOrNull(READY_TIMEOUT_MS) { repository.ready.first { it } }
        val settings = container.settingsRepository.settings.value
        val chats = repository.chats.value.filterNot { it.archived || it.blocked }.take(MAX_ROWS)
        provideContent {
            GlanceTheme {
                WidgetContent(chats = chats, locked = settings.appLock, hideText = settings.hideNotificationContent)
            }
        }
    }

    @Composable
    private fun WidgetContent(chats: List<Chat>, locked: Boolean, hideText: Boolean) {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                "Messages",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            when {
                locked -> Hint("Unlock the app to see your conversations")
                chats.isEmpty() -> Hint("No conversations yet")
                else -> LazyColumn {
                    items(chats, itemId = { it.id.hashCode().toLong() }) { chat ->
                        ChatRow(chat, hideText)
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatRow(chat: Chat, hideText: Boolean) {
        val context = LocalContext.current
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // Distinct data per row, or the launcher collapses every row's intent into one.
            .setData(android.net.Uri.parse("messages://chat/${chat.id}"))
            .putExtra(MessageNotifications.EXTRA_CHAT_ID, chat.id)
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    chat.name.ifBlank { "Conversation" },
                    style = TextStyle(
                        fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    maxLines = 1,
                )
                Text(
                    if (hideText) (if (chat.unread > 0) "New messages" else " ") else chat.preview,
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            if (chat.unread > 0) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Box(
                    modifier = GlanceModifier
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(10.dp)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        chat.unread.toString(),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary),
                    )
                }
            }
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }

    companion object {
        private const val TAG = "RecentChatsWidget"
        private const val MAX_ROWS = 8
        private const val READY_TIMEOUT_MS = 3_000L

        /** Redraws every placed copy; nothing at all when nobody has put one on a home screen. */
        suspend fun refresh(context: Context) {
            runCatching {
                if (GlanceAppWidgetManager(context).getGlanceIds(RecentChatsWidget::class.java).isEmpty()) return
                RecentChatsWidget().updateAll(context)
            }.onFailure { Log.w(TAG, "could not refresh the widget", it) }
        }
    }
}

/** The system's way in to [RecentChatsWidget]. */
class RecentChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentChatsWidget()
}
