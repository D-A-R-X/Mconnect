package com.manjugroups.m_connect.attendance

import androidx.sqlite.db.SupportSQLiteDatabase
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PendingPunchMigrationTest {
    @Test fun versionEightOnlyAddsNullableColumnsAndNeverDropsQueuedEvidence() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            check(method.name == "execSQL")
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        GeoTrackDatabase.MIGRATION_7_8.migrate(db)
        assertEquals(listOf(
            "ALTER TABLE pending_punches ADD COLUMN uploadedPhotoId TEXT",
            "ALTER TABLE pending_punches ADD COLUMN reviewReason TEXT",
        ), statements)
        assertFalse(statements.any { "DROP" in it || "DELETE" in it })
    }
}
