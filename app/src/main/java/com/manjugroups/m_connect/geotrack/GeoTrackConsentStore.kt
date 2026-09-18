package com.manjugroups.m_connect.geotrack

import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager

/**
 * Remembers, per staff member on this device, that they agreed to location
 * tracking.
 *
 * Consent used to live only in [SessionManager], which is reset on every login
 * and wiped by logout, session expiry and the base-URL purge. So staff who had
 * already agreed saw the full-screen "Location Tracking" disclosure again after
 * every sign-in. This store sits outside the session so the answer survives
 * those, and is keyed by staff id so a different person signing in on a shared
 * phone is still asked.
 */
object GeoTrackConsentStore {
    private const val PREFS_NAME = "geotrack_consent"
    private const val KEY_PREFIX = "agreed_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAgreed(context: Context, staffId: String?): Boolean {
        val id = staffId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return prefs(context).getBoolean(KEY_PREFIX + id, false)
    }

    fun recordAgreed(context: Context, staffId: String?) {
        val id = staffId?.trim().orEmpty()
        if (id.isEmpty()) return
        prefs(context).edit().putBoolean(KEY_PREFIX + id, true).apply()
    }

    /**
     * Brings [session]'s consent in line with the stored answer: restores an
     * earlier agreement, and records one given before this store existed so
     * current users are not asked again after their next sign-in.
     */
    fun reconcile(context: Context, session: SessionManager) {
        val staffId = session.staffId
        if (session.geoConsentGiven) {
            recordAgreed(context, staffId)
        } else if (hasAgreed(context, staffId)) {
            session.geoConsentGiven = true
            session.geoConsentDeclined = false
        }
    }
}
