package com.ruskcoder.messages.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DAY_MS = 24L * 60 * 60 * 1000

/** "5:38 PM" — the timestamp printed inside a message bubble. */
fun formatClock(timestampMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

/** Midnight of the day [timestampMs] falls on, in the device's own timezone. */
fun startOfDay(timestampMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** "0:06", "1:32" — a voice message's running or total length. */
fun formatDuration(totalSeconds: Float): String {
    val clamped = totalSeconds.coerceAtLeast(0f).toInt()
    val minutes = clamped / 60
    val seconds = clamped % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Whether two instants land on the same calendar day, in the device's own timezone. */
fun isSameDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

private fun yearOf(timestampMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.YEAR)

/** Whole calendar days between the two, so "yesterday" means the day before and not 24 hours. */
private fun daysBetween(timestampMs: Long, now: Long): Int =
    Math.round((startOfDay(now) - startOfDay(timestampMs)) / DAY_MS.toDouble()).toInt()

private fun pattern(pattern: String, timestampMs: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))

/*
 * The headers above a section of the conversation — a run of messages with no long silence in it.
 *
 * Each style says when in its own idiom, and the three genuinely disagree about how much to say:
 * Google prints the clock alone for anything sent today and names the day only when it has to,
 * Apple always names the day, and WhatsApp's pill wants the shortest thing that still reads.
 *
 * Named days for the last week the way every messaging app does, then a date — a bare "12/03/25"
 * for something three days old reads as a filing reference rather than as a Tuesday. The year is
 * only spelled out once the message is old enough for it to be in question.
 */

/**
 * Apple's, in two parts so the day can carry the weight and the time can't — the same split the
 * delivery receipt under a bubble uses.
 */
data class AppleSectionHeader(val day: String, val time: String)

fun formatAppleSection(timestampMs: Long, now: Long = System.currentTimeMillis()): AppleSectionHeader {
    val clock = formatClock(timestampMs)
    val days = daysBetween(timestampMs, now)
    return when {
        days <= 0 -> AppleSectionHeader("Today", clock)
        days == 1 -> AppleSectionHeader("Yesterday", clock)
        days < 7 -> AppleSectionHeader(pattern("EEEE", timestampMs), clock)
        // Once the day is a date, the two need joining: "Fri, Aug 21 3:42 PM" runs one number
        // straight into the next, which is the reason iMessage says "at" between them.
        yearOf(timestampMs) == yearOf(now) -> AppleSectionHeader(pattern("EEE, MMM d", timestampMs), "at $clock")
        else -> AppleSectionHeader(pattern("EEE, MMM d, yyyy", timestampMs), "at $clock")
    }
}

/** Google's: the clock on its own for today, with the whole day in front of it for anything older. */
fun formatGoogleSection(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val clock = formatClock(timestampMs)
    if (daysBetween(timestampMs, now) <= 0) return clock
    val day =
        if (yearOf(timestampMs) == yearOf(now)) pattern("EEEE, MMM d", timestampMs)
        else pattern("EEEE, MMM d, yyyy", timestampMs)
    return "$day · $clock"
}

/** WhatsApp's, for the pill it goes in: the shortest name the day has, then the clock. */
fun formatWhatsAppSection(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val clock = formatClock(timestampMs)
    val days = daysBetween(timestampMs, now)
    val day = when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> pattern("EEEE", timestampMs)
        yearOf(timestampMs) == yearOf(now) -> pattern("MMM d", timestampMs)
        else -> pattern("MMM d, yyyy", timestampMs)
    }
    return "$day $clock"
}

/**
 * The trailing label in the chat list: a clock for today, "Yesterday", a weekday name inside
 * the last week, then a date. Mirrors WhatsApp's own thresholds.
 */
fun formatChatTime(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val days = daysBetween(timestampMs, now)
    if (days <= 0) return formatClock(timestampMs)
    if (days == 1) return "Yesterday"
    if (days < 7) return pattern("EEEE", timestampMs)
    return pattern("dd/MM/yy", timestampMs)
}

/**
 * When a message was read, for the line under it: "12:34 PM" today, "Yesterday 12:34 PM", a
 * weekday inside the last week, then a date. The clock stays on every one of them — "Read
 * Yesterday" alone says less than the receipt actually knows.
 */
fun formatReadTime(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val clock = formatClock(timestampMs)
    val days = daysBetween(timestampMs, now)
    return when {
        days <= 0 -> clock
        days == 1 -> "Yesterday $clock"
        days < 7 -> "${pattern("EEEE", timestampMs)} $clock"
        yearOf(timestampMs) == yearOf(now) -> "${pattern("MMM d", timestampMs)} at $clock"
        else -> "${pattern("MMM d, yyyy", timestampMs)} at $clock"
    }
}
