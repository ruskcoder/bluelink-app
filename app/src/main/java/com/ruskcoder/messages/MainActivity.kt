package com.ruskcoder.messages

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.imessage.MessageNotifications
import com.ruskcoder.messages.ui.components.AppMenuHost
import com.ruskcoder.messages.ui.screens.setup.SetupGate
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.ui.theme.MessagesTheme

/** The scrims `enableEdgeToEdge` puts behind a 3-button navigation bar on API levels that need one. */
private val LightNavigationScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * A `FragmentActivity` rather than a bare `ComponentActivity` only because the system unlock prompt
 * behind the app lock is hosted in a fragment — see [com.ruskcoder.messages.security.AppLock].
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * Arrives when the activity is already running — the usual case for a notification tap, since
     * the app is launched in `singleTask` so the shade reuses the existing instance rather than
     * stacking another copy of the conversation on top.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedChat(intent)
    }

    /**
     * Contacts is edited in another app, so nothing tells us a name changed. Coming back into this
     * one is the closest thing to a signal there is, and it is exactly when the user is looking for
     * the change they just made.
     */
    override fun onResume() {
        super.onResume()
        (application as MessagesApp).container.refreshAddressBook()
    }

    /**
     * Track whether the app is actually on screen.
     *
     * The open conversation suppresses its own notifications, and that is only right while someone
     * is looking at it. Nothing disposes the chat destination when the user presses Home or locks
     * the phone, so without this the conversation they were last in stays "on screen" for the whole
     * time the app is in the background and every message in it arrives silently.
     */
    override fun onStart() {
        super.onStart()
        (application as MessagesApp).container.appVisible.value = true
    }

    override fun onStop() {
        super.onStop()
        (application as MessagesApp).container.appVisible.value = false
    }

    private fun openRequestedChat(intent: Intent?) {
        val chatId = intent?.getStringExtra(MessageNotifications.EXTRA_CHAT_ID) ?: return
        val container = (application as MessagesApp).container
        container.pendingChatId.value = chatId
        // Absent on a conversation shortcut, which lands here by the same route — see
        // [MessageNotifications.EXTRA_FOCUS_COMPOSER].
        if (intent.getBooleanExtra(MessageNotifications.EXTRA_FOCUS_COMPOSER, false)) {
            container.composeInChat.value = chatId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MessagesApp).container
        // A notification tap or a conversation shortcut names the chat to open; the navigation
        // graph picks it up from here, since the intent lands on the activity but only the graph
        // can act on it.
        openRequestedChat(intent)
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            // The app carries its own light/dark setting, so the system bars have to follow that
            // rather than the OS night mode. Left to `enableEdgeToEdge`'s default they follow the
            // system, which paints dark status-bar icons over the app's own dark theme — invisible —
            // on any phone whose OS is in light mode.
            val darkTheme = settings.mode == ThemeMode.DARK
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { darkTheme },
                )
            }
            MessagesTheme(settings = settings) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // Menus are drawn in this window rather than in popups of their own, so
                        // they can frost what is behind them. This is the layer they land on.
                        com.ruskcoder.messages.ui.components.AppLockGate(lock = container.appLock) {
                            AppMenuHost {
                                // Decides between iMessage setup and the app proper, and starts the
                                // push connection once there is a registration to connect with.
                                SetupGate(container = container)
                            }
                        }
                    }
                }
            }
        }
    }
}
