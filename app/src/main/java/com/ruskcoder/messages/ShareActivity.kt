package com.ruskcoder.messages

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.imessage.MessageNotifications
import com.ruskcoder.messages.imessage.PushService
import com.ruskcoder.messages.ui.components.AppMenuHost
import com.ruskcoder.messages.ui.screens.share.ShareTargetScreen
import com.ruskcoder.messages.ui.screens.share.sharedContent
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.ui.theme.MessagesTheme

/**
 * The share sheet's way in.
 *
 * An activity of its own rather than a destination inside [MainActivity]. `MainActivity` is
 * `singleTask`, so routing a share through it would haul the whole running app to the front, drop
 * the share on top of whatever conversation was open, and leave the person who shared a photograph
 * from their gallery inside a messaging app they never asked to open — with no way back but the
 * recents list. With its own empty task affinity this one stacks on top of the *sharing* app the
 * way a share target is supposed to, and finishing it puts them back exactly where they were.
 *
 * It talks to the same [AppContainer] as the main app, so a share lands in the same store, over the
 * same push connection, with the same drafts and settings behind it.
 */
class ShareActivity : androidx.fragment.app.FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent.sharedContent()
        // Nothing to send. Reached by an intent shaped like a share but carrying neither text nor a
        // stream, which is worth saying rather than presenting an empty picker that cannot do
        // anything when its Send is pressed.
        if (shared.isEmpty) {
            Toast.makeText(this, "There was nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        val container = (application as MessagesApp).container
        val activity = this

        // The sends outlive this screen, so something has to keep the process alive long enough to
        // finish them — an upload cut off by the activity going away is a share that silently never
        // arrives. The foreground service is already that something for inbound push; started here
        // it covers a share made without the main app having been opened at all. Gated on there
        // being a registration to connect with, exactly as `SetupGate` gates it.
        lifecycleScope.launch {
            if (runCatching { container.imessageService.isRegistered() }.getOrDefault(false)) {
                PushService.start(activity)
            }
        }

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            // The same reasoning as `MainActivity`: the app carries its own light/dark setting, so
            // the system bars follow that rather than the OS night mode.
            val darkTheme = settings.mode == ThemeMode.DARK
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
            }
            MessagesTheme(settings = settings) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        com.ruskcoder.messages.ui.components.AppLockGate(lock = container.appLock) {
                        AppMenuHost {
                            ShareTargetScreen(
                                shared = shared,
                                // A conversation picked from the share sheet's own top row arrives
                                // named, so the picker opens with it already chosen.
                                preselectedChatId = intent
                                    .getStringExtra(androidx.core.content.pm.ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
                                    ?.removePrefix(MessageNotifications.SHORTCUT_PREFIX),
                                chatRepository = container.chatRepository,
                                contactsRepository = container.contactsRepository,
                                imessageSender = container.imessageSender,
                                onClose = { finish() },
                                onSent = { targets ->
                                    // The sends run on the application scope, so they outlive this
                                    // screen — which is the point: the share is handed off and the
                                    // person is put straight back into whatever they shared from,
                                    // rather than being made to watch a progress bar in an app
                                    // they were not using. The conversations themselves show every
                                    // bubble's real progress if they go looking.
                                    Toast.makeText(activity, sentMessage(targets), Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    private fun sentMessage(targets: Int): String =
        if (targets == 1) "Sending" else "Sending to $targets conversations"
}
