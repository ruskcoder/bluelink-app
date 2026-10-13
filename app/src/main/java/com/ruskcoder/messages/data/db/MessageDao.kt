package com.ruskcoder.messages.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LastMessageRow(val kind: String, val body: String)

data class MessageSearchRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "chat_id") val chatId: String,
    val body: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
    /** Whether we sent it, for search's "From me" filter. */
    val mine: Boolean,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC, rowid ASC")
    suspend fun loadForChat(chatId: String): List<MessageEntity>

    /**
     * Live view of one whole conversation — Room re-runs this automatically after any write.
     *
     * For the few readers that genuinely need all of it (a conversation's shared media, search
     * scoped to one chat). The conversation screen reads [observeRecentForChat] instead.
     */
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC, rowid ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    /**
     * The newest [limit] messages of a conversation, oldest first.
     *
     * What the conversation screen draws from. Unbounded, every receipt, tapback and typing-driven
     * write re-read and re-mapped the entire history of the chat — tens of thousands of rows for a
     * long one — which is where the thread's stutter on every incoming "Delivered" came from. The
     * window grows as the reader scrolls back; see `ChatRepository.loadOlder`.
     *
     * Selected by rowid inside the index rather than with an ORDER BY over a subquery's `*`, so the
     * outer ordering can still break ties on rowid the way [observeForChat] does.
     */
    @Query(
        """
        SELECT * FROM messages WHERE rowid IN (
            SELECT rowid FROM messages WHERE chat_id = :chatId
            ORDER BY created_at DESC, rowid DESC
            LIMIT :limit
        )
        ORDER BY created_at ASC, rowid ASC
        """,
    )
    fun observeRecentForChat(chatId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * How many of a conversation's messages are at least as new as [messageId].
     *
     * Which is exactly the window a jump to that message needs — a search hit, a quote tapped far up
     * the thread. Zero when the message is not in this conversation.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE chat_id = :chatId
          AND created_at >= (SELECT created_at FROM messages WHERE id = :messageId AND chat_id = :chatId)
        """,
    )
    suspend fun countFromMessage(chatId: String, messageId: String): Int

    /**
     * Our own messages whose send was started and never finished — no delivery state, no failure.
     *
     * Every send writes its row before it sets off, and marks it sent or failed when it comes back.
     * A row left in neither state was in flight when the process died, or was queued while there was
     * no connection, and nothing will ever finish it unless it is picked up again. See
     * `IMessageSender.resumeInterruptedSends`.
     *
     * Not keyed on a blank `wire_guid`: a failed send is named with the guid it went out under, so a
     * retry that was then queued for want of a connection has a guid and still never arrived.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE mine = 1 AND error_text IS NULL AND status IS NULL
          AND kind IN ('text', 'attachment', 'audio')
        ORDER BY created_at ASC
        """,
    )
    suspend fun findInterruptedSends(): List<MessageEntity>

    /** Our own sends that failed since [since], oldest first — the ones worth one more automatic try. */
    @Query(
        """
        SELECT * FROM messages
        WHERE mine = 1 AND error_text IS NOT NULL AND created_at >= :since
          AND kind IN ('text', 'attachment', 'audio')
        ORDER BY created_at ASC
        """,
    )
    suspend fun findRecentFailedSends(since: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    /**
     * The newest instant this conversation already holds, or null when it holds nothing.
     *
     * Read before a message of ours is written, so it can be given a place *after* everything
     * already on screen — see `ChatRepository.showingAt`.
     */
    @Query("SELECT MAX(created_at) FROM messages WHERE chat_id = :chatId")
    suspend fun newestCreatedAt(chatId: String): Long?

    @Query("UPDATE messages SET status = :status WHERE chat_id = :chatId AND id = :messageId AND mine = 1")
    suspend fun setStatus(chatId: String, messageId: String, status: String)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE wire_guid = :wireGuid AND part = :part)")
    suspend fun existsWirePart(wireGuid: String, part: Long): Boolean

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun findById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE wire_guid = :wireGuid AND part = :part LIMIT 1")
    suspend fun findByWirePart(wireGuid: String, part: Long): MessageEntity?

    /**
     * Every one of our own rows for a wire message.
     *
     * A receipt names the message, not the part, so all of a multi-part message's rows have to move
     * together — otherwise a captioned photo could show its caption as read and its image as merely
     * delivered.
     */
    @Query("SELECT * FROM messages WHERE wire_guid = :wireGuid AND mine = 1")
    suspend fun findMineByWire(wireGuid: String): List<MessageEntity>

    /**
     * Writes the receipt map and the aggregate it implies together.
     *
     * One statement because they must never disagree: `status` is what the bubble draws, and it is
     * derived from `receipts` at the moment the receipt lands.
     */
    @Query("UPDATE messages SET receipts = :receipts, status = :status WHERE id = :messageId")
    suspend fun setReceipts(messageId: String, receipts: String?, status: String)

    /**
     * Every stored part of one wire message, lowest part first.
     *
     * A reply names the message it answers by wire guid, and the part it points at is not
     * necessarily one we stored under that number — a captioned photo puts its text and its
     * attachment in separate rows. Taking them all lets the quote fall back to whichever part can
     * actually be rendered.
     */
    @Query("SELECT * FROM messages WHERE wire_guid = :wireGuid ORDER BY part ASC")
    suspend fun findByWire(wireGuid: String): List<MessageEntity>

    /**
     * The same lookup without case sensitivity, for a guid that came back from the wire in a
     * different case than it was stored in. Separate from [findByWire] because that one can use the
     * `wire_guid` index and this one cannot.
     */
    @Query("SELECT * FROM messages WHERE wire_guid LIKE :wireGuid ORDER BY part ASC")
    suspend fun findByWireIgnoringCase(wireGuid: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE attachment_token IS NOT NULL")
    suspend fun pendingAttachmentDownloads(): List<MessageEntity>

    @Query("SELECT chat_id FROM messages WHERE id = :messageId")
    suspend fun chatIdOf(messageId: String): String?

    /**
     * The conversation a message off the wire belongs to, addressed by Apple's GUID.
     *
     * Not the same question as [chatIdOf]. A row's `id` only equals its `wire_guid` for a message
     * that arrived from Apple: one *we* sent is drawn — and given its id — the moment it is
     * composed, and learns its GUID later. So anything arriving about our own message (a tapback,
     * most visibly) has to be looked up this way, or it finds nothing and is dropped.
     *
     * Ordered by part so a multi-part message answers with its first row; every part of one
     * message is in the same conversation, so which one answers does not matter.
     */
    @Query("SELECT chat_id FROM messages WHERE wire_guid = :wireGuid ORDER BY part ASC LIMIT 1")
    suspend fun chatIdOfWire(wireGuid: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE chat_id = :chatId")
    suspend fun countForChat(chatId: String): Int

    /**
     * Move a conversation's messages to another chat.
     *
     * Used when merging threads that were the same person all along. The destination row has to
     * exist first — `chat_id` is a foreign key onto `chats`, and it cascades on delete, so draining
     * a chat before removing it is what keeps its history rather than destroying it.
     */
    @Query("UPDATE messages SET chat_id = :target WHERE chat_id = :source")
    suspend fun reassignChat(source: String, target: String)

    /**
     * Moves a receipt forward only.
     *
     * Apple does not guarantee receipt order, so a "delivered" can land after a "read" — applying
     * it blindly would make an already-read message look merely delivered. `rank` is the ordinal of
     * [com.ruskcoder.messages.data.model.DeliveryStatus], so comparing against the stored value's
     * position in the same list keeps the progression monotonic.
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE wire_guid = :messageId AND mine = 1
          AND (status IS NULL OR CASE status
                WHEN 'SENT' THEN 0 WHEN 'DELIVERED' THEN 1 WHEN 'READ' THEN 2 WHEN 'PLAYED' THEN 3 ELSE -1 END < :rank)
        """,
    )
    suspend fun advanceStatus(messageId: String, status: String, rank: Int)

    /**
     * Notes that a receipt arrived while the other end had a Focus on.
     *
     * Set at receipt time and never re-derived: whether they had a Focus on at the moment the
     * message landed is not something the conversation can work out afterwards, and it is exactly
     * what the line under the bubble is reporting.
     */
    @Query("UPDATE messages SET delivered_quietly = :quietly WHERE wire_guid = :messageId AND mine = 1")
    suspend fun setDeliveredQuietly(messageId: String, quietly: Boolean)

    /**
     * The newest of our own messages in a conversation that is still sitting unread and unheard.
     *
     * What "Notify Anyway" acts on: the offer is about the last thing you said, and only while it
     * has been delivered quietly and not yet read. Returns the wire guid, since that is what a
     * break-through names.
     */
    @Query(
        """
        SELECT wire_guid FROM messages
        WHERE chat_id = :chatId AND mine = 1 AND delivered_quietly = 1 AND status = 'DELIVERED'
        ORDER BY created_at DESC, rowid DESC
        LIMIT 1
        """,
    )
    suspend fun newestQuietlyDelivered(chatId: String): String?

    /** Turns a quiet delivery into an ordinary one, once the recipient has been told anyway. */
    @Query("UPDATE messages SET delivered_quietly = 0 WHERE chat_id = :chatId AND mine = 1 AND delivered_quietly = 1")
    suspend fun clearDeliveredQuietly(chatId: String)

    /**
     * Marks a send as failed — unless the message is already known to have arrived.
     *
     * Clearing `status` is right for a message that never got anywhere: it picked up a SENT tick
     * when it was handed to Apple, and that tick is a lie once the send is known to have failed.
     * But DELIVERED and READ are not optimistic — they are re-derived from receipts that named a
     * person and a time, so they are evidence, and an error arriving afterwards cannot unmake them.
     *
     * Apple's errors are *per recipient device*, not per message, and they arrive whenever its own
     * retries give up — which is routinely hours after the message reached everyone else. One
     * signed-out iPad or one device with stale keys in a thread was enough to condemn a whole
     * conversation's worth of delivered messages, each of them flipping to a red badge long after
     * the reply to it had been read. A partial failure is not this message failing, and there is
     * nothing useful to say to the user about it, so it is logged by the caller and dropped here.
     */
    @Query(
        """
        UPDATE messages SET error_text = :error, status = NULL
        WHERE wire_guid = :messageId AND mine = 1
          AND COALESCE(status, '') NOT IN ('DELIVERED', 'READ', 'PLAYED')
        """,
    )
    suspend fun setError(messageId: String, error: String): Int

    /**
     * Names a row Apple has now accepted, and gives it the tick that goes with the hand-off.
     *
     * A message is drawn from the moment it is composed, before Apple has been told about it, so it
     * spends its first moments as a row with no guid — this is what ends that: from here on the
     * receipts, edits and quotes that address a message by guid can find it.
     */
    @Query("UPDATE messages SET wire_guid = :wireGuid, status = :status WHERE id = :messageId")
    suspend fun setWireGuid(messageId: String, wireGuid: String, status: String)

    /**
     * The same failure as [setError], for a message that never got as far as having a guid.
     */
    @Query("UPDATE messages SET error_text = :error, status = NULL WHERE id = :messageId")
    suspend fun setErrorById(messageId: String, error: String)

    /**
     * Puts a failed message back in flight.
     *
     * Clears the reason without granting a status: the retry has been started, not accepted, so the
     * row goes back to exactly what it looked like between being composed and Apple naming it. That
     * is what takes the badge off the bubble the moment Retry is pressed rather than when the round
     * trip finishes — and what puts it back, with a fresh reason, if the second attempt fails too.
     */
    @Query("UPDATE messages SET error_text = NULL, status = NULL WHERE id = :messageId")
    suspend fun clearError(messageId: String)

    /**
     * Names a message that failed to send with the GUID it went out under, leaving it failed.
     *
     * A failure is not proof the message never arrived — a timeout is usually a lost answer — and a
     * row with no GUID can never be matched by the delivery receipt that would prove otherwise.
     */
    @Query("UPDATE messages SET wire_guid = :wireGuid WHERE id = :messageId")
    suspend fun nameUnsent(messageId: String, wireGuid: String)

    /** Our own failed messages that were never named — see [nameUnsent]. */
    @Query("SELECT * FROM messages WHERE mine = 1 AND error_text IS NOT NULL AND wire_guid = ''")
    suspend fun findUnnamedFailures(): List<MessageEntity>

    /** Replaces an attachment's stored description, once its bytes have been downloaded. */
    @Query("UPDATE messages SET payload = :payload, attachment_token = NULL WHERE id = :messageId")
    suspend fun setPayload(messageId: String, payload: String)

    /**
     * Fills in a received voice message once its file has been fetched and made playable.
     *
     * The row was written as a voice message the moment it arrived — a bubble that turns into a
     * different kind of message after the download would move the conversation under the reader.
     * Only the clip itself is missing until this runs.
     */
    @Query(
        "UPDATE messages SET audio_uri = :uri, audio_duration = :duration, audio_waveform = :waveform, " +
            "attachment_token = NULL WHERE id = :messageId",
    )
    suspend fun setAudioClip(messageId: String, uri: String, duration: Float, waveform: String)

    /** Records what a voice message says. An empty string means it was transcribed and had no words. */
    @Query("UPDATE messages SET transcript = :transcript WHERE id = :messageId")
    suspend fun setTranscript(messageId: String, transcript: String)

    /**
     * Turns a voice message back into a plain file bubble.
     *
     * For a clip whose container this device cannot read: a named file you can open elsewhere is a
     * truer thing to show than a play button that does nothing.
     */
    @Query(
        "UPDATE messages SET kind = 'attachment', body = :name, payload = :payload, " +
            "attachment_token = NULL WHERE id = :messageId",
    )
    suspend fun demoteAudioToAttachment(messageId: String, name: String, payload: String)

    /**
     * Marks everything of ours in one conversation as read.
     *
     * Only rows that actually got somewhere: a message still in flight has not been read by anyone,
     * and one that failed was never delivered to be read. `READ` is the end of the progression, so
     * nothing here can move a receipt backwards.
     */
    @Query(
        """
        UPDATE messages SET status = 'READ'
        WHERE chat_id = :chatId AND mine = 1 AND error_text IS NULL
          AND status IN ('SENT', 'DELIVERED')
        """,
    )
    suspend fun markMineRead(chatId: String)

    @Query("UPDATE messages SET reactions = :reactions WHERE chat_id = :chatId AND id = :messageId")
    suspend fun setReactions(chatId: String, messageId: String, reactions: String?)

    /**
     * Rewrites a message and records what it used to say.
     *
     * The history is passed in already assembled rather than appended in SQL: it is a JSON list,
     * and the caller has just read the row it is extending anyway.
     */
    @Query(
        """
        UPDATE messages SET body = :text, edited = 1, edit_history = :history
        WHERE wire_guid = :wireGuid AND part = :part AND kind = 'text'
        """,
    )
    suspend fun editMessage(wireGuid: String, part: Long, text: String, history: String?)

    /** The rows one wire message occupies, for reading a history back before extending it. */
    @Query("SELECT * FROM messages WHERE wire_guid = :wireGuid AND part = :part AND kind = 'text' LIMIT 1")
    suspend fun findTextByWirePart(wireGuid: String, part: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE chat_id = :chatId AND id IN (:ids)")
    suspend fun deleteMessages(chatId: String, ids: List<String>)

    /**
     * "Delete for everyone": the row survives as a tombstone, everything else is cleared.
     *
     * `body`, `payload` and the voice-note columns are deliberately left alone: between them they
     * are *what the message was*, and the tombstone offers to show it — see
     * [com.ruskcoder.messages.data.model.Message.Deleted]. A photo whose payload had been thrown
     * away could only be revealed as the word "Photo". Everything that would make the row behave
     * like a message still goes: its kind, its receipts, its reactions, its place in a thread.
     */
    @Query(
        """
        UPDATE messages SET
            kind = 'deleted', detail = NULL, status = NULL,
            reactions = NULL, reply_to_id = NULL, reply_to_name = NULL, reply_to_text = NULL
        WHERE chat_id = :chatId AND id IN (:ids)
        """,
    )
    suspend fun tombstone(chatId: String, ids: List<String>)

    /**
     * Whether the same conversation event was already noted very recently.
     *
     * A group change is recorded optimistically when we make it, and Apple may also echo it back
     * with a guid of its own — which the wire-guid check cannot catch. Matching on the text within
     * a short window does, and two genuinely separate identical changes seconds apart is not a
     * thing that happens.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM messages
            WHERE chat_id = :chatId AND kind = 'system' AND body = :text AND created_at >= :since
        )
        """,
    )
    suspend fun hasRecentSystemMessage(chatId: String, text: String, since: Long): Boolean

    @Query("SELECT kind, body FROM messages WHERE chat_id = :chatId ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun lastMessage(chatId: String): LastMessageRow?

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    /** Every message, whole, for a backup file. */
    @Query("SELECT * FROM messages")
    suspend fun loadAllForBackup(): List<MessageEntity>

    /** Bulk insert for a restore. Replaces on conflict so restoring twice is not an error. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * The substring search, kept for what a token index cannot answer: the middle of a word.
     *
     * A full-text match is by whole words and word prefixes, so "ell" finds nothing in "hello".
     * `ChatRepository.searchMessages` falls back to this only when the index has come back empty.
     */
    @Query(
        """
        SELECT id, chat_id, body, created_at, mine FROM messages
        WHERE kind IN ('text', 'attachment') AND body LIKE :pattern ESCAPE '\'
        ORDER BY created_at DESC LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<MessageSearchRow>

    /** Every conversation's messages matching a full-text [match] expression, newest first. */
    @Query(
        """
        SELECT messages.id, messages.chat_id, messages.body, messages.created_at, messages.mine FROM messages
        JOIN messages_fts ON messages.rowid = messages_fts.rowid
        WHERE messages_fts MATCH :match AND messages.kind IN ('text', 'attachment')
        ORDER BY messages.created_at DESC LIMIT :limit
        """,
    )
    suspend fun searchFts(match: String, limit: Int): List<MessageSearchRow>

    /** The same, confined to one conversation — find-in-chat, which has to reach past the window. */
    @Query(
        """
        SELECT messages.id, messages.chat_id, messages.body, messages.created_at, messages.mine FROM messages
        JOIN messages_fts ON messages.rowid = messages_fts.rowid
        WHERE messages_fts MATCH :match AND messages.chat_id = :chatId
          AND messages.kind IN ('text', 'attachment')
        ORDER BY messages.created_at DESC LIMIT :limit
        """,
    )
    suspend fun searchFtsInChat(chatId: String, match: String, limit: Int): List<MessageSearchRow>

    /** The substring fallback for one conversation. */
    @Query(
        """
        SELECT id, chat_id, body, created_at, mine FROM messages
        WHERE chat_id = :chatId AND kind IN ('text', 'attachment') AND body LIKE :pattern ESCAPE '\'
        ORDER BY created_at DESC LIMIT :limit
        """,
    )
    suspend fun searchInChat(chatId: String, pattern: String, limit: Int): List<MessageSearchRow>

    /** Takes stickers off a message — every row whose id starts with [idPrefix], escaped for LIKE. */
    @Query("DELETE FROM messages WHERE chat_id = :chatId AND id LIKE :idPrefix ESCAPE '\\'")
    suspend fun deleteByIdPrefix(chatId: String, idPrefix: String)

    /** Records the link card a message carries — see [MessageEntity.linkPreview]. */
    @Query("UPDATE messages SET link_preview = :preview WHERE id = :messageId")
    suspend fun setLinkPreview(messageId: String, preview: String?)
}
