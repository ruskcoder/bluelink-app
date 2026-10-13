package com.ruskcoder.messages.ui.screens.setup

import android.Manifest
import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.AppContainer
import com.ruskcoder.messages.imessage.PushService
import com.ruskcoder.messages.permissions.rememberBatteryExemption
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.navigation.MessagesNavGraph
import kotlinx.coroutines.delay

/**
 * How long the battery prompt waits behind the notification one.
 *
 * Long enough that the first dialog has been answered and dismissed rather than stacked under the
 * second — two system dialogs arriving together means the one underneath is dismissed blind.
 */
private const val SETUP_PROMPT_GAP_MS = 700L

/**
 * Decides whether to open the app or ask the user to connect iMessage first.
 *
 * Setup is offered rather than enforced. The app is a working messaging UI without an Apple
 * account, and refusing to open until someone has a Mac to hand would take away something that
 * already works; anyone who skips can start setup later from Settings.
 */
@Composable
fun SetupGate(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Null while the answer is still being read off disk, so neither branch flashes first.
    var registered by remember { mutableStateOf<Boolean?>(null) }
    var skipped by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        registered = runCatching { container.imessageService.isRegistered() }.getOrDefault(false)
    }

    // Starting the connection is what makes messages arrive, so it begins as soon as there is a
    // registration to connect with — not when a conversation is opened.
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    val battery = rememberBatteryExemption()

    LaunchedEffect(registered) {
        if (registered == true) {
            PushService.start(context)
            // Asked once there is actually something to be notified about, rather than on the very
            // first launch where the prompt would arrive with nothing to explain it.
            if (notificationPermission?.granted == false) notificationPermission.request()
            // And then the one that decides whether those notifications ever arrive on time. Second
            // rather than first, and after a beat: two system dialogs racing each other on first
            // launch means the one underneath is dismissed without being read. Permission to post a
            // notification is also the more legible ask of the two, so it goes first and this one
            // arrives against a screen the user has already said yes on.
            if (!battery.exempt) {
                delay(SETUP_PROMPT_GAP_MS)
                battery.request()
            }
        }
    }

    when {
        registered == null -> Loading()

        registered == true || skipped -> MessagesNavGraph(container = container)

        else -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                SetupScreen(
                    service = container.imessageService,
                    backupManager = container.backupManager,
                    scope = scope,
                    onFinished = { registered = true },
                )
            }
            TextButton(
                onClick = { skipped = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Skip for now")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}
