package com.manjugroups.m_connect.network

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionsClientTest {
    @Test
    fun selectRouteAnchors_keepsMeaningfulTurnAndEndpoints() {
        val points = listOf(
            LatLng(13.0000, 80.0000),
            LatLng(13.0000, 80.0010),
            LatLng(13.0000, 80.0020),
            LatLng(13.0010, 80.0020),
            LatLng(13.0020, 80.0020),
        )

        val anchors = DirectionsClient.selectRouteAnchors(points, maxAnchors = 8)

        assertEquals(points.first(), anchors.first())
        assertEquals(points.last(), anchors.last())
        assertTrue(anchors.any { it == points[2] })
    }

    @Test
    fun selectRouteAnchors_boundsCallsAndRemovesNearDuplicates() {
        val points = buildList {
            repeat(30) { index -> add(LatLng(13.0 + index * 0.0001, 80.0)) }
            add(LatLng(13.00290001, 80.0))
        }

        val anchors = DirectionsClient.selectRouteAnchors(points, maxAnchors = 6)

        assertTrue(anchors.size in 2..6)
        assertEquals(points.first(), anchors.first())
        assertEquals(points[29], anchors.last())
    }
}
