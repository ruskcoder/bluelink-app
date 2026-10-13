package com.ruskcoder.messages.transcribe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** How far a model download has got, or why it stopped. */
sealed class ModelDownload {
    data object Idle : ModelDownload()

    /** [received] of [total] bytes, where a total of 0 means the server would not say. */
    data class Running(val model: SpeechModel, val received: Long, val total: Long) : ModelDownload() {
        val fraction: Float get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class Failed(val model: SpeechModel, val reason: String) : ModelDownload()
}

/**
 * Owns the speech models on disk: what is installed, fetching one, and throwing one away.
 *
 * The models are downloaded rather than packaged. Tens of megabytes that most installs never touch
 * has no business inside an APK — it is paid for at install and again at every update, by everyone,
 * whether or not they ever transcribe anything. Downloaded, it is opt-in and it can be given back.
 *
 * A download resumes rather than restarting. `small` is nearly 200MB and phones lose connections
 * mid-transfer; a partial file is kept and continued with a range request, which is the difference
 * between an interruption costing seconds and costing the whole thing.
 *
 * Nothing is trusted until it is verified. The bytes land in a `.part` file, which is hashed and
 * only then renamed into place, so a truncated or altered download can never be the file the model
 * loader opens — that would fail much later and look like a broken feature rather than a bad
 * transfer.
 */
class SpeechModelStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ModelDownload>(ModelDownload.Idle)
    val state: StateFlow<ModelDownload> = _state.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    /** Which models are on disk right now. */
    val installed: StateFlow<Set<SpeechModel>> = _installed.asStateFlow()

    private var job: Job? = null

    /** Where a model lives once installed. */
    fun fileFor(model: SpeechModel): File = File(directory(), model.fileName)

    fun isInstalled(model: SpeechModel): Boolean = model in _installed.value

    /** Total bytes the installed models occupy, for the line under the storage setting. */
    fun installedBytes(): Long = _installed.value.sumOf { fileFor(it).length() }

    /**
     * Fetch [model], unless it is already here or another download is running.
     *
     * [requireUnmetered] refuses to start on a metered connection rather than quietly spending
     * somebody's data plan on 190MB. The check is deliberately at the start and not repeated: a
     * download that began on Wi-Fi and continued over cellular for its last megabyte is a better
     * outcome than one that gives up at 99%.
     */
    fun download(model: SpeechModel, requireUnmetered: Boolean) {
        if (isInstalled(model)) return
        if (job?.isActive == true) return
        if (requireUnmetered && !isUnmetered()) {
            _state.value = ModelDownload.Failed(model, "Waiting for Wi-Fi")
            return
        }

        _state.value = ModelDownload.Running(model, 0, model.bytes)
        job = scope.launch {
            val result = runCatching { fetch(model) }
            when {
                result.isSuccess -> {
                    _installed.update { it + model }
                    _state.value = ModelDownload.Idle
                }
                result.exceptionOrNull() is CancellationException -> {
                    _state.value = ModelDownload.Idle
                    throw result.exceptionOrNull() as CancellationException
                }
                else -> {
                    val cause = result.exceptionOrNull()
                    Log.w(TAG, "could not download ${model.fileName}", cause)
                    _state.value = ModelDownload.Failed(model, cause.readable())
                }
            }
        }
    }

    /** Stops a download in progress. The partial file is kept, so resuming picks it back up. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ModelDownload.Idle
    }

    /** Clears a failure so the button goes back to offering the download. */
    fun clearFailure() {
        if (_state.value is ModelDownload.Failed) _state.value = ModelDownload.Idle
    }

    /** Deletes an installed model, and any partial download of it. */
    fun delete(model: SpeechModel) {
        scope.launch(Dispatchers.IO) {
            fileFor(model).delete()
            partialFor(model).delete()
            _installed.update { it - model }
        }
    }

    private suspend fun fetch(model: SpeechModel) = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        val partial = partialFor(model)
        target.parentFile?.mkdirs()

        // A partial larger than the finished file is not a prefix of it — it is rubbish from a
        // different file or a different version, and continuing from it would produce a model that
        // fails its digest after another 190MB.
        if (partial.length() > model.bytes) partial.delete()
        var have = partial.length()

        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            val code = connection.responseCode
            // A server that ignored the range request starts from the top, so the partial has to go
            // with it — appending would splice the file onto itself.
            if (have > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                partial.delete()
                have = 0
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("server said $code")
            }

            val remaining = connection.contentLengthLong.takeIf { it > 0 } ?: (model.bytes - have)
            val total = have + remaining
            _state.value = ModelDownload.Running(model, have, total)

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partial, have > 0).buffered().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var received = have
                    var lastReported = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        // Reported in steps rather than per read: a 190MB download is tens of
                        // thousands of reads and a progress bar cannot show more than pixels.
                        if (received - lastReported >= REPORT_EVERY) {
                            lastReported = received
                            _state.value = ModelDownload.Running(model, received, total)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (partial.length() != model.bytes) {
            throw IllegalStateException("got ${partial.length()} bytes, expected ${model.bytes}")
        }
        val digest = digestOf(partial)
        if (!digest.equals(model.sha256, ignoreCase = true)) {
            partial.delete()
            throw IllegalStateException("the downloaded file did not match its checksum")
        }
        if (!partial.renameTo(target)) throw IllegalStateException("could not install the model")
        Log.i(TAG, "installed ${model.fileName}")
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A model already on disk is taken at its size rather than re-hashed.
     *
     * Hashing 190MB on every launch to re-confirm something this app installed itself would cost
     * more than it could ever catch: the file was verified on the way in, and nothing else writes
     * to this directory. The size check is there for the one case that does happen — a delete that
     * did not finish.
     */
    private fun scanInstalled(): Set<SpeechModel> =
        SpeechModel.entries.filterTo(mutableSetOf()) { fileFor(it).length() == it.bytes }

    private fun partialFor(model: SpeechModel) = File(directory(), "${model.fileName}.part")

    private fun directory(): File = File(context.filesDir, "speech").apply { mkdirs() }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun Throwable?.readable(): String = when (this) {
        null -> "Download failed"
        is java.net.UnknownHostException -> "No connection"
        is java.net.SocketTimeoutException -> "The connection timed out"
        else -> message?.takeIf { it.isNotBlank() } ?: "Download failed"
    }

    private companion object {
        const val TAG = "SpeechModelStore"
        const val BUFFER = 1 shl 16
        const val REPORT_EVERY = 512L * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
