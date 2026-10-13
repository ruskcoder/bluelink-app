package com.ruskcoder.messages.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * A full-text index over every message's `body` (schema v12).
 *
 * External-content, so the words are not stored twice: the index holds only tokens and points back
 * at `messages` by rowid, and Room keeps the two in step with triggers. Search used to be a
 * `LIKE '%needle%'` over the whole table, which is a scan of every message ever received for every
 * keystroke typed into the search field — fine for a hundred messages, and seconds for a hundred
 * thousand.
 *
 * `unicode61` rather than the default tokenizer so that case folding works for more than ASCII: a
 * search for "élan" finds "Élan", which the simple tokenizer does not.
 */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "messages_fts")
data class MessageFts(val body: String)
