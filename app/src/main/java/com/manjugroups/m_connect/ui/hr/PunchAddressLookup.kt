package com.manjugroups.m_connect.ui.hr

import android.content.Context
import android.location.Geocoder
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Street address for a Clock In location.
 *
 * Geocoder.getFromLocation is a blocking network call. It ran untimed between
 * taking the selfie and opening the confirm screen (on the main thread in the
 * camera screen), so on a weak network Clock In waited many seconds for it.
 * It now runs on a background thread and is given up after [TIMEOUT_MS]; the
 * address is optional for a punch. The last answer is reused for the same
 * spot (about 11 m), since staff usually clock in where the map already looked.
 *
 * The same lookup also yields the short place name for the selfie overlay
 * ([resolvePlaceName], see [PunchPlaceLabel]); the confirm screen usually gets
 * it from this cache without a second network call.
 */
internal object PunchAddressLookup {
    const val TIMEOUT_MS = 2_500L

    private class Resolved(val addressLine: String?, val placeName: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastKey: String? = null
    @Volatile private var last: Resolved? = null

    suspend fun resolve(context: Context, location: Location?, timeoutMs: Long = TIMEOUT_MS): String? {
        location ?: return null
        return lookup(context, location.latitude, location.longitude, timeoutMs)?.addressLine
    }

    /** Short "Area, City" label for the selfie overlay, or null if unknown in time. */
    suspend fun resolvePlaceName(
        context: Context,
        latitude: Double,
        longitude: Double,
        timeoutMs: Long = TIMEOUT_MS,
    ): String? = lookup(context, latitude, longitude, timeoutMs)?.placeName

    private suspend fun lookup(context: Context, latitude: Double, longitude: Double, timeoutMs: Long): Resolved? {
        val key = cacheKey(latitude, longitude)
        if (key == lastKey) last?.let { return it }
        val appContext = context.applicationContext
        // Not a child of the caller: the timeout must be able to return while
        // the blocking lookup is still running.
        val lookup = scope.async { geocode(appContext, latitude, longitude) }
        val resolved = withTimeoutOrNull(timeoutMs) { lookup.await() }
        if (resolved != null) {
            last = resolved
            lastKey = key
        }
        return resolved
    }

    internal fun cacheKey(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.4f,%.4f", latitude, longitude)

    @Suppress("DEPRECATION")
    private fun geocode(context: Context, latitude: Double, longitude: Double): Resolved? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    Resolved(
                        addressLine = address.getAddressLine(0),
                        placeName = PunchPlaceLabel.shortName(
                            subLocality = address.subLocality,
                            locality = address.locality,
                            subAdminArea = address.subAdminArea,
                        ),
                    )
                }
                ?.takeIf { it.addressLine != null || it.placeName != null }
        }
    } catch (_: Exception) {
        null
    }
}
