package com.manjugroups.m_connect.ui.telecaller

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DialerRecentCall(
    val number: String,
    val direction: String,
    val status: String,
    val startedAt: Long,
    val durationSeconds: Long,
)

class DialerRecentCallStore(context: Context, staffId: String?) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key = "calls_${staffId?.takeIf { it.isNotBlank() } ?: "current"}"
    private val gson = Gson()

    fun read(): List<DialerRecentCall> = runCatching {
        val raw = preferences.getString(key, null) ?: return emptyList()
        val type = object : TypeToken<List<DialerRecentCall>>() {}.type
        gson.fromJson<List<DialerRecentCall>>(raw, type).orEmpty()
    }.getOrDefault(emptyList())

    fun add(call: DialerRecentCall) {
        val updated = buildList {
            add(call)
            addAll(read())
        }.take(MAX_ITEMS)
        preferences.edit().putString(key, gson.toJson(updated)).apply()
    }

    private companion object {
        const val PREFS = "modern_dialer_recent_calls"
        const val MAX_ITEMS = 20
    }
}
