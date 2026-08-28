package com.manjugroups.m_connect.ui.library.collections

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Collection timestamps arrive as UTC instants — the server writes them with
 * `new Date().toISOString()`.
 *
 * The reported bug: an entry made at 11:05 AM IST displayed as 05:35 AM. The
 * parser's `'Z'` is QUOTED, so it matches the literal letter Z rather than
 * meaning UTC, and SimpleDateFormat then parses in the device's own timezone.
 * The UTC wall-clock time was read as if it were already local, losing 5:30.
 *
 * These tests exercise the parse/format pair directly rather than the mapper,
 * because the mapper's formatter renders in the device timezone and a unit test
 * cannot rely on the JVM running in IST.
 */
class CollectionTimestampTest {

    private fun utcParser(pattern: String) =
        SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun istFormatter() =
        SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }

    @Test
    fun `a UTC instant renders as the real IST wall-clock time`() {
        val parsed = utcParser("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .parse("2026-08-28T05:35:00.000Z")!!
        // 05:35 UTC + 5:30 = 11:05 IST. This is the exact case that was
        // reported as "showing 5 am".
        assertEquals("Aug 28, 2026 • 11:05 AM", istFormatter().format(parsed))
    }

    @Test
    fun `the millis-less shape parses the same way`() {
        val parsed = utcParser("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .parse("2026-08-28T05:35:00Z")!!
        assertEquals("Aug 28, 2026 • 11:05 AM", istFormatter().format(parsed))
    }

    @Test
    fun `an evening UTC instant rolls into the next IST day`() {
        // 20:00 UTC on the 27th is 01:30 IST on the 28th. Reading it as local
        // would have shown the wrong DATE, not just the wrong time.
        val parsed = utcParser("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .parse("2026-08-27T20:00:00.000Z")!!
        assertEquals("Aug 28, 2026 • 01:30 AM", istFormatter().format(parsed))
    }

    @Test
    fun `parsing without the UTC zone is what produced the wrong time`() {
        // Pins the actual defect: same input, no timeZone set, interpreted in
        // IST — 5:30 earlier than the truth.
        val naive = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
            .parse("2026-08-28T05:35:00.000Z")!!
        val correct = utcParser("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .parse("2026-08-28T05:35:00.000Z")!!
        assertEquals(5L * 60 + 30, (correct.time - naive.time) / 60_000)
    }
}
