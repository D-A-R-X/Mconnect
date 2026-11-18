package com.manjugroups.m_connect.geotrack

/**
 * Flight-mode reporting rule (pure, unit-tested). Report a change exactly once;
 * on the very first check of a service start only an ON state is news (a normal
 * start must not log "flight mode off").
 */
object GeoTrackFlightMode {
    fun shouldReport(lastReported: Boolean?, isOn: Boolean, firstCheck: Boolean): Boolean = when {
        lastReported == isOn -> false
        lastReported == null || firstCheck -> isOn
        else -> true
    }
}
