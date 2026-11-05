package com.manjugroups.m_connect.network

import com.google.android.gms.maps.model.LatLng
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** GET {geo}/api/geotrack/snapped-route: the day's route matched to roads. */
data class SnappedRouteResponse(
    val success: Boolean = false,
    val data: SnappedRouteData? = null,
    val error: String? = null,
)

data class SnappedRouteData(
    val segments: List<SnappedSegment> = emptyList(),
    val isFinal: Boolean = false,
)

/**
 * One piece of the route. kind: snapped / routed (follows roads, draw solid),
 * gap / estimated (inferred or no road data, draw dashed).
 */
data class SnappedSegment(
    val kind: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val encodedPolyline: String? = null,
) {
    val isDashed: Boolean get() = kind == "gap" || kind == "estimated"
    val startMs: Long? get() = SnappedRoute.parseTime(startedAt)
    val endMs: Long? get() = SnappedRoute.parseTime(endedAt)
    val path: List<LatLng> get() = SnappedRoute.decode(encodedPolyline.orEmpty())
}

object SnappedRoute {
    /** Google encoded polyline (precision 5) → points. */
    fun decode(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            val values = LongArray(2)
            for (axis in 0..1) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return out
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                values[axis] = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            lat += values[0]
            lng += values[1]
            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    /** RFC 3339 (Go's time format, any fraction length) → epoch millis. */
    fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        // Trim the fraction to milliseconds so one pattern fits all lengths.
        val normalized = Regex("""\.(\d{3})\d*""").replace(value, ".$1")
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(normalized)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /**
     * The road-following path for a time window (e.g. one trip): the solid
     * segments overlapping it, joined in order. Empty when none.
     */
    fun pathBetween(segments: List<SnappedSegment>, fromMs: Long, toMs: Long): List<LatLng> {
        val out = ArrayList<LatLng>()
        segments.forEach { seg ->
            val start = seg.startMs ?: return@forEach
            val end = seg.endMs ?: return@forEach
            if (seg.isDashed || end < fromMs || start > toMs) return@forEach
            val path = seg.path
            if (out.isNotEmpty() && path.isNotEmpty() && out.last() == path.first()) out.addAll(path.drop(1))
            else out.addAll(path)
        }
        return out
    }
}
