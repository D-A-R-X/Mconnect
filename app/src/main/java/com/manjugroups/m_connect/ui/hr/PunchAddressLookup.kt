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
 */
internal object PunchAddressLookup {
    const val TIMEOUT_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastKey: String? = null
    @Volatile private var lastAddress: String? = null

    suspend fun resolve(context: Context, location: Location?, timeoutMs: Long = TIMEOUT_MS): String? {
        location ?: return null
        val key = cacheKey(location.latitude, location.longitude)
        if (key == lastKey) lastAddress?.let { return it }
        val appContext = context.applicationContext
        // Not a child of the caller: the timeout must be able to return while
        // the blocking lookup is still running.
        val lookup = scope.async { geocode(appContext, location) }
        val address = withTimeoutOrNull(timeoutMs) { lookup.await() }
        if (address != null) {
            lastKey = key
            lastAddress = address
        }
        return address
    }

    internal fun cacheKey(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.4f,%.4f", latitude, longitude)

    @Suppress("DEPRECATION")
    private fun geocode(context: Context, location: Location): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }
    } catch (_: Exception) {
        null
    }
}
