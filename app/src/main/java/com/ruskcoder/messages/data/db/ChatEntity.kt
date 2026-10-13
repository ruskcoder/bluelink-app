package com.ruskcoder.messages.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "chats")
@Serializable
data class ChatEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val initials: String,
    @ColumnInfo(name = "is_group") val isGroup: Boolean,
    /** Vestigial, and kept on purpose; see [archived] for why it cannot safely be dropped. */
    val favorite: Boolean = false,
    val muted: Boolean,
    val unread: Int,
    val preview: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val theme: String,
    /** JSON-encoded `List<GroupMember>`, null for a one-to-one chat. */
    val members: String?,
    /**
     * Address-book photo uri for a one-to-one conversation's other party (schema v4).
     *
     * Denormalized onto the chat rather than looked up per render: the list draws every row's
     * avatar on every frame of a scroll, and a contacts query per row is not something that can
     * live on that path. Refreshed whenever the address book is re-read.
     */
    @ColumnInfo(name = "photo_uri") val photoUri: String? = null,

    // --- list organization (schema v6) --------------------------------------------------------

    /**
     * Put away out of the main list, into Archived.
     *
     * Back in use after a spell as a vestigial column. A new message *from someone else* brings the
     * conversation back — see [ChatDao.bumpAfterMessage] — because an archive that silently swallows
     * a reply is a place messages go to be missed.
     *
     * [favorite] beside it is still unused, and still here: taking a column out of SQLite before
     * API 34 means rebuilding the table, and `messages` holds a cascading foreign key onto `chats`.
     * Migrations run inside a transaction, where `PRAGMA foreign_keys = OFF` is a no-op, so the
     * `DROP TABLE` a rebuild needs would delete every message on the device along with it.
     */
    val archived: Boolean = false,

    /** Held at the top of the list regardless of how recently anything was said in it. */
    val pinned: Boolean = false,

    // --- iMessage (schema v2) -------------------------------------------------------------

    /**
     * JSON-encoded `List<String>` of Apple handles. iMessage has no server-side chat id — a
     * conversation *is* its participant set — so this, not [id], is what addresses a send.
     */
    val participants: String? = null,
    /** Which of our own handles this conversation sends from. */
    @ColumnInfo(name = "sender_handle") val senderHandle: String? = null,
    /** Apple's identifier for a group, carried on the wire as the conversation's `sender_guid`. */
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    /**
     * Increments on every participant change. Apple uses it to order concurrent edits, so it has
     * to be remembered rather than recomputed.
     */
    @ColumnInfo(name = "group_version") val groupVersion: Long = 0,
    /** `iMessage`, `SMS`, or null for a conversation that only exists on this device. */
    val service: String? = null,

    // --- per-conversation settings (schema v7) ------------------------------------------------

    /**
     * When a snooze expires, in epoch millis; 0 for "not snoozed".
     *
     * Distinct from [muted], which is the switch you set and leave set. A snooze is the same
     * silence with an end on it, so it is stored as the instant it runs out rather than as a flag
     * plus a duration — nothing has to run to un-snooze a conversation, it simply stops being
     * snoozed once the clock passes this.
     */
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: Long = 0,

    /** A picture behind this conversation only, in place of the app's own wallpaper. */
    @ColumnInfo(name = "wallpaper_uri") val wallpaperUri: String? = null,

    /**
     * Per-conversation overrides of the two things you tell the other end about yourself, stored as
     * [OVERRIDE_OFF], [OVERRIDE_ON] or [OVERRIDE_NONE] — "follow the global setting", which is what
     * every conversation starts as and what a reset puts back.
     *
     * An integer rather than a nullable boolean because these columns were created `NOT NULL` when
     * they only ever held on/off, and SQLite cannot loosen that without rebuilding the table.
     */
    @ColumnInfo(name = "read_receipts") val readReceipts: Int = OVERRIDE_NONE,
    @ColumnInfo(name = "typing_indicators") val typingIndicators: Int = OVERRIDE_NONE,

    /** Nothing from these handles is stored, shown, or announced. */
    val blocked: Boolean = false,

    // --- the other end's Focus (schema v10) ---------------------------------------------------

    /**
     * Whether the person on the other end has a Focus on that this account is not an exception to.
     *
     * Only ever set for a one-to-one iMessage conversation, and only when they have chosen to share
     * their Focus status with us — Apple pushes it on an encrypted channel of its own, and silence
     * means "not shared" every bit as much as it means "no Focus on". Stored rather than held in
     * memory because a status change arrives once and then never again until it changes back: an
     * app that forgot it on restart would show nothing until they next picked up their phone.
     */
    @ColumnInfo(name = "notifs_silenced", defaultValue = "0") val notifsSilenced: Boolean = false,

    /**
     * When we last broke through that Focus with "Notify Anyway", in epoch millis; 0 for never.
     *
     * Apple treats one break-through as covering a short window rather than a single message, so
     * messages sent just after one are delivered normally without us having to ask again. Kept as
     * the instant rather than a flag for the same reason a snooze is: what matters is how long ago.
     */
    @ColumnInfo(name = "date_notified_anyways", defaultValue = "0") val dateNotifiedAnyways: Long = 0,
)

const val OVERRIDE_OFF = 0
const val OVERRIDE_ON = 1
const val OVERRIDE_NONE = 2

fun Int.toOverride(): Boolean? = when (this) {
    OVERRIDE_OFF -> false
    OVERRIDE_ON -> true
    else -> null
}

fun Boolean?.toStoredOverride(): Int = when (this) {
    null -> OVERRIDE_NONE
    true -> OVERRIDE_ON
    false -> OVERRIDE_OFF
}
