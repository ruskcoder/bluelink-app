package com.ruskcoder.messages.util

import android.content.Context

/**
 * How tall the soft keyboard was the last time one stood on this device, in dp.
 *
 * The attachment card takes the keyboard's slot rather than stacking with it, so it has to be
 * exactly as tall as the keyboard is — otherwise the send bar jumps as one replaces the other. The
 * screen can only measure that while a keyboard is actually up, and the first thing many people do
 * in a conversation is press "+" without ever having typed, which left the card falling back to a
 * guess that was rarely the right height.
 *
 * Kept in its own preferences file rather than in `Settings`: this is a measurement of the device
 * and its keyboard, not a choice anybody made, so it has no business in the settings blob or in a
 * backup restored onto a different phone. Written from the UI thread and read on the very first
 * frame, which is why it is `SharedPreferences` and not DataStore — the whole point is to have an
 * answer before the first composition, not one frame later.
 */
object KeyboardHeightStore {
    private const val FILE = "keyboard_metrics"
    private const val HEIGHT_DP = "height_dp"

    /** The remembered height, or 0 when no keyboard has ever been seen on this install. */
    fun read(context: Context): Float =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(HEIGHT_DP, 0f)

    fun write(context: Context, dp: Float) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(HEIGHT_DP, dp)
            .apply()
    }
}
