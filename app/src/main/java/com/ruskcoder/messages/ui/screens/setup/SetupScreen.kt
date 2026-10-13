package com.ruskcoder.messages.ui.screens.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.backup.BackupManager
import com.ruskcoder.messages.diagnostics.LogExport
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.imessage.describe
import com.ruskcoder.messages.ui.components.BackupAndRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.imessage_core.PhoneTarget

/**
 * The whole iMessage sign-in flow, shown when there is no working registration.
 *
 * Ordered the way the dependencies actually run: Apple will not accept a sign-in from a client that
 * cannot prove it is Apple hardware, so the code from a Mac has to come first — before there is any
 * point asking for an Apple ID.
 */
@Composable
fun SetupScreen(
    service: IMessageService,
    backupManager: BackupManager,
    scope: CoroutineScope,
    onFinished: () -> Unit,
) {
    val state = rememberSetupState(service, scope, onFinished)

    // Scanning a code and signing in are separate sessions in practice — people put the phone down
    // between them. If a previous run got as far as storing hardware, start from there rather than
    // sending them back to the Mac for a code they already used.
    LaunchedEffect(Unit) { state.resumeIfConfigured() }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var savingLogs by remember { mutableStateOf(false) }

    // The system picker, so the export lands wherever the user can actually get at it — Drive, a
    // messaging app's share sheet, the Downloads folder — rather than somewhere they then have to
    // be talked through finding.
    val chooseLogFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        savingLogs = true
        scope.launch {
            val outcome = runCatching {
                LogExport.writeTo(context, service.stateDir, uri, state.logSecrets())
            }
            savingLogs = false
            snackbar.showSnackbar(
                outcome.fold(
                    onSuccess = { "Saved logs — ${it.describe()}" },
                    onFailure = { "Could not save the logs: ${it.describe()}" },
                ),
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Box(Modifier.weight(1f)) {
                when (val step = state.step) {
                    is SetupState.Step.Hardware -> HardwareStep(state, backupManager)
                    is SetupState.Step.Confirm -> ConfirmStep(state, step)
                    is SetupState.Step.AppleId -> AppleIdStep(state, step)
                    is SetupState.Step.DeviceCode -> CodeStep(
                        state = state,
                        title = "Two-factor code",
                        detail = "Enter the code Apple just showed on your other devices.",
                        smsAction = "Send a code to a phone number instead",
                    )
                    is SetupState.Step.SmsCode -> CodeStep(
                        state = state,
                        title = "Verification code",
                        detail = "Enter the code Apple texted you.",
                        smsAction = "Resend the code",
                    )
                    is SetupState.Step.SmsChoice -> SmsChoiceStep(state, step)
                    is SetupState.Step.Registering -> BusyStep("Registering with iMessage…")
                }
            }

            state.error?.let { message ->
                ErrorBar(
                    message = message,
                    savingLogs = savingLogs,
                    onSaveLogs = { chooseLogFile.launch(LogExport.defaultFileName()) },
                    onDismiss = state::dismissError,
                )
            }
        }
    }
}

@Composable
private fun StepScaffold(
    title: String,
    detail: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/**
 * Where setup begins: a Mac's activation code, or a backup that already holds one.
 *
 * Restore sits on this step rather than behind it because it *replaces* it — a backup carries the
 * hardware identity and the Apple Account both, so someone moving to a new phone never needs the
 * Mac again. Offered second all the same: a first-time install has no backup to restore, and the
 * scanner is what that person came here for.
 */
@Composable
private fun HardwareStep(state: SetupState, backupManager: BackupManager) {
    var pasted by remember { mutableStateOf("") }

    BackupAndRestore(
        manager = backupManager,
        // Jump straight to confirming the identity that was just written, which is the same place
        // reopening the app would land — no reason to make them scan something they now have.
        onRestored = { outcome -> if (outcome.signInRestored) state.resumeIfConfigured() },
    ) { _, startRestore ->
        StepScaffold(
            title = "Connect a Mac",
            detail = "iMessage will only accept this device once it has been vouched for by an Apple " +
                "device. Open OpenBubbles on your Mac, choose Share Activation Code, and scan the code " +
                "it shows — or paste a registration relay code below.",
        ) {
            SetupChecklist()
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                QrScanner(onScanned = state::onScanned)
            }

            Spacer(Modifier.height(24.dp))
            Text("Or enter the code by hand", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Activation or relay code") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { state.onScanned(null, pasted) },
                enabled = pasted.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }

            Spacer(Modifier.height(24.dp))
            Text("Moving from another phone?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "A backup file from your old device carries both its Mac identity and your Apple " +
                    "Account, so there is no code to scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = startRestore,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from a backup")
            }
        }
    }
}

