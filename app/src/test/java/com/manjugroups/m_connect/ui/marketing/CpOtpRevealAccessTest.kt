package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client gate for revealing a team member's CP arrival OTP.
 *
 * These assertions are a mirror of `requireOtpRevealAccess` in the backend's
 * `convex/hr/fieldVisitOtp.ts`. The server stays authoritative — if these ever
 * disagree, the server wins and the user sees an error toast instead of a code.
 * The point of the gate is that an unprivileged viewer is never even shown that
 * the capability exists.
 */
class CpOtpRevealAccessTest {

    private val avp = "Assistant Vice President"
    private val reports = setOf("staff_field_1", "staff_field_2")

    private fun canReveal(
        isSuperAdmin: Boolean = false,
        designation: String? = avp,
        permissions: Set<String> = setOf(CP_REVEAL_OTP_PERMISSION),
        viewerStaffId: String? = "staff_avp",
        targetStaffId: String? = "staff_field_1",
        team: Set<String> = reports,
    ) = CpOtpRevealAccess.canRevealFor(
        isSuperAdmin = isSuperAdmin,
        designation = designation,
        permissions = permissions,
        viewerStaffId = viewerStaffId,
        targetStaffId = targetStaffId,
        reportingTeamStaffIds = team,
    )

    @Test
    fun `an AVP with the key reveals for their own reporting staff`() {
        assertTrue(canReveal())
    }

    @Test
    fun `the key alone is not enough without a GM or AVP designation`() {
        // The permission is grantable from the IAM screen, so a coordinator or
        // an executive could be given it by mistake. The designation gate is
        // what stops that from handing out client OTPs.
        assertFalse(canReveal(designation = "Marketing Executive"))
        assertFalse(canReveal(designation = "Senior Manager"))
        assertFalse(canReveal(designation = null))
    }

    @Test
    fun `a GM or AVP without the key cannot reveal`() {
        assertFalse(canReveal(permissions = emptySet()))
        assertFalse(canReveal(permissions = setOf("marketing.cpVisits.view")))
    }

    @Test
    fun `reveal is limited to staff below the viewer in the hierarchy`() {
        // Another AVP's field staff — same designation, same key, wrong team.
        assertFalse(canReveal(targetStaffId = "staff_other_team"))
        assertFalse(canReveal(team = emptySet()))
    }

    @Test
    fun `nobody reveals their own OTP`() {
        // Even if the viewer somehow appears inside their own team list, the
        // server removes them from it — the point is a manager assisting
        // someone else, not self-service past the client's verification.
        assertFalse(
            canReveal(
                viewerStaffId = "staff_avp",
                targetStaffId = "staff_avp",
                team = setOf("staff_avp", "staff_field_1"),
            ),
        )
    }

    @Test
    fun `a super admin reveals without the key, the designation or the team`() {
        assertTrue(
            canReveal(
                isSuperAdmin = true,
                designation = "Operations Head",
                permissions = emptySet(),
                targetStaffId = "staff_anyone",
                team = emptySet(),
            ),
        )
    }

    @Test
    fun `a missing target staff id never reveals`() {
        // A legacy CP row with no resolvable assignee must fail closed rather
        // than fall through to "not in the team, so ask the server anyway".
        assertFalse(canReveal(targetStaffId = null))
        assertFalse(canReveal(targetStaffId = "   "))
        assertFalse(canReveal(isSuperAdmin = true, targetStaffId = null))
    }

    @Test
    fun `AGM is not GM`() {
        // "AGM" contains "GM". An Assistant General Manager is a rank below,
        // and the backend excludes them explicitly.
        assertFalse(CpOtpRevealAccess.isGmDesignation("Assistant General Manager"))
        assertFalse(CpOtpRevealAccess.isGmDesignation("Asst. General Manager"))
        assertTrue(CpOtpRevealAccess.isGmDesignation("GM"))
        assertTrue(CpOtpRevealAccess.isGmDesignation("Senior General Manager"))
        assertTrue(CpOtpRevealAccess.isGmDesignation("GM - Sales"))
    }

    @Test
    fun `AVP is matched in the spellings HR actually uses`() {
        listOf(
            "AVP",
            "avp",
            "AVP - Marketing",
            "AVP/Sales",
            "AVP_Marketing",
            "Assistant Vice President",
            "Asst Vice President",
            "Associate VP",
            "AVVP",
        ).forEach {
            assertTrue("expected AVP match for '$it'", CpOtpRevealAccess.isAvpDesignation(it))
        }
        listOf(
            "Vice President",
            "VP",
            "Executive",
            "",
            // The backend's separator pass turns "A.V.P" into "a v p", which
            // its \bavp\b test does not match — so the server refuses this
            // spelling too. Mirrored deliberately: the app must not offer a
            // reveal the server will reject. If HR has staff titled this way,
            // the designation needs fixing on the HR record, not here.
            "A.V.P",
        ).forEach {
            assertFalse("unexpected AVP match for '$it'", CpOtpRevealAccess.isAvpDesignation(it))
        }
    }

    @Test
    fun `a verified or closed visit is not offered for reveal`() {
        // The server refuses these outright, so offering the action could only
        // produce an error toast.
        listOf("arrived", "completed", "cancelled", "postponed", "pending_gm_approval")
            .forEach { assertFalse(it, CpOtpRevealAccess.isRevealableStatus(it)) }
    }

    @Test
    fun `a live or unknown visit status stays revealable`() {
        listOf("scheduled", "in_progress", "enroute").forEach {
            assertTrue(it, CpOtpRevealAccess.isRevealableStatus(it))
        }
        // An unknown/absent status degrades to the server's own answer rather
        // than silently hiding a capability the viewer legitimately has.
        assertTrue(CpOtpRevealAccess.isRevealableStatus(null))
        assertTrue(CpOtpRevealAccess.isRevealableStatus(""))
    }
}
