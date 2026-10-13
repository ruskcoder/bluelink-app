package com.ruskcoder.messages.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.util.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes and reads the app's backup files.
 *
 * The credential half is the reason this exists in the form it does. Everything Apple-shaped on
 * disk is sealed under keys the Android Keystore will not release, so a backup cannot be a copy of
 * the state directory — it carries the Mac hardware identity re-encoded as an activation payload
 * and the Apple Account credential decrypted by the device that still holds the key, and the
 * restoring device rebuilds the rest by registering normally. See `kotlin_api/backup.rs`.
 *
 * [imessage] is a provider rather than the service itself: constructing it loads the Rust library
 * and initializes Apple state on disk, which is real work that a settings screen should not do
 * merely by being opened. It is also allowed to fail — a build without the native library still
 * backs up settings and messages.
 */
class BackupManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val chats: ChatRepository,
    private val imessage: () -> IMessageService,
) {
    /**
     * Build a backup and write it to `target`.
     *
     * The whole payload is assembled in memory and sealed in one go, because AES-GCM authenticates
     * the payload as a unit — there is no half-written backup to be salvaged, and a backup that
     * only half restores an account is worse than none.
     */
    suspend fun create(target: Uri, includeMessages: Boolean, passphrase: String): BackupContents =
        withContext(Dispatchers.IO) {
            val createdAt = System.currentTimeMillis()
            val payload = BackupPayload(
                settings = settings.settings.value,
                account = exportAccount(),
                hardware = exportHardware(),
                messages = if (includeMessages) chats.exportForBackup() else null,
            )

            val json = appJson.encodeToString(BackupPayload.serializer(), payload)
            val envelope = BackupCrypto.seal(json.toByteArray(Charsets.UTF_8), passphrase, createdAt)

            val bytes = appJson.encodeToString(BackupEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
            val stream = context.contentResolver.openOutputStream(target, "wt")
                ?: throw BackupException("Could not open that location for writing.")
            stream.use { it.write(bytes) }

            OpenedBackup(payload, createdAt).contents
        }

    /**
     * Read `source` and open it with `passphrase`.
     *
     * Parsing the envelope and decrypting are one step from the caller's point of view, but they
     * fail for entirely different reasons — the wrong file versus the wrong passphrase — and the
     * messages say which, because only one of the two is worth retrying.
     */
    suspend fun open(source: Uri, passphrase: String): OpenedBackup = withContext(Dispatchers.IO) {
        val envelope = readEnvelope(source)
        val plaintext = BackupCrypto.open(envelope, passphrase)
        val payload = runCatching {
            appJson.decodeFromString(BackupPayload.serializer(), plaintext.toString(Charsets.UTF_8))
        }.getOrElse { throw BackupException("This backup opened but its contents could not be read.", it) }
        OpenedBackup(payload, envelope.createdAt)
    }

    /** The unencrypted outer layer, checked before a passphrase is worth asking for. */
    suspend fun readEnvelope(source: Uri): BackupEnvelope = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw BackupException("Could not open that file.")

        val envelope = runCatching {
            appJson.decodeFromString(BackupEnvelope.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrElse { throw BackupException("That is not a backup file.", it) }

        if (envelope.format != BACKUP_FORMAT) throw BackupException("That is not a backup file.")
        if (envelope.version > BACKUP_VERSION) {
            throw BackupException("This backup was written by a newer version of the app.")
        }
        envelope
    }

    /**
     * Apply an opened backup, and report which parts of it landed.
     *
     * Sections are applied independently and a failure in one does not abandon the others: a
     * restore that recovers the conversations but cannot write the Apple credential is still worth
     * having, and the caller says exactly what did and did not happen.
     */
    suspend fun restore(payload: BackupPayload): RestoreOutcome = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()

        payload.settings?.let { settings.replace(it) }
        payload.messages?.let { chats.restoreFromBackup(it) }

        val hardware = payload.hardware?.let { encoded ->
            runCatching { imessage().importHardware(encoded.decodeBase64()) }
                .onFailure {
                    Log.e(TAG, "could not restore hardware identity", it)
                    failures += "the Mac identity"
                }
                .isSuccess
        } ?: false

        val account = payload.account?.let { saved ->
            runCatching { imessage().importAccount(saved.appleId, saved.hashedPassword.decodeBase64()) }
                .onFailure {
                    Log.e(TAG, "could not restore Apple Account", it)
                    failures += "the Apple Account"
                }
                .isSuccess
        } ?: false

        // The live session was built against the identity that has just been replaced, so it can
        // only misreport from here on. Dropping it makes the next caller open a fresh one.
        if (hardware || account) runCatching { imessage().invalidateSession() }

        RestoreOutcome(
            settings = payload.settings != null,
            messages = payload.messages != null,
            hardware = hardware,
            account = account,
            failures = failures,
        )
    }

    private suspend fun exportAccount(): BackupAccount? =
        runCatching { imessage().exportAccount() }
            .onFailure { Log.w(TAG, "no Apple Account to back up", it) }
            .getOrNull()
            ?.let { BackupAccount(it.appleId, it.hashedPassword.base64()) }

    private suspend fun exportHardware(): String? =
        runCatching { imessage().exportHardwarePayload()?.base64() }
            .onFailure { Log.w(TAG, "no hardware identity to back up", it) }
            .getOrNull()

    private companion object {
        const val TAG = "BackupManager"
    }
}

/** What a restore actually applied. Drives the wording of the dialog that follows it. */
data class RestoreOutcome(
    val settings: Boolean,
    val messages: Boolean,
    val hardware: Boolean,
    val account: Boolean,
    val failures: List<String>,
) {
    /** Whether the app needs to reconnect to Apple before anything else will work. */
    val signInRestored: Boolean get() = hardware || account
}

/** The name the file picker opens with, so backups sort by date in a folder. */
fun suggestedBackupName(at: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
    return "messages-backup-$stamp.$BACKUP_EXTENSION"
}
