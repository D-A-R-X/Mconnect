package com.manjugroups.m_connect.network

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * Driving-route helper. Calls our Convex backend (which proxies to the
 * Routes API using the server-side key), then decodes the encoded polyline
 * locally for rendering on the map.
 *
 * Returns null if the backend reports failure or the network call throws —
 * callers should fall back gracefully (e.g. straight-line + haversine).
 */
object DirectionsClient {

    data class GeocodeResult(
        val latLng: LatLng,
        val formattedAddress: String?,
        val name: String?,
    )

    data class DirectionsResult(
        val polyline: List<LatLng>,
        val distanceMeters: Int,
        val durationSeconds: Int,
        val distanceText: String,
        val durationText: String,
    )

    data class RoadMatchedLeg(
        val points: List<LatLng>,
        val isRoadMatched: Boolean,
    )

    data class RoadMatchedTrail(
        val legs: List<RoadMatchedLeg>,
        val sourcePointCount: Int,
        val anchorCount: Int,
    ) {
        val matchedLegCount: Int get() = legs.count { it.isRoadMatched }
        val isFullyMatched: Boolean get() = legs.isNotEmpty() && matchedLegCount == legs.size
    }

    private val api by lazy { GeoTrackApi.create() }

    suspend fun geocodeAddress(
        bearerToken: String,
        address: String,
    ): GeocodeResult? = withContext(Dispatchers.IO) {
        if (bearerToken.isBlank() || address.isBlank()) return@withContext null
        try {
            val resp = api.geocodeAddress(
                bearerToken,
                GeocodeAddressRequest(address = address),
            )
            val lat = resp.lat
            val lng = resp.lng
            if (!resp.success || lat == null || lng == null) {
                android.util.Log.w(
                    "DirectionsClient",
                    "Backend geocode failed: ${resp.error ?: "no coordinates"}"
                )
                return@withContext null
            }
            GeocodeResult(
                latLng = LatLng(lat, lng),
                formattedAddress = resp.formattedAddress,
                name = resp.name,
            )
        } catch (e: Exception) {
            android.util.Log.w("DirectionsClient", "Backend geocode call failed", e)
            null
        }
    }

    suspend fun fetchDriving(
        bearerToken: String,
        origin: LatLng,
        dest: LatLng,
    ): DirectionsResult? = withContext(Dispatchers.IO) {
        if (bearerToken.isBlank()) return@withContext null
        try {
            val resp = api.getRoute(
                bearerToken,
                RouteRequest(
                    originLat = origin.latitude,
                    originLng = origin.longitude,
                    destLat = dest.latitude,
                    destLng = dest.longitude,
                )
            )
            val encoded = resp.encodedPolyline
            if (!resp.success || encoded.isNullOrBlank()) {
                android.util.Log.w(
                    "DirectionsClient",
                    "Backend route failed: ${resp.error ?: "no polyline"}"
                )
                return@withContext null
            }
            val distMeters = (resp.distanceMeters ?: 0.0).toInt()
            val durSeconds = (resp.durationSeconds ?: 0.0).toInt()
            DirectionsResult(
                polyline = decodePolyline(encoded),
                distanceMeters = distMeters,
                durationSeconds = durSeconds,
                distanceText = formatDistance(distMeters),
                durationText = formatDuration(durSeconds),
            )
        } catch (e: Exception) {
            android.util.Log.w("DirectionsClient", "Backend route call failed", e)
            null
        }
    }

