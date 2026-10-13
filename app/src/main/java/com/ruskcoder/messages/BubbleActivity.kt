package com.ruskcoder.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.imessage.MessageNotifications
import com.ruskcoder.messages.ui.components.AppLockGate
import com.ruskcoder.messages.ui.components.AppMenuHost
import com.ruskcoder.messages.ui.screens.chat.ChatScreen
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.ui.theme.MessagesTheme

/**
 * One conversation, floating in a notification bubble over whatever else is on screen.
 *
 * The same conversation screen the app draws, hosted on its own rather than through the navigation
 * graph: a bubble is exactly one chat, and every way out of it — a contact's page, search — hands off
 * to the full app instead of trying to become a whole app inside a floating window.
 */
class BubbleActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra(MessageNotifications.EXTRA_CHAT_ID)
        if (chatId.isNullOrBlank()) {
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as MessagesApp).container

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            MessagesTheme(settings = settings) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppLockGate(lock = container.appLock) {
                            AppMenuHost {
                                ChatScreen(
                                    chatId = chatId,
                                    initialName = null,
                                    initialGroup = false,
                                    initialMembers = emptyList(),
                                    chatRepository = container.chatRepository,
                                    imessageSender = container.imessageSender,
                                    pigeonClient = container.pigeonClient,
                                    openPigeonClient = container.openPigeonClient,
                                    typingByChat = container.imessageSyncer.typing,
                                    onChatRead = container.imessageNotifications::clear,
                                    contactsRepository = container.contactsRepository,
                                    settingsRepository = container.settingsRepository,
                                    voiceTranscriptions = container.voiceTranscriptions,
                                    speechModels = container.speechModels,
                                    onBack = ::finish,
                                    onOpenContact = { openInApp(chatId) },
                                    onOpenGroup = { openInApp(chatId) },
                                    onOpenSearch = { openInApp(chatId) },
                                    onRetryDownloads = { container.imessageSyncer.resumePendingDownloads() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Hands the conversation to the full app, for anything a bubble is too small to be. */
    private fun openInApp(chatId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MessageNotifications.EXTRA_CHAT_ID, chatId),
        )
    }

    companion object {
        fun intent(context: Context, chatId: String): Intent =
            Intent(context, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // Distinct data per conversation, so each bubble's pending intent is its own.
                .setData(android.net.Uri.parse("messages://bubble/$chatId"))
                .putExtra(MessageNotifications.EXTRA_CHAT_ID, chatId)
    }
}
