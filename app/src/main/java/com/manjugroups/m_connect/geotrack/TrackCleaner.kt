package com.manjugroups.m_connect.geotrack

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a staff member's raw GPS fixes into the track worth drawing, the way
 * Google Timeline does before it draws a day. Same rules as the web's
 * lib/geotrack-clean-track.ts and the geo service's road snapping:
 *
 *  1. Drops fixes that can't be trusted: mock, (0,0), accuracy worse than
 *     100 m (kept when nothing better exists), or an impossible jump.
 *  2. Drops single-fix spikes: one fix that jumps out and straight back.
 *  3. Collapses every stay into ONE point. While a phone sits on a site its
 *     fixes wander 20–100 m around the real spot (and the activity sensor
 *     flips between walking / on foot / in vehicle), so joining them drew a
 *     star of criss-crossing lines over every stop. A stay = fixes within
 *     ~60 m (or their own accuracy, up to 100 m) of the running centre for
 *     3 minutes or more; a few strays that come straight back are part of it.
 *
 * Fixes without a time only get the validity filter.
 */
object TrackCleaner {

    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracy: Double? = null,
        val atMs: Long? = null,
        val isMock: Boolean = false,
    )

    /** A point to draw; a stay's point carries its arrival time. */
    data class Point(val lat: Double, val lng: Double, val atMs: Long?)

    const val MAX_ACCURACY_M = 100.0
    private const val MAX_PLAUSIBLE_SPEED_MPS = 55.0
    private const val SPIKE_MIN_M = 60.0
    private const val SPIKE_MAX_SPAN_MS = 10 * 60_000L // still phones report every ~5 min
    private const val STAY_RADIUS_M = 60.0
    private const val STAY_MAX_RADIUS_M = 100.0
    private const val STAY_MIN_MS = 3 * 60_000L
    private const val STAY_EXCURSION_POINTS = 3
    private const val STAY_EXCURSION_MS = 10 * 60_000L
    private const val STAY_EXCURSION_MAX_M = 250.0 // strays farther are a real trip

    private class F(val lat: Double, val lng: Double, val acc: Double, val at: Long?)

    fun clean(points: List<Fix>): List<Point> {
        if (points.isEmpty()) return emptyList()
        return collapseStays(dropSpikes(dropImpossibleJumps(valid(points))))
    }

    /** The geo service timeline, cleaned and ready to draw. */
    fun cleanTimeline(timeline: List<com.manjugroups.m_connect.network.TimelinePoint>): List<Point> =
        clean(timeline.map { Fix(it.lat, it.lng, it.accuracy, it.recordedAt, it.isMock == true) })

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val rad = Math.PI / 180
        val dLat = (lat2 - lat1) * rad
        val dLng = (lng2 - lng1) * rad
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * rad) * cos(lat2 * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    private fun valid(points: List<Fix>): List<F> {
        val all = points.filter {
            it.lat.isFinite() && it.lng.isFinite() && !(it.lat == 0.0 && it.lng == 0.0) &&
                it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && !it.isMock
        }.map { F(it.lat, it.lng, it.accuracy?.takeIf { a -> a.isFinite() && a > 0 } ?: 0.0, it.atMs) }
        val accurate = all.filter { it.acc <= MAX_ACCURACY_M }
        return if (accurate.size >= 2) accurate else all
    }

    private fun dropImpossibleJumps(fixes: List<F>): List<F> {
        val out = ArrayList<F>(fixes.size)
        for (f in fixes) {
            val last = out.lastOrNull()
            if (last?.at != null && f.at != null) {
                val dt = (f.at - last.at) / 1000.0
                val d = distanceM(last.lat, last.lng, f.lat, f.lng)
                if (dt < 0) continue
                if (dt == 0.0 && d > SPIKE_MIN_M) continue
                if (dt > 0 && d / dt > MAX_PLAUSIBLE_SPEED_MPS) continue
            }
            out += f
        }
        return out
    }

    private fun dropSpikes(fixes: List<F>): List<F> {
        var current = fixes
        repeat(3) {
            val out = ArrayList<F>(current.size)
            var removed = false
            for (i in current.indices) {
                val a = out.lastOrNull()
                val b = current[i]
                val c = current.getOrNull(i + 1)
                if (a != null && c != null) {
                    val ab = distanceM(a.lat, a.lng, b.lat, b.lng)
                    val bc = distanceM(b.lat, b.lng, c.lat, c.lng)
                    val ac = distanceM(a.lat, a.lng, c.lat, c.lng)
                    val threshold = maxOf(SPIKE_MIN_M, b.acc)
                    val quick = a.at == null || c.at == null || c.at - a.at <= SPIKE_MAX_SPAN_MS
                    if (quick && ab > threshold && bc > threshold && ac < 0.35 * min(ab, bc)) {
                        removed = true
                        continue
                    }
                }
                out += b
            }
            current = out
            if (!removed) return current
        }
        return current
    }

    private fun weight(f: F): Double {
        val a = maxOf(5.0, if (f.acc > 0) f.acc else 25.0)
        return 1 / (a * a)
    }

    private fun radius(f: F): Double = min(STAY_MAX_RADIUS_M, maxOf(STAY_RADIUS_M, f.acc))

    private fun collapseStays(fixes: List<F>): List<Point> {
        val out = ArrayList<Point>()
        var i = 0
        while (i < fixes.size) {
            val head = fixes[i]
            if (head.at == null) {
                out += Point(head.lat, head.lng, null)
                i++
                continue
            }
            var wSum = weight(head)
            var latSum = head.lat * wSum
            var lngSum = head.lng * wSum
            var cLat = head.lat
            var cLng = head.lng
            fun add(f: F) {
                val w = weight(f)
                wSum += w; latSum += f.lat * w; lngSum += f.lng * w
                cLat = latSum / wSum; cLng = lngSum / wSum
            }
            fun inside(f: F) = distanceM(cLat, cLng, f.lat, f.lng) <= radius(f)

            var lastIn = i
            var j = i + 1
            while (j < fixes.size) {
                val f = fixes[j]
                if (f.at == null) break
                if (inside(f)) {
                    add(f); lastIn = j; j++
                    continue
                }
                // A few stray fixes that come straight back still belong to the stay.
                val lastAt = fixes[lastIn].at!!
                var back = -1
                var k = j
                while (k < fixes.size && k <= j + STAY_EXCURSION_POINTS) {
                    val g = fixes[k]
                    if (g.at == null || g.at - lastAt > STAY_EXCURSION_MS) break
                    if (k > j && inside(g)) { back = k; break }
                    if (distanceM(cLat, cLng, g.lat, g.lng) > STAY_EXCURSION_MAX_M) break
                    k++
                }
                if (back < 0) break
                add(fixes[back]); lastIn = back; j = back + 1
            }

            if (lastIn > i && fixes[lastIn].at!! - head.at >= STAY_MIN_MS) {
                out += Point(cLat, cLng, head.at)
                i = lastIn + 1
            } else {
                out += Point(head.lat, head.lng, head.at)
                i++
            }
        }
        return out
    }
}
