package com.ruskcoder.messages.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether Android will let this app use the network while the phone is asleep.
 *
 * This is not the same question as "is the push service running", and the difference is the whole
 * reason this file exists. A foreground service exempts an app from the *background execution*
 * limits — it is what keeps the process alive and unfrozen — but it does nothing about Doze's
 * network firewall. An app that is not on the battery-optimisation allowlist is put in the platform's
 * `dozable` chain, and once the device idles its packets are simply dropped: the process is up, the
 * socket is still `ESTABLISHED`, and nothing arrives. Apple queues the messages, and the moment the
 * phone wakes they all land at once.
 *
 * That is why every other app that holds its own long-lived push socket — OpenBubbles and
 * BlueBubbles among them — asks for this. It is the one permission that actually decides whether
 * messages arrive on time.
 *
 * The exemption is granted by the user, per install, and is **cleared by an uninstall/reinstall**,
 * which is why it is offered in three places rather than once at setup: a fresh install of a debug
 * build silently loses it, and nothing about the app looks wrong afterwards.
 */
object BatteryExemption {

    /** Whether the app is currently allowed to run unrestricted while the device is idle. */
    fun isExempt(context: Context): Boolean {
        // Doze arrived in M. Below it there is no allowlist and nothing to ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system's own exemption prompt.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` puts up a one-tap dialog, which is the whole
     * point of asking here rather than sending someone into Settings to find a toggle three screens
     * down. Some builds — and Play-restricted ones — refuse the direct intent, so a failure falls
     * back to the settings list, where the app can still be allowed by hand.
     */
    fun request(context: Context) {
        if (isExempt(context)) return
        @Suppress("BatteryLife") // Deliberate: a message client is exactly the case the flag is for.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
            .recoverCatching { context.startActivity(appInfo) }
    }
}

/**
 * Live exemption state for a screen, re-read every time the app comes back to the foreground.
 *
 * Re-read on resume because the answer changes *outside* the app: the prompt is a system dialog, and
 * the settings screens the fallbacks open are another process entirely. Read once at composition, a
 * banner would still be sitting there after the user had just granted the thing it was asking for.
 */
@Composable
fun rememberBatteryExemption(): BatteryExemptionState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BatteryExemption.isExempt(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return BatteryExemptionState(exempt) { BatteryExemption.request(context) }
}

/** What a screen needs to know, and the one thing it can do about it. */
class BatteryExemptionState(val exempt: Boolean, val request: () -> Unit)
