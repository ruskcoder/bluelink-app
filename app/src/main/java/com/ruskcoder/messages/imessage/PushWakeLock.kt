package com.ruskcoder.messages.imessage

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU up for as long as it takes to file one inbound push.
 *
 * A foreground service keeps the process alive and unfrozen, but it does not keep the processor
 * awake — and, contrary to what this note used to claim, it does not exempt the app from Doze's
 * network firewall either. That one is the user's to grant; see [BatteryExemption], which asks for
 * it. An arriving packet wakes the device only long enough to hand the bytes to the
 * socket; everything that happens next — decrypting the message, resolving the conversation,
 * writing the rows, decoding the sender's photo, posting the notification — is ordinary work that
 * the device is free to fall back asleep in the middle of. When it does, the notification lands
 * whenever the phone next wakes for its own reasons, which is why a message can arrive silently and
 * then appear all at once minutes later with several others.
 *
 * Reference-counted, because the reader can be part-way through one event while a retry or a
 * download for an earlier one is still running: releasing on the first completion would drop the
 * lock out from under the work still in flight. Timed as a backstop so a lock can never be leaked
 * by a coroutine that dies between acquire and release — a held wake lock is a flat battery.
 */
class PushWakeLock(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    private val lock: PowerManager.WakeLock? = runCatching {
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(true) }
    }.getOrNull()

    /** Runs [block] with the CPU held awake, releasing whatever happens inside it. */
    suspend fun <T> holding(block: suspend () -> T): T {
        runCatching { lock?.acquire(TIMEOUT_MS) }
        try {
            return block()
        } finally {
            // `isHeld` is checked because the timeout above may already have released it, and
            // releasing an unheld lock throws.
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private companion object {
        const val TAG = "messages:push"

        /**
         * The ceiling on one event's processing, not an expectation of it. Long enough to cover a
         * slow write behind a large sync, short enough that a leaked lock costs minutes of standby
         * rather than a day of it.
         */
        const val TIMEOUT_MS = 60_000L
    }
}
