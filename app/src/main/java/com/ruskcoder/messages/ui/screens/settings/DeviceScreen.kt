package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.imessage.describe
import com.ruskcoder.messages.ui.components.QrCode
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import uniffi.imessage_core.DeviceDetails
import uniffi.imessage_core.HwConfig

/**
 * The machine this device claims to be, and the means to pass that claim on.
 *
 * Apple will not register a client that cannot produce validation data from genuine Apple hardware,
 * so every install is standing behind some Mac — but until now nothing in the app would tell you
 * *which*, once setup was over. This is that page: the whole stored record, and OpenBubbles' own
 * "share activation code" alongside it, since an identity that can be read once can be handed on.
 *
 * The code is generated on demand rather than shown on arrival. It is the credential that lets
 * another device register as this Mac, and a page that prints one every time it is opened leaves it
 * on screen for anyone who happens to be looking.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun DeviceScreen(imessageService: IMessageService, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var config by remember { mutableStateOf<HwConfig?>(null) }
    var details by remember { mutableStateOf<DeviceDetails?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var shared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val hardware = imessageService.hardware()
            config = hardware
            details = hardware?.details()
        } catch (e: Exception) {
            error = e.describe()
        } finally {
            loading = false
        }
    }

    SettingsPage(title = "Device", onBack = onBack) {
        val current = details
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            current == null -> Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No device identity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    error ?: "This install has not been paired with a Mac yet. Settings → Account → iMessage starts setup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                DeviceHeader(current)

                SettingsGroup(title = "Hardware") {
                    DetailRow("Model", current.name)
                    current.productName?.let { DetailRow("Product", it) }
                    DetailRow("Serial number", current.serial)
                    current.boardId?.let { DetailRow("Board ID", it) }
                    current.mlb?.let { DetailRow("Logic board (MLB)", it) }
                    current.rom?.let { DetailRow("ROM", it) }
                    current.macAddress?.let { DetailRow("MAC address", it) }
                }

                SettingsGroup(title = "System") {
                    DetailRow("OS version", current.osVersion)
                    current.osBuild?.let { DetailRow("Build", it) }
                    current.platformUuid?.let { DetailRow("Platform UUID", it) }
                    current.rootDiskUuid?.let { DetailRow("Root disk UUID", it) }
                    current.deviceId?.let { DetailRow("Device ID", it) }
                    current.protocolVersion?.let { DetailRow("Protocol version", it.toString()) }
                }

                SettingsGroup(
                    title = "Identifies to Apple as",
                ) {
                    current.icloudUserAgent?.let { DetailRow("iCloud user agent", it) }
                    current.aoskitVersion?.let { DetailRow("AOSKit", it) }
                    current.relayHost?.let { DetailRow("Relay host", it) }
                }

                ShareSection(
                    exportable = current.exportable,
                    payload = payload,
                    shared = shared,
                    onGenerate = {
                        scope.launch {
                            try {
                                payload = config?.toActivationPayload(shared)
                                error = null
                            } catch (e: Exception) {
                                error = e.describe()
                            }
                        }
                    },
                    onToggleShared = { shared = it; payload = null },
                    onCopy = { code -> clipboard.setText(AnnotatedString(code)) },
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceHeader(details: DeviceDetails) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (details.relayHost != null) Icons.Outlined.Dns else Icons.Rounded.LaptopMac,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            details.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            if (details.relayHost != null) "Vouched for by a relay" else "macOS ${details.osVersion}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One field of the record.
 *
 * Tappable, and the tap copies: a platform UUID is not something anyone is going to retype off a
 * screen, and every one of these lines exists to be pasted somewhere else.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    SettingsRow(
        title = label,
        description = value,
        onClick = { clipboard.setText(AnnotatedString(value)) },
    )
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun ShareSection(
    exportable: Boolean,
    payload: ByteArray?,
    shared: Boolean,
    onGenerate: () -> Unit,
    onToggleShared: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    SettingsGroup(
        title = "Share this identity",
    ) {
        if (!exportable) {
            SettingsRow(title = "Not exportable")
            return@SettingsGroup
        }

        SettingsRow(
            title = if (shared) "Reusable code" else "Single-use code",
            icon = Icons.Outlined.Memory,
        )
        // A row of its own under the label: beside it, the button squeezed the title onto two lines.
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            OutlinedButton(onClick = { onToggleShared(!shared) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (shared) "Make single-use" else "Make reusable")
            }
        }

        // Held in a local so the null check narrows it: `payload` is a parameter, and the compiler
        // will not carry a smart cast from it into the block below.
        val bytes = payload
        if (bytes == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Generate code")
                }
            }
            return@SettingsGroup
        }
        val code = Base64.encode(bytes)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            QrCode(payload = bytes, size = 260.dp)

            Text(
                code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onCopy(code) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Copy code")
                }
                OutlinedButton(onClick = onGenerate) { Text("Regenerate") }
            }
        }
    }
}
