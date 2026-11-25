package com.manjugroups.m_connect.ui.hr

import java.util.Locale

/**
 * Short place name shown on the clock-in / clock-out selfie overlay, e.g.
 * "Ekkattuthangal, Chennai". Display only: the punch still sends the raw
 * latitude / longitude and the full address line unchanged.
 *
 * Built from the area (sub-locality) and the city (locality, or the district
 * when the geocoder gives no city). At most two parts, never the street or
 * house number. When nothing usable comes back the overlay shows
 * [compactCoordinates] instead, so the photo never loses its location.
 */
internal object PunchPlaceLabel {
    private const val MAX_PART_LENGTH = 32

    fun shortName(subLocality: String?, locality: String?, subAdminArea: String?): String? {
        val area = clean(subLocality)
        val city = clean(locality) ?: clean(subAdminArea)
        // Area equal to the city ("Coimbatore, Coimbatore") shows the city once.
        val parts = if (area != null && city != null && area.equals(city, ignoreCase = true)) {
            listOf(city)
        } else {
            listOfNotNull(area, city)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun compactCoordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    /** The overlay line: place name when known, else the compact coordinates. */
    fun overlayText(placeName: String?, latitude: Double?, longitude: Double?): String {
        placeName?.takeIf { it.isNotBlank() }?.let { return it }
        if (latitude == null || longitude == null) return "Location unavailable"
        return compactCoordinates(latitude, longitude)
    }

    private fun clean(raw: String?): String? {
        val value = raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (value.isEmpty()) return null
        // Postcodes and plus codes ("600032", "2WX4+2M") are not place names.
        if (value.none { it.isLetter() } || PLUS_CODE.matches(value)) return null
        return if (value.length <= MAX_PART_LENGTH) value
        else value.take(MAX_PART_LENGTH - 1).trimEnd() + "…"
    }

    private val PLUS_CODE = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,3}\\b.*", RegexOption.IGNORE_CASE)
}
