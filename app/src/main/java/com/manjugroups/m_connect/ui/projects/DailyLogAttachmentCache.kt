package com.manjugroups.m_connect.ui.projects

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.manjugroups.m_connect.network.DailyLogAttachment

/**
 * Device-local cache of daily-log attachments, keyed by the created log's id.
 *
 * Bridges the gap until the backend's `attachments` field is deployed to
 * production: the media itself IS already in Convex storage (the upload works
 * and /api/storage/serve streams it), we just remember which storage ids
 * belong to which log so the app can render them on this device.
 *
 * Limitation: per-device only. The web and other phones need the server-side
 * `attachments` field (one prod Convex deploy) to see them. Once that lands,
 * server attachments take precedence and this cache is simply ignored.
 */
object DailyLogAttachmentCache {
    private const val PREFS = "daily_log_attachments"
    private const val KEY = "map"
    private const val MAX_ENTRIES = 300
    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, List<DailyLogAttachment>>>() {}.type

    private fun readAll(ctx: Context): MutableMap<String, List<DailyLogAttachment>> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableMapOf()
        return runCatching {
            gson.fromJson<MutableMap<String, List<DailyLogAttachment>>>(json, mapType)
        }.getOrNull() ?: mutableMapOf()
    }

    fun put(ctx: Context, logId: String?, attachments: List<DailyLogAttachment>) {
        if (logId.isNullOrBlank() || attachments.isEmpty()) return
        val map = readAll(ctx)
        map[logId] = attachments
        val bounded = if (map.size > MAX_ENTRIES) {
            map.entries.toList().takeLast(MAX_ENTRIES).associateTo(mutableMapOf()) { it.key to it.value }
        } else {
            map
        }
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, gson.toJson(bounded, mapType)).apply()
        }
    }

    fun get(ctx: Context, logId: String?): List<DailyLogAttachment> {
        if (logId.isNullOrBlank()) return emptyList()
        return readAll(ctx)[logId].orEmpty()
    }
}
