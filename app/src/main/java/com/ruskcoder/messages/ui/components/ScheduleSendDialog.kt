package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How a queued message's moment is written out, wherever one is shown. */
internal val ScheduledTimeLabel = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())

/**
 * When to send a message that has been written but not sent yet.
 *
 * Four presets and a custom picker. The presets are the times people actually mean — later today,
 * this evening, tomorrow morning, next week — each one showing the clock time it resolves to, since
 * "tomorrow morning" is a promise about a specific minute and hiding which minute helps nobody.
 *
 * Anything already in the past is dropped from the list rather than shown greyed: at nine at night
 * "this evening" has been and gone, and offering it only invites the question of what it would do.
 */
@Composable
fun ScheduleSendDialog(
    /** What is in the composer, so the dialog can say what it is about to queue. */
    text: String,
    /** How many are already waiting in this conversation; none hides the way to them. */
    pending: Int,
    onDismiss: () -> Unit,
    onSchedule: (Long) -> Unit,
    onManage: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        ScheduleTimeDialog(title = "Send at", onDismiss = onDismiss, onPick = onSchedule)
        return
    }

    val presets = remember { presetsFrom(System.currentTimeMillis()) }
    AppDialog(
        title = "Send later",
        message = text.trim().takeIf { it.isNotEmpty() }?.let { "“${it.take(90)}”" },
        onDismiss = onDismiss,
        buttons = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        Column {
            presets.forEach { preset ->
                ScheduleRow(
                    label = preset.label,
                    detail = ScheduledTimeLabel.format(Date(preset.at)),
                    onClick = { onSchedule(preset.at) },
                )
            }
            ScheduleRow(label = "Pick a date and time", detail = null, onClick = { picking = true })

            // The way through to what is already queued. Below a rule, because it is not a fifth
            // way to schedule *this* message: it is about the other ones.
            if (pending > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    color = Color.White.copy(alpha = 0.12f),
                )
                ScheduleRow(
                    label = if (pending == 1) "1 message waiting" else "$pending messages waiting",
                    detail = "Change the time, or cancel",
                    icon = Icons.Rounded.Schedule,
                    onClick = onManage,
                )
            }
        }
    }
}

/** Which half of the custom flow is open. */
private enum class PickStep { DATE, TIME }

/**
 * The date-then-time flow, on its own so both the send dialog and the list of queued messages can
 * open it — scheduling a new message and moving an existing one are the same question asked twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTimeDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    /** Where the pickers open; now, for a message that has no time yet. */
    initialAt: Long = System.currentTimeMillis(),
) {
    var step by remember { mutableStateOf(PickStep.DATE) }
    var chosenDate by remember { mutableStateOf<Long?>(null) }
    val start = remember(initialAt) { Calendar.getInstance().apply { timeInMillis = initialAt } }

    when (step) {
        PickStep.DATE -> {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = utcDayOf(initialAt))
            AppDialog(
                title = title,
                onDismiss = onDismiss,
                buttons = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = dateState.selectedDateMillis != null,
                        onClick = { chosenDate = dateState.selectedDateMillis; step = PickStep.TIME },
                    ) { Text("Next") }
                },
            ) {
                // Transparent container: the pickers are Material surfaces by default, and a solid
                // block inside a frosted dialog is the one thing on it the screen behind does not
                // show through.
                DatePicker(
                    state = dateState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        PickStep.TIME -> {
            val timeState = rememberTimePickerState(
                initialHour = start.get(Calendar.HOUR_OF_DAY),
                initialMinute = start.get(Calendar.MINUTE),
            )
            AppDialog(
                title = title,
                onDismiss = onDismiss,
                buttons = {
                    TextButton(onClick = { step = PickStep.DATE }) { Text("Back") }
                    TextButton(
                        onClick = {
                            val at = combine(chosenDate ?: utcDayOf(initialAt), timeState.hour, timeState.minute)
                            // A time already gone is the one thing the picker can produce that this
                            // cannot honour, so it goes out now rather than never.
                            onPick(maxOf(at, System.currentTimeMillis() + 1_000))
                        },
                    ) { Text("Done") }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TimePicker(
                        state = timeState,
                        colors = TimePickerDefaults.colors(clockDialColor = ControlTint),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    label: String,
    detail: String?,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class Preset(val label: String, val at: Long)

/**
 * The four offers, given the current time, with anything already past left out.
 *
 * "In an hour" is a duration rather than a clock time, because it is the one of these that means
 * "not right this second" rather than a point in the day.
 */
private fun presetsFrom(now: Long): List<Preset> {
    val inAnHour = now + 60L * 60 * 1000
    return listOfNotNull(
        Preset("In an hour", inAnHour),
        Preset("This evening", atClock(now, hour = 20)).takeIf { it.at > inAnHour },
        Preset("Tomorrow morning", atClock(now, hour = 8, daysAhead = 1)),
        Preset("Next week", atClock(now, hour = 8, daysAhead = 7)),
    )
}

/** [hour] o'clock, [daysAhead] days from the day [from] falls on. */
private fun atClock(from: Long, hour: Int, daysAhead: Int = 0): Long =
    Calendar.getInstance().apply {
        timeInMillis = from
        add(Calendar.DAY_OF_YEAR, daysAhead)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Midnight UTC on the day [at] falls on locally, which is the form the date picker selects in.
 *
 * The picker is choosing a *day*, not an instant, and does its arithmetic in UTC. Handed a local
 * midnight it lands on the day before for anyone west of Greenwich.
 */
private fun utcDayOf(at: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = at }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** A day from the date picker plus an hour and minute from the time picker, in the local zone. */
private fun combine(dateMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMillis }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
