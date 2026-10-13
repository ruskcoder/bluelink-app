package com.ruskcoder.messages.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A conversation plus the shape of its newest message.
 *
 * The chat row needs more than the denormalized `preview` string to render properly: who sent it,
 * so a group can say "Alex:"; what kind it was, so a photo reads as a photo; and how far it got, so
 * your own last message can carry its tick. Joined rather than denormalized into more columns on
 * `chats` — four more copies of the last message is four more things that can drift out of step
 * with it.
 */
/** One one-to-one conversation and the handles it addresses. See [ChatDao.oneToOneParticipants]. */
data class ChatParticipants(
    val id: String,
    /** JSON-encoded `List<String>` of Apple handles, as stored. */
    val participants: String,
)

data class ChatListRow(
    @Embedded val chat: ChatEntity,
    @ColumnInfo(name = "last_kind") val lastKind: String?,
    @ColumnInfo(name = "last_mine") val lastMine: Boolean?,
    @ColumnInfo(name = "last_status") val lastStatus: String?,
    @ColumnInfo(name = "last_sender_id") val lastSenderId: String?,
    /**
     * The newest message's own text, so the preview line and the rest of the row describe the same
     * message.
     *
     * `chats.preview` is a denormalized copy written by whichever path happened to store a message
     * last, and it drifts: a late-arriving backfill, an edit, an unsend or a second write in the
     * same tick can leave the column holding one message's words while the join below reports a
     * different message's sender and kind. The row then reads as "You: " in front of something
     * somebody else said, or as a photo icon in front of a sentence. Read from the join, the two
     * halves cannot disagree — there is only one message being described.
     */
    @ColumnInfo(name = "last_body") val lastBody: String?,
)

