package com.ruskcoder.messages.ui.screens.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import com.ruskcoder.messages.diagnostics.LogRedactor
import com.ruskcoder.messages.imessage.ActivationCodeException
import com.ruskcoder.messages.imessage.ActivationCodes
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.imessage.describe
import uniffi.imessage_core.HwConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.imessage_core.DeviceInfo
import uniffi.imessage_core.LoginStep
import uniffi.imessage_core.PhoneTarget

/**
 * Drives setup: hardware identity, then Apple ID, then whatever verification Apple asks for.
 *
 * A plain state holder rather than a `ViewModel`, matching the rest of this app — screens take
 * their dependencies directly and hoist their state. Setup is a single flow that lives and dies
 * with its screen, so there is nothing here worth surviving a configuration change that reopening
 * the screen would not rebuild anyway.
 */
class SetupState(
    private val service: IMessageService,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
) {
    sealed interface Step {
        /** Scan or paste the code from a Mac. */
        data object Hardware : Step

        /** Show what was scanned so the user can confirm it is the right machine. */
        data class Confirm(val device: DeviceInfo) : Step

        data class AppleId(val savedAccount: String?) : Step

        /** Apple pushed a code to the account's trusted devices. */
        data object DeviceCode : Step

        data class SmsChoice(val targets: List<PhoneTarget>) : Step

        data object SmsCode : Step

        /** Registering with IDS, which is what makes the account able to send. */
        data object Registering : Step
    }

    var step by mutableStateOf<Step>(Step.Hardware)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** True once a code has been accepted, so the scanner can stop reporting. */
    var scanned by mutableStateOf(false)
        private set

    /**
     * Bumped whenever a fresh code is sent, so the entry field can clear itself.
     *
     * Needed because resending keeps the same [Step], and half a stale code left in the box is
     * worse than an empty one — the field is capped at six digits, so the user cannot type over it.
     */
    var codeRound by mutableStateOf(0)
        private set

    /**
     * The identity from the code just scanned, held until the connection is opened with it.
     *
     * Kept in memory rather than read back from disk between the two steps: what setup writes
     * before connecting has no push token in it, and the connection is what completes it.
     */
    private var pendingHardware: HwConfig? = null

    /**
     * What the last sign-in attempt was made with, held only so a log export can delete it.
     *
     * No pattern can pick an arbitrary password out of a log line, so the only way to promise it is
     * not in a file the user is about to send someone is to know the string and remove every copy.
     * Kept in memory for as long as the failure is on screen and dropped the moment registration
     * succeeds — by which point the sign-in that could have logged it is over.
     */
    private var attempted = LogRedactor.Secrets()

    /** The values a log export has to scrub. See [attempted]. */
    fun logSecrets(): LogRedactor.Secrets = attempted

    fun dismissError() {
        error = null
    }

    /**
     * Start at the Apple ID step when hardware is already stored from a previous run.
     *
     * Deliberately does *not* set [pendingHardware]. That field means "a code was just scanned", and
     * connecting with it mints a fresh NGM identity — which for an install that already has one
     * would invalidate the registration built on it. Stored hardware goes through the restoring
     * path instead, keeping the identity it was registered with.
     */
    fun resumeIfConfigured() {
        scope.launch {
            val hardware = runCatching { service.hardware() }
                .onFailure { Log.w(TAG, "could not read stored hardware", it) }
                .getOrNull() ?: return@launch
            step = Step.Confirm(hardware.deviceInfo())
        }
    }

    fun onScanned(rawBytes: ByteArray?, text: String) {
        if (scanned || busy) return
        scanned = true
        scope.launch {
            busy = true
            try {
                val input = ActivationCodes.resolve(rawBytes, text)
                val config = service.applyActivation(input)
                pendingHardware = config
                step = Step.Confirm(config.deviceInfo())
            } catch (e: ActivationCodeException) {
                Log.w(TAG, "activation code rejected", e)
                error = e.describe()
                scanned = false
            } catch (e: Exception) {
                Log.e(TAG, "could not apply activation code", e)
                error = e.describe()
                scanned = false
            } finally {
                busy = false
            }
        }
    }

    /** Accept the confirmed device and move on to signing in. */
    fun acceptDevice() {
        scope.launch {
            busy = true
            try {
                step = Step.AppleId(service.savedAppleId())
            } finally {
                busy = false
            }
        }
    }

    /** Go back to the scanner, e.g. the confirmed machine was not the intended one. */
    fun rescan() {
        scanned = false
        error = null
        step = Step.Hardware
    }

    fun signIn(appleId: String, password: String) = runLogin {
        attempted = LogRedactor.Secrets(appleId = appleId, password = password)
        service.session(pendingHardware).login(appleId, password)
    }

    fun signInSaved() = runLogin {
        // No password is typed on this path — the Rust side unseals the stored one — so only the
        // Apple ID can be scrubbed literally. The redactor's field rules cover the rest.
        attempted = LogRedactor.Secrets(appleId = (step as? Step.AppleId)?.savedAccount)
        service.session(pendingHardware).loginSaved()
    }

    fun submitCode(code: String) = runLogin { service.session().submitCode(code) }

    fun chooseSmsTarget(target: PhoneTarget) = runLogin {
        service.session().chooseSmsTarget(target.id)
    }

    /**
     * Ask Apple for a text instead of the code it pushed to the account's trusted devices.
     *
     * Also the resend, once a text has been sent: Apple treats both as the same request, and the
     * Rust side re-runs the choice, so a second tap can land on a different number.
     */
    fun requestSmsCode() = runLogin {
        codeRound++
        service.session().requestSmsCode()
    }

    /**
     * Run one leg of the login negotiation and move to whatever it asks for next.
     *
     * The Rust side already resolves every state it can answer on its own, so anything that comes
     * back here is a step the user has to act on — or the end of the flow.
     */
    private fun runLogin(action: suspend () -> LoginStep) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                when (val next = action()) {
                    is LoginStep.NeedsDeviceCode -> step = Step.DeviceCode
                    is LoginStep.NeedsSmsChoice -> step = Step.SmsChoice(next.targets)
                    is LoginStep.NeedsSmsCode -> step = Step.SmsCode
                    is LoginStep.NeedsExtraStep -> error = next.detail
                    is LoginStep.LoggedIn -> register()
                }
            } catch (e: Exception) {
                Log.e(TAG, "sign in failed", e)
                error = e.describe()
            } finally {
                busy = false
            }
        }
    }

    private suspend fun register() {
        step = Step.Registering
        try {
            val handles = service.session().register()
            Log.i(TAG, "registered with handles: $handles")
            service.onRegistered(handles)
            // Setup is over, so nothing is left that could need the credentials scrubbed out of it.
            attempted = LogRedactor.Secrets()
            onFinished()
        } catch (e: Exception) {
            Log.e(TAG, "registration failed", e)
            error = e.describe()
            step = Step.AppleId(service.savedAppleId())
        }
    }
}

private const val TAG = "IMessageSetup"

@Composable
fun rememberSetupState(
    service: IMessageService,
    scope: CoroutineScope,
    onFinished: () -> Unit,
): SetupState = remember(service) { SetupState(service, scope, onFinished) }
