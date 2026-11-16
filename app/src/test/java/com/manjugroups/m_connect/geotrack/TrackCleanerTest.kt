package com.manjugroups.m_connect.geotrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class TrackCleanerTest {

    private val lat0 = 13.04
    private val lng0 = 80.2
    private val t0 = 1_794_800_000_000L

    /** dx metres east, dy metres north of the origin, t seconds in. */
    private fun at(dx: Double, dy: Double, t: Int, accuracy: Double = 15.0, mock: Boolean = false) =
        TrackCleaner.Fix(
            lat = lat0 + dy / 111_320,
            lng = lng0 + dx / (111_320 * cos(lat0 * Math.PI / 180)),
            accuracy = accuracy,
            atMs = t0 + t * 1000L,
            isMock = mock,
        )

    private fun jitter(seed: Int, r: Double): Double {
        val x = sin(seed * 12.9898) * 43758.5453
        return (x - floor(x)) * 2 * r - r
    }

    private fun length(path: List<TrackCleaner.Point>): Double =
        path.zipWithNext { a, b -> TrackCleaner.distanceM(a.lat, a.lng, b.lat, b.lng) }.sum()

    @Test
    fun stopIsOnePointHoweverMuchItWanders() {
        val stay = (0 until 40).map { at(jitter(it, 50.0), jitter(it + 100, 50.0), it * 30, 40.0 + (it % 5) * 10) }
        val path = TrackCleaner.clean(stay)
        assertEquals(1, path.size)
        assertTrue(TrackCleaner.distanceM(path[0].lat, path[0].lng, lat0, lng0) < 30)
        assertEquals(t0, path[0].atMs)
    }

    @Test
    fun driveKeepsEveryFix() {
        val drive = (0 until 30).map { at(it * 80.0, 0.0, it * 10) }
        assertEquals(30, TrackCleaner.clean(drive).size)
    }

    @Test
    fun slowWalkStaysAWalk() {
        val walk = (0 until 60).map { at(0.0, it * 13.0, it * 10) }
        assertTrue(length(TrackCleaner.clean(walk)) > 700)
    }

    @Test
    fun driveStopDriveGivesOneStopPoint() {
        val driveIn = (0 until 10).map { at(-1000.0 + it * 100, 0.0, it * 10) }
        val stop = (0 until 20).map { at(jitter(it, 40.0), jitter(it + 7, 40.0), 100 + it * 30, 50.0) }
        val driveOut = (0 until 10).map { at(100.0 + it * 100, 0.0, 700 + it * 10) }
        assertEquals(21, TrackCleaner.clean(driveIn + stop + driveOut).size)
    }

    @Test
    fun outAndBackSpikeDropped() {
        val pts = listOf(at(0.0, 0.0, 0), at(80.0, 0.0, 10), at(160.0, 0.0, 20), at(240.0, 600.0, 30, 30.0),
            at(320.0, 0.0, 40), at(400.0, 0.0, 50))
        assertEquals(5, TrackCleaner.clean(pts).size)
    }

    @Test
    fun strayFixInsideAStopIsNotDrawn() {
        val pts = listOf(at(0.0, 0.0, 0), at(5.0, 3.0, 300), at(-4.0, 2.0, 600), at(0.0, 110.0, 900, 50.0),
            at(3.0, -2.0, 1200), at(-2.0, 4.0, 1500))
        assertEquals(1, TrackCleaner.clean(pts).size)
    }

    @Test
    fun realTripAwayFromAStopIsKept() {
        val pts = (0 until 10).map { at(0.0, 0.0, it * 30) } +
            listOf(at(400.0, 0.0, 330), at(800.0, 0.0, 360)) +
            (0 until 10).map { at(800.0, 0.0, 390 + it * 30) }
        assertTrue(length(TrackCleaner.clean(pts)) > 700)
    }

    @Test
    fun badFixesDropped() {
        val pts = listOf(
            at(0.0, 0.0, 0),
            at(50.0, 0.0, 10, mock = true),
            TrackCleaner.Fix(0.0, 0.0, null, t0 + 20_000),
            at(100.0, 0.0, 30, 250.0),
            at(50_000.0, 0.0, 40),
            at(80.0, 0.0, 50),
        )
        assertEquals(2, TrackCleaner.clean(pts).size)
    }

    @Test
    fun inaccurateFixesKeptWhenThatIsAllThereIs() {
        val pts = listOf(at(0.0, 0.0, 0, 300.0), at(500.0, 0.0, 60, 300.0), at(1000.0, 0.0, 120, 300.0))
        assertEquals(3, TrackCleaner.clean(pts).size)
    }
}
