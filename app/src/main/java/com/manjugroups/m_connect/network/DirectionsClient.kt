package com.manjugroups.m_connect.network

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Driving-route helper. Calls our Convex backend (which proxies to the
 * Routes API using the server-side key), then decodes the encoded polyline
 * locally for rendering on the map.
 *
 * Returns null if the backend reports failure or the network call throws —
 * callers should fall back gracefully (e.g. straight-line + haversine).
 */
object DirectionsClient {

    data class DirectionsResult(
        val polyline: List<LatLng>,
        val distanceMeters: Int,
        val durationSeconds: Int,
        val distanceText: String,
        val durationText: String,
    )

    private val api by lazy { GeoTrackApi.create() }

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