    /**
     * Builds a display-only road route through ordered anchors from the recorded GPS trail.
     * The original samples remain the source of truth for trip evidence and distance.
     */
    suspend fun fetchRoadMatchedTrail(
        bearerToken: String,
        recordedPoints: List<LatLng>,
        maxAnchors: Int = 8,
    ): RoadMatchedTrail? {
        if (bearerToken.isBlank() || recordedPoints.size < 2) return null
        val anchors = selectRouteAnchors(recordedPoints, maxAnchors)
        if (anchors.size < 2) return null

        val legs = coroutineScope {
            anchors.zipWithNext().map { (origin, destination) ->
                async {
                    val routed = fetchDriving(bearerToken, origin, destination)
                    if (routed != null && routed.polyline.size >= 2) {
                        RoadMatchedLeg(routed.polyline, isRoadMatched = true)
                    } else {
                        RoadMatchedLeg(listOf(origin, destination), isRoadMatched = false)
                    }
                }
            }.awaitAll()
        }
        return RoadMatchedTrail(legs, recordedPoints.size, anchors.size)
    }

    internal fun selectRouteAnchors(points: List<LatLng>, maxAnchors: Int): List<LatLng> {
        val limit = max(2, maxAnchors)
        val cleaned = points.fold(mutableListOf<LatLng>()) { result, point ->
            if (result.isEmpty() || distanceMeters(result.last(), point) >= 5.0) result += point
            result
        }
        if (cleaned.size <= 2) return cleaned

        var toleranceMeters = 12.0
        var simplified = simplify(cleaned, toleranceMeters)
        while (simplified.size > limit && toleranceMeters < 250.0) {
            toleranceMeters *= 1.5
            simplified = simplify(cleaned, toleranceMeters)
        }
        if (simplified.size <= limit) return simplified

        val lastIndex = simplified.lastIndex
        return (0 until limit).map { slot ->
            simplified[(slot * lastIndex.toDouble() / (limit - 1)).toInt()]
        }.distinct()
    }

    private fun simplify(points: List<LatLng>, toleranceMeters: Double): List<LatLng> {
        if (points.size <= 2) return points
        var furthestIndex = -1
        var furthestDistance = 0.0
        for (index in 1 until points.lastIndex) {
            val distance = distanceToSegmentMeters(points[index], points.first(), points.last())
            if (distance > furthestDistance) {
                furthestDistance = distance
                furthestIndex = index
            }
        }
        if (furthestIndex < 0 || furthestDistance <= toleranceMeters) {
            return listOf(points.first(), points.last())
        }
        val before = simplify(points.subList(0, furthestIndex + 1), toleranceMeters)
        val after = simplify(points.subList(furthestIndex, points.size), toleranceMeters)
        return before.dropLast(1) + after
    }

    private fun distanceToSegmentMeters(point: LatLng, start: LatLng, end: LatLng): Double {
        val referenceLat = (start.latitude + end.latitude + point.latitude) / 3.0
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLng = metersPerDegreeLat * cos(referenceLat * PI / 180.0)
        val px = (point.longitude - start.longitude) * metersPerDegreeLng
        val py = (point.latitude - start.latitude) * metersPerDegreeLat
        val ex = (end.longitude - start.longitude) * metersPerDegreeLng
        val ey = (end.latitude - start.latitude) * metersPerDegreeLat
        val lengthSquared = ex * ex + ey * ey
        if (lengthSquared == 0.0) return kotlin.math.sqrt(px * px + py * py)
        val projection = ((px * ex + py * ey) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - projection * ex
        val dy = py - projection * ey
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun distanceMeters(first: LatLng, second: LatLng): Double {
        val latMeters = (second.latitude - first.latitude) * 111_320.0
        val meanLat = (first.latitude + second.latitude) / 2.0 * PI / 180.0
        val lngMeters = (second.longitude - first.longitude) * 111_320.0 * cos(meanLat)
        return kotlin.math.sqrt(latMeters * latMeters + lngMeters * lngMeters)
    }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000.0)
        else "$meters m"

    private fun formatDuration(seconds: Int): String {
        val minutes = (seconds / 60.0).let { if (it < 1) 1 else it.toInt() }
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h hr" else "$h hr $m min"
    }

    /**
     * Decodes Google's encoded polyline algorithm.
     * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>(encoded.length / 2)
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            poly.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return poly
    }
}
