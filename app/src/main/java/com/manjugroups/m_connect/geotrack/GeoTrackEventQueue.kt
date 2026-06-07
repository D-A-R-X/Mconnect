package com.manjugroups.m_connect.geotrack

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingGeoTrackEventEntity
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TamperReportRequest

object GeoTrackEventQueue {
    private val gson = Gson()
    private val metadataType = object : TypeToken<Map<String, Any?>>() {}.type
    private const val PREFS = "geotrack_event_queue"
    private const val DUPLICATE_WINDOW_MS = 6 * 60 * 60 * 1000L

    suspend fun enqueue(
        context: Context,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
        occurredAt: Long = System.currentTimeMillis(),
    ) {
        val appContext = context.applicationContext
        val db = GeoTrackDatabase.getInstance(appContext)
        db.pendingGeoTrackEventDao().insert(
            PendingGeoTrackEventEntity(
                eventType = eventType,
                metadataJson = gson.toJson(metadata + ("ts" to occurredAt)),
                occurredAt = occurredAt,
            )
        )
    }

    suspend fun enqueueDistinct(
        context: Context,
        eventType: String,
        metadata: Map<String, Any?> = emptyMap(),
        signature: String = eventType,
        minIntervalMs: Long = DUPLICATE_WINDOW_MS,
        occurredAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "last_$signature"
        val last = prefs.getLong(key, 0L)
        if (occurredAt - last < minIntervalMs) return false

        enqueue(appContext, eventType, metadata, occurredAt)
        prefs.edit().putLong(key, occurredAt).apply()
        return true
    }

    suspend fun flush(
        context: Context,
        api: GeoTrackApi = GeoTrackApi.create(),
        session: SessionManager = SessionManager(context.applicationContext),
        limit: Int = 100,
    ): Int {
        if (!session.isLoggedIn) return 0
        val dao = GeoTrackDatabase.getInstance(context.applicationContext).pendingGeoTrackEventDao()
        val pending = dao.getPending(limit)
        if (pending.isEmpty()) return 0

        val sentIds = mutableListOf<Long>()
        for (event in pending) {
            val metadata = runCatching {
                gson.fromJson<Map<String, Any?>>(event.metadataJson, metadataType)
            }.getOrDefault(mapOf("ts" to event.occurredAt))

            val response = api.reportTamper(
                session.bearerToken,
                TamperReportRequest(event.eventType, metadata)
            )
            if (!response.success) break
            sentIds.add(event.id)
        }
        if (sentIds.isNotEmpty()) dao.deleteByIds(sentIds)
        return sentIds.size
    }
}
