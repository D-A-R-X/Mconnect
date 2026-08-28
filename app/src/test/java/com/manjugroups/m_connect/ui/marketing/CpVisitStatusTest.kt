package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A CP visit and its spawned field visit are separate rows with separate
 * lifecycles, and the card has to pick one to display.
 *
 * The reported bug: a completed CP kept showing "Enroute" with a "Start Trip"
 * action and never closed. The app closes the field visit in a SECOND call
 * after recording the outcome; when that call fails the CP is completed
 * server-side while its field visit is stuck at "in-progress" — and both
 * screens preferred the field visit, so the finished CP read as a live trip
 * forever, on every reload.
 */
class CpVisitStatusTest {

    @Test
    fun `a live trip is described by the field visit, not the CP row`() {
        // Only the field visit tracks "arrived". Preferring the CP row here
        // would drop the user back on Start Trip after they verified arrival.
        assertEquals(
            "arrived",
            resolveCpEffectiveStatus(cpStatus = "in_progress", fieldVisitStatus = "arrived"),
        )
        assertEquals(
            "in-progress",
            resolveCpEffectiveStatus(cpStatus = "scheduled", fieldVisitStatus = "in-progress"),
        )
    }

    @Test
    fun `a completed CP stays completed even when its trip row is stale`() {
        // The exact reported failure.
        assertEquals(
            "completed",
            resolveCpEffectiveStatus(cpStatus = "completed", fieldVisitStatus = "in-progress"),
        )
        assertEquals(
            "completed",
            resolveCpEffectiveStatus(cpStatus = "completed", fieldVisitStatus = "arrived"),
        )
    }

    @Test
    fun `a cancelled CP is not resurrected by an open trip row`() {
        assertEquals(
            "cancelled",
            resolveCpEffectiveStatus(cpStatus = "cancelled", fieldVisitStatus = "in-progress"),
        )
    }

    @Test
    fun `a postponed CP is not shown as a live trip`() {
        assertEquals(
            "postponed",
            resolveCpEffectiveStatus(cpStatus = "postponed", fieldVisitStatus = "arrived"),
        )
    }

    @Test
    fun `a held CP reads as pending approval, not completed`() {
        // The app closes the field visit BEFORE the GM decides, so the trip row
        // says completed while the CP is actually waiting on approval.
        assertEquals(
            "pending_gm_approval",
            resolveCpEffectiveStatus(
                cpStatus = "pending_gm_approval",
                fieldVisitStatus = "completed",
            ),
        )
    }

    @Test
    fun `casing from the backend does not defeat the terminal check`() {
        assertEquals(
            "Completed",
            resolveCpEffectiveStatus(cpStatus = "Completed", fieldVisitStatus = "in-progress"),
        )
    }

    @Test
    fun `the CP row is used when there is no trip row yet`() {
        assertEquals(
            "scheduled",
            resolveCpEffectiveStatus(cpStatus = "scheduled", fieldVisitStatus = null),
        )
        assertEquals(
            "in_progress",
            resolveCpEffectiveStatus(cpStatus = "in_progress", fieldVisitStatus = "  "),
        )
    }

    @Test
    fun `nothing known falls back to scheduled rather than blank`() {
        assertEquals("scheduled", resolveCpEffectiveStatus(null, null))
        assertEquals("scheduled", resolveCpEffectiveStatus("", ""))
    }
}
