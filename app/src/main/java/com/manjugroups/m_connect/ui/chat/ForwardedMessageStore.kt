package com.manjugroups.m_connect.ui.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * Local persistent set of message IDs that this device sent via the
 * forward flow. Used to surface a "Forwarded" tag on chat bubbles —
 * WhatsApp-style — without requiring a backend schema change for an
 * `isForwarded` flag.
 *
 * Limitations (worth knowing):
 * - Only tracks forwards initiated FROM THIS device. Messages others
 *   forward to me will NOT show the tag until the backend gains a real
 *   forwarded-flag field.
 * - Wiped on app uninstall / data clear.
 * - Capped at [MAX_TRACKED] entries; oldest are evicted first to keep
 *   the prefs file from growing without bound on heavy forwarders.
 */
object ForwardedMessageStore {

    private const val PREFS_NAME = "forwarded_messages"
    private const val KEY_IDS = "ids"
    private const val MAX_TRACKED = 5000
    // Convex doc IDs are lowercase alphanumeric, so commas survive as a
    // safe separator without escaping.
    private const val SEP = ","

    @Volatile
    private var cached: LinkedHashSet<String>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded(context: Context): LinkedHashSet<String> {
        val existing = cached
        if (existing != null) return existing
        val raw = prefs(context).getString(KEY_IDS, null)
        val loaded = if (raw.isNullOrBlank()) {
            LinkedHashSet()
        } else {
            LinkedHashSet(raw.split(SEP).filter { it.isNotBlank() })
        }
        cached = loaded
        return loaded
    }

    fun isForwarded(context: Context, messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return false
        return ensureLoaded(context).contains(messageId)
    }

    @Synchronized
    fun markForwarded(context: Context, messageIds: Collection<String>) {
        val clean = messageIds.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        val set = ensureLoaded(context)
        set.addAll(clean)
        // Evict oldest if we've grown past the cap.
        while (set.size > MAX_TRACKED) {
            val iterator = set.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
        prefs(context).edit()
            .putString(KEY_IDS, set.joinToString(SEP))
            .apply()
    }

    fun markForwarded(context: Context, messageId: String) {
        markForwarded(context, listOf(messageId))
    }
}