/**
 * What setup is going to ask for, before it asks for any of it.
 *
 * The step this sits on opens with a camera pointed at nothing and a request for a code from a Mac,
 * which reads as a dead end to anyone who does not already know why a Mac is involved. Saying the
 * whole of it up front — a Mac once, an Apple Account, and background access afterwards — is what
 * turns that into a short list somebody can decide to work through.
 */
@Composable
private fun SetupChecklist() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("What you'll need", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ChecklistRow(
            icon = Icons.Outlined.Laptop,
            title = "A Mac, just once",
            detail = "OpenBubbles on a Mac shares a code that vouches for this phone with Apple. " +
                "Once setup is done the Mac isn't needed again.",
        )
        ChecklistRow(
            icon = Icons.Outlined.AccountCircle,
            title = "Your Apple Account",
            detail = "The one you already use for iMessage on your iPhone, iPad or Mac.",
        )
        ChecklistRow(
            icon = Icons.Outlined.BatteryChargingFull,
            title = "Background access",
            detail = "After signing in, let the app run in the background so messages arrive while it's closed.",
        )
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(OpenBubblesUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) {
            Text("Get OpenBubbles")
        }
    }
}

@Composable
private fun ChecklistRow(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where OpenBubbles — the Mac half of setup — is published. */
private const val OpenBubblesUrl = "https://github.com/OpenBubbles"

@Composable
private fun ConfirmStep(state: SetupState, step: SetupState.Step.Confirm) {
    StepScaffold(
        title = "Is this your device?",
        detail = "This device will identify itself to Apple as the machine below.",
    ) {
        DetailRow("Model", step.device.name)
        DetailRow("Serial", step.device.serial)
        // Not labelled "macOS": a relay may be vouching for an iPhone.
        DetailRow("OS version", step.device.osVersion)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = state::acceptDevice,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        TextButton(onClick = state::rescan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan a different code")
        }
    }
}

@Composable
private fun AppleIdStep(state: SetupState, step: SetupState.Step.AppleId) {
    var appleId by remember { mutableStateOf(step.savedAccount.orEmpty()) }
    var password by remember { mutableStateOf("") }

    StepScaffold(
        title = "Apple Account",
        detail = "Sign in with the Apple Account you use for iMessage. Your password is sent " +
            "straight to Apple and is not stored anywhere else.",
    ) {
        if (step.savedAccount != null) {
            Button(
                onClick = state::signInSaved,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue as ${step.savedAccount}")
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = appleId,
            onValueChange = { appleId = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Apple Account") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
            ),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { state.signIn(appleId, password) },
            enabled = appleId.isNotBlank() && password.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in")
        }
    }
}

/**
 * Enter a six-digit code, with a way out for people who cannot read the one Apple sent.
 *
 * [smsAction] covers both directions of that: falling back to a text when the pushed code went to
 * a device that is not to hand, and asking for another text when the first never arrived. It is
 * one call either way — Apple restarts the SMS leg from scratch regardless.
 */
@Composable
private fun CodeStep(state: SetupState, title: String, detail: String, smsAction: String) {
    var code by remember(state.codeRound) { mutableStateOf("") }

    StepScaffold(title = title, detail = detail) {
        OutlinedTextField(
            value = code,
            onValueChange = { entered ->
                code = entered.filter(Char::isDigit).take(CODE_LENGTH)
                // Apple's codes are a fixed length, so there is nothing to confirm once it is
                // complete — submit rather than making the user reach for a button.
                if (code.length == CODE_LENGTH) state.submitCode(code)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Go,
            ),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { state.submitCode(code) },
            enabled = code.length == CODE_LENGTH && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Verify")
        }
        TextButton(
            onClick = state::requestSmsCode,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(smsAction)
        }
    }
}

@Composable
private fun SmsChoiceStep(state: SetupState, step: SetupState.Step.SmsChoice) {
    StepScaffold(
        title = "Where should we text you?",
        detail = "Apple can send a verification code to any of these trusted numbers.",
    ) {
        step.targets.forEach { target ->
            PhoneRow(target = target, enabled = !state.busy) { state.chooseSmsTarget(target) }
        }
    }
}

@Composable
private fun PhoneRow(target: PhoneTarget, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(target.number, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "•• ${target.lastTwoDigits}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyStep(label: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorBar(
    message: String,
    savingLogs: Boolean,
    onSaveLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSaveLogs, enabled = !savingLogs) {
                Text(if (savingLogs) "Saving…" else "Save logs")
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** Apple's verification codes are always six digits. */
private const val CODE_LENGTH = 6
