package com.ruskcoder.messages.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id", "created_at"]),
        Index(value = ["wire_guid", "part"]),
    ],
)
@Serializable
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    val kind: String,
    /** Doubles as the search index and the chat-list preview text. */
    val body: String,
    val detail: String?,
    val missed: Boolean?,
    val mine: Boolean,
    @ColumnInfo(name = "sender_id") val senderId: String?,
    val status: String?,
    @ColumnInfo(name = "reply_to_id") val replyToId: String?,
    @ColumnInfo(name = "reply_to_name") val replyToName: String?,
    @ColumnInfo(name = "reply_to_text") val replyToText: String?,
    @ColumnInfo(name = "audio_uri") val audioUri: String?,
    @ColumnInfo(name = "audio_duration") val audioDuration: Float?,
    /** JSON-encoded `List<Float>`. */
    @ColumnInfo(name = "audio_waveform") val audioWaveform: String?,
    /** JSON-encoded `Attachment`. */
    val payload: String?,
    /**
     * JSON-encoded [com.ruskcoder.messages.data.model.Reactions] — a map of sender handle to
     * tapback. Was a `List<String>` of glyphs before schema v2, which could not express who
     * reacted and so could not be un-reacted.
     */
    val reactions: String?,

    /**
     * Every version this message had before the one in `body`, oldest first, JSON-encoded.
     *
     * iMessage keeps an edit log and lets you read it back; without one, "Edited" is a label with
     * nothing behind it. Null for a message that has never been rewritten, which is almost all of
     * them — one column carrying nothing for most rows is cheaper than a table nothing joins to.
     */
    @ColumnInfo(name = "edit_history") val editHistory: String? = null,
    val edited: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,

    // --- iMessage (schema v2) -------------------------------------------------------------
    // All nullable or defaulted: a message composed locally has no Apple identity, and rows
    // written before iMessage was set up must stay readable.

    /** The Apple handle that sent this, as `mailto:...` or `tel:...`. Null for local messages. */
    val handle: String? = null,
    /** Which part of the message this row represents; iMessage addresses edits and tapbacks by part. */
    val part: Long = 0,
    /** The part of the quoted message a reply points at, alongside [replyToId]. */
    @ColumnInfo(name = "reply_to_part") val replyToPart: String? = null,
    /** `iMessage`, `SMS`, or null for a message that never left the device. */
    val service: String? = null,
    /** A send effect such as `invisibleink`, echoed back so a resend keeps it. */
    val effect: String? = null,
    val subject: String? = null,
    /** Set when Apple rejected delivery; shown on the bubble in place of a tick. */
    @ColumnInfo(name = "error_text") val errorText: String? = null,
    /** Apple's message GUID. Multiple stored rows may share it when a message has many parts. */
    @ColumnInfo(name = "wire_guid", defaultValue = "''") val wireGuid: String = id,
    /** Opaque MMCS locator retained until a deferred attachment download completes. */
    @ColumnInfo(name = "attachment_token") val attachmentToken: String? = null,
    /** RustPush could not authenticate the signing identity for this inbound content. */
    @ColumnInfo(name = "verification_failed", defaultValue = "0") val verificationFailed: Boolean = false,

    // --- group receipts (schema v5) ---------------------------------------------------------

    /**
     * JSON-encoded [com.ruskcoder.messages.data.model.MessageReceipts] — who has received and read
     * this, by handle.
     *
     * [status] remains the aggregate the bubble draws, recomputed from this whenever a receipt
     * lands. Keeping both means the chat list and the tick rendering stay a single column read,
     * while a group can still answer "who has seen it".
     */
    val receipts: String? = null,

    // --- rich text (schema v9) ---------------------------------------------------------------

    /**
     * How the message's text is dressed, JSON-encoded as `List<TextRun>`.
     *
     * Null for every message nobody formatted, which is nearly all of them — [body] alone is the
     * whole message there, and a column carrying nothing for most rows is cheaper than a table
     * nothing joins to. Where it is set, the runs concatenate to exactly [body]: this says only
     * how the text looks, never what it says, so search and the chat-list preview go on reading
     * [body] and know nothing about formatting.
     */
    @ColumnInfo(name = "text_runs") val textRuns: String? = null,

    // --- quiet delivery (schema v10) ----------------------------------------------------------

    /**
     * Delivered while the recipient had a Focus on that this account is not an exception to — so it
     * arrived on their phone without making a sound.
     *
     * Recorded at the moment the receipt lands rather than derived on read: whether they had a
     * Focus on *then* is not a question the conversation can answer later, and it is what the
     * receipt line under the bubble is reporting. Cleared again when the sender breaks through with
     * "Notify Anyway", which is the one thing that turns a quiet delivery into an ordinary one.
     */
    @ColumnInfo(name = "delivered_quietly", defaultValue = "0") val deliveredQuietly: Boolean = false,

    // --- voice transcription (schema v11) -----------------------------------------------------

    /**
     * What a voice message says, once somebody has asked.
     *
     * Null until then, and never filled in on its own: transcribing costs seconds of CPU and it is
     * a thing you ask for, not something the app should quietly do to every clip that arrives.
     * Stored rather than recomputed because the second reading of a message should not cost what
     * the first one did — and because the model is not perfectly deterministic across thread
     * counts, so a transcript that changed each time you opened it would read as a bug.
     *
     * An empty string is a real answer: it means the clip was transcribed and had no words in it.
     */
    val transcript: String? = null,

    // --- link previews (schema v12) -----------------------------------------------------------

    /**
     * The rich link card a text message carries, JSON-encoded as
     * [com.ruskcoder.messages.data.model.LinkPreview].
     *
     * Null for every message without a link, and for one whose link nobody fetched a card for. The
     * sender fetches it — the way Apple's clients do — so a message arrives with its card already
     * described and the receiving phone never contacts the linked site on its own.
     */
    @ColumnInfo(name = "link_preview") val linkPreview: String? = null,
)
