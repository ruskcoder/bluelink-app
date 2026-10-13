package com.ruskcoder.messages.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.ui.components.settings.SettingsBlock
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.screens.setup.QrScanner
import com.ruskcoder.messages.web.WebBridge
import com.ruskcoder.messages.web.WebPairing
import kotlinx.coroutines.launch
import java.net.URI

/** Settings → Web: link a browser by scanning its code, and see or remove the ones already linked. */
@Composable
fun WebDevicesScreen(bridge: WebBridge, onBack: () -> Unit) {
    val pairings by bridge.pairings.collectAsStateWithLifecycle()
    val states by bridge.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<WebPairing?>(null) }

    // Normally already running from the push service; this covers linking before iMessage is set up.
    LaunchedEffect(Unit) { bridge.start() }
    BackHandler(enabled = scanning) { scanning = false }

    if (scanning) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            QrScanner(
                onScanned = { _, text ->
                    scanning = false
                    linking = true
                    error = null
                    scope.launch {
                        runCatching { bridge.pair(text) }
                            .onFailure { error = it.message ?: "Couldn't link that browser" }
                        linking = false
                    }
                },
            )
            IconButton(onClick = { scanning = false }, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                "Point your phone at the code on the Messages web page",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp),
            )
        }
        return
    }

    SettingsPage(title = "Web", onBack = onBack) {
        SettingsGroup {
            SettingsBlock {
                Text(
                    "Use Messages from a browser on your computer. Your phone sends everything, so it needs to stay " +
                        "connected. Messages are end-to-end encrypted between this phone and the browser.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsRow(
                icon = Icons.Outlined.QrCodeScanner,
                title = if (linking) "Linking…" else "Link a browser",
                enabled = !linking,
                onClick = { scanning = true },
            )
        }

        error?.let { message ->
            SettingsGroup {
                SettingsBlock {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (pairings.isNotEmpty()) {
            SettingsGroup(title = "Linked browsers") {
                pairings.forEach { pairing ->
                    SettingsRow(
                        icon = Icons.Outlined.Computer,
                        title = pairing.name,
                        description = describe(states[pairing.room], pairing),
                        trailing = {
                            IconButton(onClick = { removing = pairing }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Unlink")
                            }
                        },
                    )
                }
            }
        }
    }

    removing?.let { pairing ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unlink ${pairing.name}?") },
            text = { Text("That browser will be signed out and can't use this phone until it's linked again.") },
            confirmButton = {
                TextButton(onClick = {
                    bridge.remove(pairing.room)
                    removing = null
                }) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private fun describe(state: WebBridge.LinkState?, pairing: WebPairing): String {
    val host = runCatching { URI(pairing.relay).host }.getOrNull() ?: pairing.relay
    val status = when (state) {
        WebBridge.LinkState.ACTIVE -> "Active now"
        WebBridge.LinkState.READY -> "Ready"
        WebBridge.LinkState.CONNECTING -> "Connecting…"
        WebBridge.LinkState.OFFLINE -> "Can't reach relay, retrying"
        null -> "Not connected"
    }
    return "$status · $host"
}
