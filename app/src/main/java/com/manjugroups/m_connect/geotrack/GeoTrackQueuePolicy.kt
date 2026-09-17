package com.manjugroups.m_connect.geotrack

/** Which queued GeoTrack commands can never succeed on retry. */
internal object GeoTrackQueuePolicy {
    /**
     * A stop for a session the service does not know. The session is already
     * gone, which is exactly what the stop wanted, so the event is complete.
     * Any other failure (network, 5xx, 401) stays queued.
     */
    fun isStopAlreadyGone(httpCode: Int): Boolean = httpCode == 404
}
