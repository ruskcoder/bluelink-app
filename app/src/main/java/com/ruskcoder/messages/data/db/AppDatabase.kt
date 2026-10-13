package com.ruskcoder.messages.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Conversations live in SQLite (via Room): it's the storage a phone messaging app wants — an
 * append-only message log that grows to thousands of rows, indexed reads per conversation, and
 * cheap filtering for search. Settings live in DataStore instead (see `data.datastore`), since
 * those are a handful of flags read on every launch.
 *
 * This is a fresh Android install with no prior schema to migrate from, so there's a single
 * version at the RN app's final shape rather than replaying that app's incremental
 * ALTER TABLE history — that history only mattered for upgrading an already-installed app.
 */
@Database(entities = [ChatEntity::class, MessageEntity::class, MessageFts::class], version = 13, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Adds the columns iMessage needs, and rewrites stored reactions.
         *
         * Before v2 a message's reactions were a JSON array of glyphs, which records *what* was
         * reacted but not *who* by — so a tapback could never be removed or attributed. v2 stores a
         * map keyed by sender handle instead. Existing rows have no sender to attribute to, so they
         * are dropped rather than invented: they can only have come from the pre-iMessage local
         * store, where nothing sent them.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN handle TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN part INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE messages ADD COLUMN reply_to_part TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN service TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN effect TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN subject TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN error_text TEXT")
                connection.execSQL("UPDATE messages SET reactions = NULL")

                connection.execSQL("ALTER TABLE chats ADD COLUMN participants TEXT")
                connection.execSQL("ALTER TABLE chats ADD COLUMN sender_handle TEXT")
                connection.execSQL("ALTER TABLE chats ADD COLUMN group_id TEXT")
                connection.execSQL("ALTER TABLE chats ADD COLUMN group_version INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE chats ADD COLUMN service TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN wire_guid TEXT NOT NULL DEFAULT ''")
                connection.execSQL("UPDATE messages SET wire_guid = id")
                connection.execSQL("ALTER TABLE messages ADD COLUMN attachment_token TEXT")
                connection.execSQL("ALTER TABLE messages ADD COLUMN verification_failed INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_messages_wire_guid_part ON messages(wire_guid, part)")
            }
        }

        /** Denormalizes each conversation's address-book photo onto its row. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE chats ADD COLUMN photo_uri TEXT")
            }
        }

        /**
         * Adds per-participant receipts.
         *
         * Existing rows start empty rather than being back-filled from `status`: a message already
         * marked READ says nothing about *which* participant read it, and inventing a handle would
         * make the new "read by" list lie about people who may never have seen it.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN receipts TEXT")
            }
        }

        /** Archive and pin, so the list can be organized rather than only sorted. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * The settings a conversation carries on its own, rather than inheriting from the app.
         *
         * Every one of them defaults to the behaviour that was already in force, so an existing
         * install comes out of this migration behaving exactly as it went in: nothing snoozed,
         * no per-chat wallpaper, receipts and typing indicators still gated only by the global
         * switches, nobody blocked.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE chats ADD COLUMN snoozed_until INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE chats ADD COLUMN wallpaper_uri TEXT")
                connection.execSQL("ALTER TABLE chats ADD COLUMN read_receipts INTEGER NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE chats ADD COLUMN typing_indicators INTEGER NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE chats ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * The log behind the "Edited" label.
         *
         * Existing edited messages start with an empty history rather than a fabricated one: the
         * version before the edit was overwritten at the time and is not recoverable, and inventing
         * an entry would put words in someone's mouth. Their label simply has nothing to open.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN edit_history TEXT")
            }
        }

        /**
         * Adds the column that says how a message's text is formatted.
         *
         * Nothing is back-filled and nothing needs to be: every existing row is plain text, and
         * null is exactly what plain text stores.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN text_runs TEXT")
            }
        }

        /**
         * The other end's Focus, and what it did to a message already sent.
         *
         * Nothing is back-filled. Whether somebody had a Focus on when a message was delivered
         * last week is not recoverable, and guessing it would put a "Delivered Quietly" under a
         * message that rang: every existing row is simply an ordinary delivery, which is what the
         * default says.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN delivered_quietly INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE chats ADD COLUMN notifs_silenced INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE chats ADD COLUMN date_notified_anyways INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * What a voice message says, for the ones somebody has asked about.
         *
         * Nothing is back-filled and nothing could be: transcribing every clip already in the
         * store would take minutes of CPU on the first launch after an update, to answer a
         * question nobody has asked yet. Null is exactly right — it means "not transcribed".
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN transcript TEXT")
            }
        }

        /**
         * The full-text index behind search, and the column link cards are stored in.
         *
         * The index is external-content — it points at `messages` rather than copying it — so the
         * triggers that keep it in step have to be created here exactly as Room would create them
         * on a fresh install, and then the index is built once from everything already stored.
         * That rebuild is one pass over the table on the first launch after the update; every
         * search after it is an index lookup instead of a scan.
         *
         * Nothing is back-filled into `link_preview`: a card is fetched by the sender at the moment
         * of sending, and inventing one for an old message would mean fetching every link anyone
         * has ever sent.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE messages ADD COLUMN link_preview TEXT")
                connection.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`body` TEXT NOT NULL, " +
                        "tokenize=unicode61, content=`messages`)",
                )
                FTS_TRIGGERS.forEach { connection.execSQL(it) }
                connection.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
            }
        }

        /**
         * Receipts and typing indicators stop being a narrowing of the global switch and become an
         * override of it, with "no override" as a third state.
         *
         * Every stored 1 was the column's default rather than a choice — nothing could tell the two
         * apart — so they become "follow the app", which behaves identically until the global switch
         * moves. A stored 0 was a deliberate "off here" and stays that way.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE chats SET read_receipts = 2 WHERE read_receipts = 1")
                connection.execSQL("UPDATE chats SET typing_indicators = 2 WHERE typing_indicators = 1")
            }
        }

        /** Room's own content-sync triggers for [MessageFts], as its generated schema spells them. */
        private val FTS_TRIGGERS = listOf(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON `messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON `messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
        )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "messages.db")
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
