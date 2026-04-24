package com.manjugroups.m_connect.geotrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LocationPointEntity::class], version = 1, exportSchema = false)
abstract class GeoTrackDatabase : RoomDatabase() {
    abstract fun locationPointDao(): LocationPointDao

    companion object {
        @Volatile
        private var INSTANCE: GeoTrackDatabase? = null

        fun getInstance(context: Context): GeoTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GeoTrackDatabase::class.java,
                    "geotrack_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
