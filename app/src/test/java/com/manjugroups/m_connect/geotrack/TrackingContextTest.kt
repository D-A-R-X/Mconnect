package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.auth.FieldActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trip attribution for GeoTrack telemetry.
 *
 * The app runs ONE tracking session per attendance day, so a CP trip or an
 * on-duty trip is a stretch of that session rather than a session of its own.
 * These values are what let the backend say which stretch a point belongs to —
 * without them a trip's distance can only be guessed from a time window, which
 * is what forced the straight-line fallbacks documented in
 * `reports/GEOTRACK_CONVEX_CUTOVER.md`.
 */
class TrackingContextTest {

    private fun activity(kind: String, refId: String? = "ref_1") =
        FieldActivity(kind = kind, title = "t", sub = null, startMs = 1L, refId = refId)

    @Test
    fun `no field activity is the plain shift`() {
        val ctx = TrackingContext.fromFieldActivity(null)
        assertEquals(TrackingContext.ATTENDANCE, ctx.contextType)
        // Deliberately null: the session already carries the attendance row id
        // as its own contextId, so repeating it per point adds nothing and
        // could disagree with the session across a day rollover.
        assertNull(ctx.contextId)
    }

    @Test
    fun `each field activity maps to its backend segment type`() {
        assertEquals(TrackingContext.ON_DUTY, TrackingContext.fromFieldActivity(activity("onduty")).contextType)
        assertEquals(TrackingContext.CP_TRIP, TrackingContext.fromFieldActivity(activity("cp")).contextType)
        assertEquals(TrackingContext.SITE_VISIT, TrackingContext.fromFieldActivity(activity("sv")).contextType)
        assertEquals(TrackingContext.FLEET_TRIP, TrackingContext.fromFieldActivity(activity("fleet")).contextType)
    }

    @Test
    fun `the trip id is carried through`() {
        assertEquals("ref_1", TrackingContext.fromFieldActivity(activity("cp")).contextId)
        assertEquals("trip_9", TrackingContext.fromFieldActivity(activity("onduty", "trip_9")).contextId)
    }

    @Test
    fun `an activity whose id has not arrived yet still names the trip type`() {
        // On-duty sets the activity immediately and learns its geoTrips id only
        // when the backend answers. The points in between must still be tagged
        // on_duty rather than silently counting as plain shift time.
        val ctx = TrackingContext.fromFieldActivity(activity("onduty", refId = null))
        assertEquals(TrackingContext.ON_DUTY, ctx.contextType)
        assertNull(ctx.contextId)
    }

    @Test
    fun `a blank id is treated as absent`() {
        assertNull(TrackingContext.fromFieldActivity(activity("cp", "   ")).contextId)
        assertNull(TrackingContext.fromFieldActivity(activity("cp", "")).contextId)
    }

    @Test
    fun `kind matching is case and space insensitive`() {
        assertEquals(TrackingContext.ON_DUTY, TrackingContext.fromFieldActivity(activity(" OnDuty ")).contextType)
    }

    @Test
    fun `an unknown kind falls back to the shift instead of inventing a type`() {
        // A newer flow could set an activity this build has never heard of.
        // Sending its raw kind as a segment type would be rejected backend-side
        // and lose the point's attribution entirely; the shift is the honest
        // answer, and the id is dropped with it so it cannot be misread.
        val ctx = TrackingContext.fromFieldActivity(activity("inspection"))
        assertEquals(TrackingContext.ATTENDANCE, ctx.contextType)
        assertNull(ctx.contextId)
    }
}
