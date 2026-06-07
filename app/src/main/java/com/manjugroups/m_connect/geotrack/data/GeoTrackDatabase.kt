package com.manjugroups.m_connect.geotrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationPointEntity::class, PendingGeoTrackEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class GeoTrackDatabase : RoomDatabase() {
    abstract fun locationPointDao(): LocationPointDao
    abstract fun pendingGeoTrackEventDao(): PendingGeoTrackEventDao

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

        fun getInstance(context: Context): GeoTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GeoTrackDatabase::class.java,
                    "geotrack_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
