package com.manjugroups.m_connect.geotrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocationPointEntity::class,
        PendingGeoTrackEventEntity::class,
        PendingChatMessageEntity::class,
    ],
    version = 3,
    exportSchema = false
)
abstract class GeoTrackDatabase : RoomDatabase() {
    abstract fun locationPointDao(): LocationPointDao
    abstract fun pendingGeoTrackEventDao(): PendingGeoTrackEventDao
    abstract fun pendingChatMessageDao(): PendingChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: GeoTrackDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventType TEXT NOT NULL,
                        metadataJson TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // V3 introduces the offline chat queue. Same shape as the
        // Room-generated CREATE; failing to add this would mean the
        // app starts crashing for every existing user on upgrade.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        localId TEXT NOT NULL,
                        conversationId TEXT,
                        channelId TEXT,
                        body TEXT NOT NULL,
                        parentMessageId TEXT,
                        createdAt INTEGER NOT NULL,
                        attemptCount INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): GeoTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GeoTrackDatabase::class.java,
                    "geotrack_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