@Dao
interface ChatDao {
    @Query(
        """
        SELECT chats.*,
            newest.kind AS last_kind,
            newest.mine AS last_mine,
            newest.status AS last_status,
            newest.sender_id AS last_sender_id,
            newest.body AS last_body
        FROM chats
        LEFT JOIN messages AS newest ON newest.id = (
            SELECT id FROM messages
            WHERE chat_id = chats.id
            ORDER BY created_at DESC, rowid DESC
            LIMIT 1
        )
        ORDER BY chats.pinned DESC, chats.updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<ChatListRow>>

    @Query("UPDATE chats SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE chats SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE chats SET snoozed_until = :until WHERE id = :id")
    suspend fun setSnoozedUntil(id: String, until: Long)

    @Query("UPDATE chats SET wallpaper_uri = :uri WHERE id = :id")
    suspend fun setWallpaper(id: String, uri: String?)

    @Query("UPDATE chats SET read_receipts = :enabled WHERE id = :id")
    suspend fun setReadReceipts(id: String, enabled: Int)

    @Query("UPDATE chats SET typing_indicators = :enabled WHERE id = :id")
    suspend fun setTypingIndicators(id: String, enabled: Int)

    @Query("UPDATE chats SET blocked = :blocked WHERE id = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    /** Records what Apple's presence channel just said about the person on the other end. */
    @Query("UPDATE chats SET notifs_silenced = :silenced WHERE id = :id")
    suspend fun setNotifsSilenced(id: String, silenced: Boolean)

    @Query("UPDATE chats SET date_notified_anyways = :at WHERE id = :id")
    suspend fun setNotifiedAnyways(id: String, at: Long)

    /**
     * Every one-to-one conversation and the handles it addresses.
     *
     * Read out whole and matched in Kotlin rather than compared in SQL: a stored participant is the
     * handle exactly as Apple wrote it, and the handle a Focus arrives under is written by a
     * different part of Apple — the two agree on the person and not always on the spelling. Only
     * `Handles.key` knows how to make those the same string, and it cannot be called from a query.
     */
    @Query("SELECT id, participants FROM chats WHERE is_group = 0 AND participants IS NOT NULL")
    suspend fun oneToOneParticipants(): List<ChatParticipants>

    @Query("SELECT * FROM chats ORDER BY updated_at DESC")
    suspend fun loadAll(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun findById(id: String): ChatEntity?

    @Insert
    suspend fun insert(chat: ChatEntity)

    /**
     * The conflict branch of the identity upsert: refreshes name/appearance only. `members` and
     * `photo_uri` use COALESCE so a caller that doesn't know the roster (opening an existing group)
     * or has no address-book match never wipes what is already stored. Returns the number of rows
     * updated — 0 means the chat doesn't exist yet.
     */
    @Query(
        """
        UPDATE chats SET
            name = :name,
            color = :color,
            initials = :initials,
            is_group = :isGroup,
            members = COALESCE(:members, members),
            photo_uri = COALESCE(:photoUri, photo_uri)
        WHERE id = :id
        """,
    )
    suspend fun updateIdentity(
        id: String,
        name: String,
        color: String,
        initials: String,
        isGroup: Boolean,
        members: String?,
        photoUri: String?,
    ): Int

    /**
     * Re-points a one-to-one conversation at what the address book now says about the person in it:
     * their name, the monogram and fill derived from it, and their photo.
     *
     * Unlike [updateIdentity] this one does *not* COALESCE. It is called after a full re-read of
     * the address book, where a null photo genuinely means "this contact no longer has one" and a
     * changed name genuinely means the conversation should be re-titled — which is the whole point:
     * renaming someone in Contacts used to leave every existing thread wearing their old name until
     * the next message happened to arrive in it.
     */
    @Query(
        """
        UPDATE chats SET
            name = :name,
            color = :color,
            initials = :initials,
            photo_uri = :photoUri
        WHERE id = :id
        """,
    )
    suspend fun setContactIdentity(
        id: String,
        name: String,
        color: String,
        initials: String,
        photoUri: String?,
    )

    /**
     * Moves a conversation up the list for a message that just landed in it.
     *
     * An unread one — somebody else talking — also takes the conversation back out of Archived.
     * Archiving is putting away a conversation that has gone quiet, not muting the person in it; a
     * reply that stayed filed away where nobody looks would be a message missed on purpose.
     */
    @Query(
        """
        UPDATE chats SET
            preview = :preview,
            updated_at = :updatedAt,
            unread = MAX(unread + :unreadDelta, 0),
            archived = CASE WHEN :unreadDelta > 0 THEN 0 ELSE archived END
        WHERE id = :id
        """,
    )
    suspend fun bumpAfterMessage(id: String, preview: String, updatedAt: Long, unreadDelta: Int)

    @Query("UPDATE chats SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE chats SET preview = :preview WHERE id = :id")
    suspend fun setPreview(id: String, preview: String)

    /**
     * Attaches (or refreshes) the Apple identity of a conversation.
     *
     * COALESCE throughout so a later message that happens to carry less — a receipt with no group
     * name, say — cannot erase what an earlier one established.
     */
    @Query(
        """
        UPDATE chats SET
            participants = COALESCE(:participants, participants),
            sender_handle = COALESCE(:senderHandle, sender_handle),
            group_id = COALESCE(:groupId, group_id),
            service = COALESCE(:service, service)
        WHERE id = :id
        """,
    )
    suspend fun setIMessageFields(
        id: String,
        participants: String?,
        senderHandle: String?,
        groupId: String?,
        service: String?,
    )

    @Query("UPDATE chats SET group_version = :version WHERE id = :id")
    suspend fun setGroupVersion(id: String, version: Long)

    @Query("UPDATE chats SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: String, unread: Int)

    @Query("UPDATE chats SET theme = :theme WHERE id = :id")
    suspend fun setTheme(id: String, theme: String)

    @Query("UPDATE chats SET members = :membersJson, name = :name WHERE id = :id")
    suspend fun setMembers(id: String, membersJson: String, name: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String)

    /**
     * Fold a duplicate conversation's counters into the one it is being merged with.
     *
     * Unread adds up because both halves were genuinely unread; muted ORs together so a flag set on
     * either survives; the preview and timestamp come from whichever half was more recent, which is
     * the message the list should be showing.
     */
    @Query(
        """
        UPDATE chats SET
            unread = unread + :unread,
            muted = muted OR :muted,
            preview = CASE WHEN :updatedAt > updated_at THEN :preview ELSE preview END,
            updated_at = MAX(updated_at, :updatedAt)
        WHERE id = :id
        """,
    )
    suspend fun absorb(
        id: String,
        unread: Int,
        muted: Boolean,
        preview: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM chats")
    suspend fun clearAll()

    /**
     * Every chat, whole, for a backup file.
     *
     * Deliberately not [loadAll]: that one orders by recency for callers that render it, while a
     * backup only ever round-trips the rows and wants the cheapest read.
     */
    @Query("SELECT * FROM chats")
    suspend fun loadAllForBackup(): List<ChatEntity>

    /** Bulk insert for a restore. Replaces on conflict so restoring twice is not an error. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chats: List<ChatEntity>)
}
