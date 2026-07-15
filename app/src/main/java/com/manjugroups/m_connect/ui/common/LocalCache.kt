package com.manjugroups.m_connect.ui.common

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

/**
 * Tiny disk cache for API responses, so data-loading screens can paint
 * INSTANTLY from the last-known snapshot and then refresh in the background
 * ("cache-first" loading). This removes the long blank/skeleton wait that made
 * the app feel slow / "almost crashing" on Home, Chat, and the list screens.
 *
 * Design (mirrors the app's existing filesDir-JSON caches — ChatMetadataCache,
 * ChatMessageCache — so it scales to large lists, unlike SharedPreferences):
 *  - One JSON file per key under filesDir/response_cache/.
 *  - A dumb key -> JSON store: callers namespace their own keys (include the
 *    signed-in staffId and any date/scope) so one user never reads another's
 *    cached data. [clearAll] is also called on logout as a safety net.
 *  - Each entry stores a timestamp so callers can treat very old data as
 *    stale ([getEntry]); [get] ignores age.
 *  - Every op is wrapped so a miss / corrupt file / IO error never crashes a
 *    screen — the network path stays the source of truth.
 */
object LocalCache {

    private const val DIR = "response_cache"
    private val gson = Gson()

    data class Entry<T>(val value: T, val savedAt: Long)

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun file(ctx: Context, key: String): File {
        // Sanitize so an arbitrary key is always a valid filename.
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir(ctx), "$safe.json")
    }

    /** Store [value] under [key] (JSON, with a save timestamp). */
    fun put(ctx: Context, key: String, value: Any, nowMs: Long) {
        runCatching {
            val wrapped = mapOf("v" to value, "t" to nowMs)
            file(ctx, key).writeText(gson.toJson(wrapped))
        }
    }

    /** Cached value for [key], or null if absent/unparseable. Ignores age. */
    fun <T : Any> get(ctx: Context, key: String, type: Type): T? = getEntry<T>(ctx, key, type)?.value

    /** Cached value + when it was saved, so callers can gate on staleness. */
    fun <T : Any> getEntry(ctx: Context, key: String, type: Type): Entry<T>? = runCatching {
        val f = file(ctx, key)
        if (!f.exists()) return null
        val obj = gson.fromJson(f.readText(), com.google.gson.JsonObject::class.java) ?: return null
        val valueEl = obj.get("v") ?: return null
        val value = gson.fromJson<T>(valueEl, type) ?: return null
        Entry(value, obj.get("t")?.asLong ?: 0L)
    }.getOrNull()

    inline fun <reified T : Any> get(ctx: Context, key: String): T? =
        get(ctx, key, object : TypeToken<T>() {}.type)

    /** Wipe everything — call on logout so a new user starts clean. */
    fun clearAll(ctx: Context) {
        runCatching { dir(ctx).deleteRecursively() }
    }
}
