package com.ruskcoder.messages.data.backup

import com.ruskcoder.messages.data.db.ChatEntity
import com.ruskcoder.messages.data.db.MessageEntity
import com.ruskcoder.messages.data.model.Settings
import kotlinx.serialization.Serializable

/** What the file extension and the `format` field both say, so a wrong file is caught early. */
const val BACKUP_FORMAT = "com.ruskcoder.messages.backup"
const val BACKUP_VERSION = 1
const val BACKUP_EXTENSION = "mbk"
const val BACKUP_MIME = "application/octet-stream"

/**
 * The outer, unencrypted layer of a backup file.
 *
 * Everything here exists so the *next* version of the app can still open this one: the format tag,
 * the version, and the full description of how the payload was sealed — cipher, key derivation,
 * iteration count, salt. Nothing is assumed at read time, so raising the iteration count or moving
 * to a different KDF later does not strand files written today.
 *
 * It carries no hint of what is inside beyond [createdAt]. A backup holds an Apple Account
 * credential, and a summary in the clear would tell anyone holding the file whose account to go
 * after; the counts the restore screen shows are read out of the payload once it is open.
 */
@Serializable
data class BackupEnvelope(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val createdAt: Long,
    /** JCA name, so restore asks for the same derivation this device could actually perform. */
    val kdf: String,
    val iterations: Int,
    /** Base64. */
    val salt: String,
    /** Base64, 12 bytes — the AES-GCM nonce. */
    val iv: String,
    /** Base64 AES-256-GCM ciphertext of [BackupPayload] as JSON, tag appended. */
    val payload: String,
)

/**
 * The decrypted contents.
 *
 * Every section is optional and read back independently. A backup taken before iMessage was set up
 * has no [account] or [hardware]; one taken with messages left out has no [messages]. Restore
 * applies whichever sections are present and says so, rather than refusing a partial file.
 */
@Serializable
data class BackupPayload(
    val settings: Settings? = null,
    val account: BackupAccount? = null,
    /**
     * Base64 `OABS` activation payload — the same bytes a shared activation QR carries, so the
     * hardware identity restores through exactly the path a scanned code takes.
     *
     * Null for a relay identity, which has no machine of its own to export.
     */
    val hardware: String? = null,
    val messages: BackupMessages? = null,
)

/**
 * The Apple Account, as the far side needs it.
 *
 * [hashedPassword] is base64 of the SHA-256 that Grand Slam authenticates with, not the password.
 * It is password-equivalent all the same, which is the whole reason the payload is encrypted.
 */
@Serializable
data class BackupAccount(
    val appleId: String,
    val hashedPassword: String,
)

/** The conversation database, as its own rows. */
@Serializable
data class BackupMessages(
    val chats: List<ChatEntity>,
    val messages: List<MessageEntity>,
)

/** What a restore found in the file, for the confirmation step and the result line afterwards. */
data class BackupContents(
    val createdAt: Long,
    val appleId: String?,
    val hasHardware: Boolean,
    val hasSettings: Boolean,
    val chatCount: Int,
    val messageCount: Int,
) {
    val hasMessages: Boolean get() = chatCount > 0 || messageCount > 0
}

/**
 * A backup that has been decrypted, still paired with when it was taken.
 *
 * The timestamp lives in the envelope rather than the payload, so that a restore can say how old a
 * file is before asking for the passphrase; carrying it forward here keeps the confirmation step
 * from having to hold on to the envelope as well.
 */
data class OpenedBackup(val payload: BackupPayload, val createdAt: Long) {
    val contents: BackupContents get() = BackupContents(
        createdAt = createdAt,
        appleId = payload.account?.appleId,
        hasHardware = payload.hardware != null,
        hasSettings = payload.settings != null,
        chatCount = payload.messages?.chats?.size ?: 0,
        messageCount = payload.messages?.messages?.size ?: 0,
    )
}

/** Why a backup file could not be opened, in the wording the dialog shows. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
