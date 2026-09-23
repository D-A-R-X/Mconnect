package com.manjugroups.m_connect.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Turns a scanned site-visit QR into an in-app destination.
 *
 * The QR has always encoded a real URL —
 * `https://mg.theairix.com/site-visit/consulting/<id>` — so a generic scanner
 * (Google Lens, the camera app) could read it perfectly well; it just had
 * nowhere to send it, because the app declared no intent filter for that
 * address. Every such scan therefore opened the website instead of the app,
 * and the staff member had to come back and scan a second time from inside
 * M-Connect.
 *
 * The app now claims those links. The id is extracted here so the entry
 * activity, the scanner and any future caller all agree on one rule.
 */
object SiteVisitDeepLink {

    /** The QR's path. Kept identical to the marker the in-app scanner matches. */
    private const val CONSULTING_MARKER = "/site-visit/consulting/"

    /** The short form the older payloads used, still accepted by the scanner. */
    private const val SV_PREFIX = "SV:"

    private const val PREFS = "deeplink_prefs"
    private const val KEY_PENDING_SV = "pending_site_visit_id"

    /**
     * The site-visit id a link points at, or null when it points elsewhere.
     *
     * Tolerant of a trailing slash, a query string and a fragment, because a
     * scanner or a messaging app may append its own tracking parameters.
     */
    fun siteVisitIdFrom(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith(SV_PREFIX, ignoreCase = true)) {
            return raw.substringAfter(':').trim().ifEmpty { null }
        }
        val index = raw.indexOf(CONSULTING_MARKER, ignoreCase = true)
        if (index < 0) return null
        return raw.substring(index + CONSULTING_MARKER.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')
            .ifEmpty { null }
    }

    fun siteVisitIdFrom(uri: Uri?): String? = siteVisitIdFrom(uri?.toString())

    /**
     * Remembers a link that arrived before the app could act on it.
     *
     * A staff member who scans while signed out has to sign in first, and that
     * can restart the process, so this is held on disk rather than in memory -
     * otherwise the scan would be silently lost and they would blame the QR.
     */
    fun storePending(context: Context, siteVisitId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_SV, siteVisitId)
            .apply()
    }

    /** Returns the waiting link, if any, and clears it so it opens once. */
    fun consumePending(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING_SV, null)?.takeIf { it.isNotBlank() }
        if (pending != null) prefs.edit().remove(KEY_PENDING_SV).apply()
        return pending
    }

    /** Captures the site-visit link an intent carries, if it carries one. */
    fun capture(context: Context, intent: Intent?): Boolean {
        val id = siteVisitIdFrom(intent?.data) ?: return false
        storePending(context, id)
        return true
    }
}
