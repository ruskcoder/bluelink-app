package com.ruskcoder.messages.imessage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.ruskcoder.messages.MessagesApp
import kotlinx.coroutines.launch

/**
 * Handles the buttons on a message notification.
 *
 * A broadcast receiver rather than an activity so replying and marking as read never pull the app
 * to the foreground — the point of an inline reply is answering without leaving what you were
 * doing. The work itself is handed to the same repository the UI uses, so a reply sent from the
 * shade is indistinguishable from one typed in the conversation.
 */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(MessageNotifications.EXTRA_CHAT_ID) ?: return
        val container = (context.applicationContext as MessagesApp).container

        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) return

                val chatName = intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty()

                // goAsync keeps the receiver alive past onReceive; a send involves storage and the
                // network, neither of which finishes inside the synchronous window.
                val pending = goAsync()
                container.applicationScope.launch {
                    try {
                        val identity = container.chatRepository.identityOf(chatId)
                        if (identity == null) {
                            Log.w(TAG, "reply to unknown conversation $chatId")
                            return@launch
                        }
                        // The same call the composer makes. `ChatRepository.sendMessage` only
                        // writes the message locally — going through it would show the reply in the
                        // thread without ever putting it on the wire.
                        container.imessageSender.sendText(chatId, identity, text)
                        // Answering a conversation has read it.
                        container.chatRepository.markRead(chatId)
                        // Leave the notification up with the reply shown in it, the way the
                        // platform's messaging apps do, rather than cancelling out from under a
                        // conversation that is still going.
                        container.imessageNotifications.appendOwnReply(chatId, chatName, text)
                    } catch (e: Exception) {
                        Log.w(TAG, "reply from notification failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            // A reaction placed from the shade. The same call a double-tap in the conversation makes,
            // so it reaches the other end as a real tapback; reacting has read the conversation.
            ACTION_REACT -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
                val emoji = intent.getStringExtra(EXTRA_EMOJI) ?: return
                container.imessageSender.toggleTapback(
                    chatId,
                    messageId,
                    intent.getStringExtra(EXTRA_MESSAGE_TEXT).orEmpty(),
                    emoji,
                )
                container.chatRepository.markRead(chatId)
                container.imessageNotifications.clear(chatId)
            }

            ACTION_MARK_READ -> {
                container.chatRepository.markRead(chatId)
                container.imessageNotifications.clear(chatId)
            }

            // Swiped away: forget the accumulated lines so the next message starts a fresh thread
            // rather than replaying ones already dismissed.
            ACTION_DISMISS -> container.imessageNotifications.clear(chatId)
        }
    }

    companion object {
        const val KEY_REPLY = "com.ruskcoder.messages.KEY_REPLY"

        private const val ACTION_REPLY = "com.ruskcoder.messages.action.REPLY"
        private const val ACTION_MARK_READ = "com.ruskcoder.messages.action.MARK_READ"
        private const val ACTION_REACT = "com.ruskcoder.messages.action.REACT"
        private const val EXTRA_MESSAGE_ID = "com.ruskcoder.messages.MESSAGE_ID"
        private const val EXTRA_MESSAGE_TEXT = "com.ruskcoder.messages.MESSAGE_TEXT"
        private const val EXTRA_EMOJI = "com.ruskcoder.messages.EMOJI"
        private const val ACTION_DISMISS = "com.ruskcoder.messages.action.DISMISS"
        private const val EXTRA_CHAT_NAME = "com.ruskcoder.messages.CHAT_NAME"
        private const val TAG = "NotificationActions"

        fun replyIntent(context: Context, chatId: String, chatName: String): PendingIntent =
            pending(context, ACTION_REPLY, chatId, requestOffset = 1) {
                putExtra(EXTRA_CHAT_NAME, chatName)
            }

        fun reactIntent(context: Context, chatId: String, messageId: String, text: String, emoji: String): PendingIntent =
            pending(context, ACTION_REACT, chatId, requestOffset = 4) {
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_MESSAGE_TEXT, text)
                putExtra(EXTRA_EMOJI, emoji)
            }

        fun markReadIntent(context: Context, chatId: String): PendingIntent =
            pending(context, ACTION_MARK_READ, chatId, requestOffset = 2)

        fun dismissIntent(context: Context, chatId: String): PendingIntent =
            pending(context, ACTION_DISMISS, chatId, requestOffset = 3)

        /**
         * `requestOffset` keeps the three actions on distinct request codes. Without it they would
         * collide on the chat's hash and the last one built would silently replace the others.
         *
         * Mutable because `RemoteInput` has to write the typed reply into the intent; the explicit
         * component keeps that from being exploitable by another app.
         */
        private fun pending(
            context: Context,
            action: String,
            chatId: String,
            requestOffset: Int,
            extras: Intent.() -> Unit = {},
        ): PendingIntent {
            val intent = Intent(context, NotificationActions::class.java).apply {
                this.action = action
                setPackage(context.packageName)
                putExtra(MessageNotifications.EXTRA_CHAT_ID, chatId)
                extras()
            }
            return PendingIntent.getBroadcast(
                context,
                chatId.hashCode() + requestOffset,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
