package com.manjugroups.m_connect.network

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads a timestamp as epoch milliseconds whether the server sent a number or
 * an ISO-8601 string.
 *
 * The GeoTrack endpoints were built against a backend that emitted epoch
 * millis, and every model here declares them as `Long`. Tracking has since
 * moved to the Go service (api-geo), which marshals Go `time.Time` the
 * idiomatic way — RFC 3339, e.g. `"2026-09-12T06:14:22.184Z"`. Gson cannot
 * parse that into a Long, so it throws and the WHOLE response is lost: the
 * GeoTrack Live map and the attendance route strip come back empty with no
 * visible error, which reads as "geo tracking is not working".
 *
 * Rather than pick a side, accept both. The app then works against whichever
 * backend answers, which also means the service can be corrected later without
 * needing an app release to match.
 *
 * Attach per field with `@JsonAdapter(EpochMillisAdapter::class)` — deliberately
 * not registered globally, so this leniency applies only to the timestamps that
 * actually cross this boundary.
 */
class EpochMillisAdapter : JsonDeserializer<Long?> {

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): Long? {
        if (json == null || json.isJsonNull) return null
        val primitive = runCatching { json.asJsonPrimitive }.getOrNull() ?: return null

        if (primitive.isNumber) return primitive.asLong

        val raw = primitive.asString?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // A numeric string — some payloads quote large integers to dodge the
        // JavaScript float precision limit.
        raw.toLongOrNull()?.let { return it }
        return parseIso(raw)
            ?: throw JsonParseException("Unrecognised timestamp: $raw")
    }

    private fun parseIso(raw: String): Long? {
        // Normalise a trailing offset so SimpleDateFormat's X pattern is not
        // needed on older API levels, and clamp fractional seconds to the three
        // digits SSS accepts — Go emits a variable number of them, and anything
        // other than exactly three makes the parse fail.
        val normalized = normalizeFraction(raw)
        for (pattern in ISO_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply {
                        isLenient = false
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    .parse(normalized)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /** `…:22.184376Z` → `…:22.184Z`; `…:22Z` is left alone. */
    private fun normalizeFraction(raw: String): String {
        val dot = raw.indexOf('.')
        if (dot < 0) return raw
        var end = dot + 1
        while (end < raw.length && raw[end].isDigit()) end++
        val digits = raw.substring(dot + 1, end)
        if (digits.length == 3) return raw
        val padded = digits.padEnd(3, '0').take(3)
        return raw.substring(0, dot + 1) + padded + raw.substring(end)
    }

    private companion object {
        /**
         * `Z` is QUOTED on purpose in the UTC patterns: the timezone is forced
         * to UTC above, so the letter is matched literally. An unquoted `Z`
         * would be read as a general timezone and silently shift the instant —
         * the same defect that once made collection entries display 5:30 early.
         */
        val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
    }
}
