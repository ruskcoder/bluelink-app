package com.ruskcoder.messages.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects everything this app has logged into one file the user picks, scrubbed on the way out.
 *
 * A failed sign-in is close to undebuggable from the outside: the message on screen is one line at
 * the end of a conversation between this device, Apple, and an anisette server, and the person
 * hitting it usually cannot get at the rest. This is how they hand over the rest.
 *
 * Two sources, because neither is complete on its own:
 *
 *  - **logcat**, which holds this process's Kotlin logging *and* the Rust core's, since the core
 *    installs an `AndroidLogger` alongside its file logger. Restricted to this process — an
 *    unprivileged app only ever gets its own lines back anyway, but the file is about to be sent to
 *    someone, so it is worth being certain rather than trusting logd's filtering.
 *  - **the core's own log files** under the state directory, which the Rust side writes at debug
 *    level and rotates. These outlive the process, so they carry the earlier runs that logcat's
 *    ring buffer has already dropped.
 *
 * Everything written goes through [LogRedactor] first. Nothing is staged on disk in between: the
 * only copy of the unredacted text is the one already on the device.
 */
object LogExport {

    private const val TAG = "LogExport"

    /**
     * How much of any one log file to take, from the end.
     *
     * The core rotates at 10 MB and keeps one old file, so an untrimmed export could be 20 MB of
     * text that then has to be held in memory to be redacted. The tail is the part that describes
     * the failure being reported.
     */
    private const val MAX_SECTION_BYTES = 4L * 1024 * 1024

    private const val LOGCAT_TIMEOUT_SECONDS = 20L

    /** What the export took out, for telling the user it worked. */
    data class Summary(
        val characters: Long,
        val replacements: Map<String, Int>,
    ) {
        val redactions: Int get() = replacements.values.sum()

        fun describe(): String {
            val size = when {
                characters >= 1024 * 1024 -> "%.1f MB".format(Locale.US, characters / 1024.0 / 1024.0)
                else -> "${characters / 1024} KB"
            }
            return if (redactions == 0) size else "$size, $redactions items redacted"
        }
    }

    /** A name with the timestamp already in it, so repeated exports do not overwrite each other. */
    fun defaultFileName(): String = "messages-logs-${stamp("yyyyMMdd-HHmmss")}.txt"

    /**
     * Write every log this app has into [target], with [secrets] and other personal details removed.
     *
     * Sections are redacted and written one at a time so peak memory is one section rather than the
     * whole export.
     *
     * @throws IllegalStateException if redaction cannot account for a secret, in which case nothing
     * useful has been written — see [LogRedactor.redact].
     */
    suspend fun writeTo(
        context: Context,
        stateDir: String,
        target: Uri,
        secrets: LogRedactor.Secrets,
    ): Summary = withContext(Dispatchers.IO) {
        val sections = buildList {
            add(Section("about this export", header(context)))
            add(Section("logcat (this process, pid ${Process.myPid()})", logcat()))
            addAll(coreLogs(File(stateDir, "logs")))
        }

        val counts = LinkedHashMap<String, Int>()
        var characters = 0L

        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Android would not open the file you picked for writing")
        stream.bufferedWriter().use { writer ->
            for (section in sections) {
                val redacted = LogRedactor.redact(section.body, secrets)
                for ((label, hits) in redacted.replacements) {
                    counts[label] = (counts[label] ?: 0) + hits
                }
                writer.write("\n===== ${section.title} =====\n\n")
                writer.write(redacted.text)
                writer.write("\n")
                characters += redacted.text.length
            }
            writer.write(footer(counts))
        }

        Summary(characters, counts)
    }

    private data class Section(val title: String, val body: String)

    private fun header(context: Context): String = buildString {
        appendLine("Messages diagnostic log")
        appendLine("Taken:   ${stamp("yyyy-MM-dd HH:mm:ss")} UTC")
        appendLine("App:     ${appVersion(context)}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.SUPPORTED_ABIS.firstOrNull()})")
        appendLine()
        appendLine("Personal information was replaced before this file was written: the Apple ID and")
        appendLine("password from the sign-in attempt, plus e-mail addresses, phone numbers, IP")
        appendLine("addresses, and the header and plist fields that carry credentials or device")
        appendLine("identity. Each one reads as <username>, <password>, <email> and so on below.")
        appendLine()
        appendLine("What that cannot cover is free text. A message body, a contact's name, or a")
        appendLine("nickname looks like any other string to a pattern, so read the file before you")
        appendLine("send it anywhere.")
    }

    private fun footer(counts: Map<String, Int>): String = buildString {
        appendLine()
        appendLine("===== redactions =====")
        appendLine()
        if (counts.isEmpty()) {
            appendLine("Nothing matched — no personal information was found to replace.")
        } else {
            for ((label, hits) in counts.entries.sortedByDescending { it.value }) {
                appendLine("$label  ×$hits")
            }
        }
    }

    /**
     * This process's logcat.
     *
     * `--pid` arrived in API 24, which is exactly this app's minimum, so a device that does not
     * understand it should not exist — but a vendor logcat that rejects the flag would otherwise
     * produce an empty export, so fall back to the plain dump and drop foreign lines by hand.
     */
    private fun logcat(): String {
        val pid = Process.myPid()
        run(listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"))
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val everything = run(listOf("logcat", "-d", "-v", "threadtime"))
            ?: return "logcat could not be read on this device."
        return everything.lineSequence().filter { it.isFrom(pid) }.joinToString("\n")
    }

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        output
    }.onFailure { Log.w(TAG, "could not run ${command.first()}", it) }.getOrNull()

    /**
     * Whether a `threadtime` line came from [pid].
     *
     * The format is `MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message`, and every line of a
     * multi-line message carries the prefix, so matching the third field is enough.
     */
    private fun String.isFrom(pid: Int): Boolean =
        trim().split(Regex("\\s+")).getOrNull(2)?.toIntOrNull() == pid

    /** The Rust core's own log files, oldest first so the export reads forwards in time. */
    private fun coreLogs(dir: File): List<Section> {
        val files = dir.listFiles()?.filter { it.isFile } ?: return listOf(
            Section("core logs", "No core log files at ${dir.absolutePath}."),
        )
        return files.sortedBy { it.lastModified() }.map { file ->
            Section("core log: ${file.name}", file.tail(MAX_SECTION_BYTES))
        }
    }

    /** The last [maxBytes] of a file, starting at a line boundary so the first entry is whole. */
    private fun File.tail(maxBytes: Long): String = runCatching {
        RandomAccessFile(this, "r").use { handle ->
            val skipped = (handle.length() - maxBytes).coerceAtLeast(0)
            handle.seek(skipped)
            val buffer = ByteArray((handle.length() - skipped).toInt())
            handle.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            if (skipped == 0L) text else "…$skipped earlier bytes omitted…\n${text.substringAfter('\n')}"
        }
    }.getOrElse { "could not be read: $it" }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrElse { "unknown" }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
