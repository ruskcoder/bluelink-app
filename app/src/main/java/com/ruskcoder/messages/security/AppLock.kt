package com.ruskcoder.messages.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ruskcoder.messages.data.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the app behind the phone's own lock — fingerprint, face, or the device PIN — once it has
 * been away for longer than the chosen timeout.
 *
 * The phone's credential rather than a PIN of the app's own: it is one fewer secret to forget, the
 * system prompt is the one people already trust with their banking apps, and the app never sees or
 * stores anything to do with it.
 *
 * "Away" means every one of the app's activities has stopped, counted across all of them — moving
 * from the main screen to the share sheet or a conversation bubble is not leaving the app.
 */
class AppLock(private val settings: () -> Settings) : Application.ActivityLifecycleCallbacks {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var started = 0
    private var backgroundedAt = 0L

    /** False until the app has come to the front once, so a cold start is always a locked start. */
    private var shownOnce = false

    override fun onActivityStarted(activity: Activity) {
        if (started++ > 0) return
        val current = settings()
        if (!current.appLock) {
            _locked.value = false
        } else if (!shownOnce || System.currentTimeMillis() - backgroundedAt >= current.appLockTimeoutSeconds * 1000L) {
            _locked.value = true
        }
        shownOnce = true
    }

    override fun onActivityStopped(activity: Activity) {
        if (--started > 0) return
        // Only the moment it was *left* unlocked starts the clock. Leaving while still locked keeps
        // it locked however quickly the app comes back.
        if (!_locked.value) backgroundedAt = System.currentTimeMillis()
    }

    override fun onActivityResumed(activity: Activity) {
        // Recents shows a picture of the last screen, which is a picture of somebody's messages.
        // With the lock on that picture is blanked, and screenshots of the app go with it.
        val secure = settings().appLock
        if (secure) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun unlock() {
        _locked.value = false
    }

    /** Turning the lock on from Settings should not lock the very screen it was turned on from. */
    fun onLockEnabled() {
        shownOnce = true
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        /**
         * The strongest combination this API level lets be mixed with the device credential.
         *
         * Before Android 11 a strong biometric cannot be offered alongside the PIN, and the
         * credential cannot be offered on its own — so older phones get "any biometric, or the PIN".
         */
        private val authenticators: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            } else {
                BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            }

        /** Whether the phone has any lock set up to put the app behind. */
        fun canAuthenticate(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

        /** Shows the system unlock prompt; [onUnlocked] runs only if it succeeds. */
        fun authenticate(activity: FragmentActivity, onUnlocked: () -> Unit, onUnavailable: () -> Unit = {}) {
            if (!canAuthenticate(activity)) {
                // No lock on the phone any more — removed after the app lock was switched on. There
                // is nothing to check against, and locking someone out of their own messages over it
                // would be worse than letting them in.
                onUnavailable()
                return
            }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Messages")
                    .setSubtitle("Use your fingerprint, face or screen lock")
                    .setAllowedAuthenticators(authenticators)
                    .build(),
            )
        }
    }
}
