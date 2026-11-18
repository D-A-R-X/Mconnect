package com.manjugroups.m_connect.geotrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTrackFlightModeTest {
    @Test
    fun firstCheckReportsOnlyFlightModeOn() {
        assertTrue(GeoTrackFlightMode.shouldReport(null, isOn = true, firstCheck = true))
        assertFalse("a normal start doesn't log 'flight mode off'", GeoTrackFlightMode.shouldReport(null, isOn = false, firstCheck = true))
    }

    @Test
    fun eachRealChangeIsReportedOnce() {
        assertTrue(GeoTrackFlightMode.shouldReport(false, isOn = true, firstCheck = false))
        assertTrue(GeoTrackFlightMode.shouldReport(true, isOn = false, firstCheck = false))
        assertFalse("broadcast then heartbeat for the same state", GeoTrackFlightMode.shouldReport(true, isOn = true, firstCheck = false))
        assertFalse(GeoTrackFlightMode.shouldReport(false, isOn = false, firstCheck = false))
    }
}
