package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvApprovalAccessTest {

    private val gm = "gm1"
    private val avp = "avp1"
    private val telecaller = "tc1"

    private fun approve(
        me: String?,
        isAdmin: Boolean = false,
        role: String? = "office-staff",
        permissions: Set<String> = emptySet(),
    ) = SvApprovalAccess.canApprove(
        me = me,
        assignedTo = gm,
        isAdmin = isAdmin,
        role = role,
        permissions = permissions,
    )

    @Test
    fun `the assigned GM can approve`() {
        assertTrue(approve(gm))
    }

    @Test
    fun `admins, super-admins and confirm-permission holders can approve`() {
        assertTrue(approve(avp, isAdmin = true))
        assertTrue(approve(avp, role = "super-admin"))
        assertTrue(approve(avp, role = " Super-Admin "))
        assertTrue(approve(avp, permissions = setOf(SvApprovalAccess.CONFIRM_PERMISSION)))
    }

    @Test
    fun `supervisor and requester may reject but not approve`() {
        // The AVP above the GM and the telecaller who raised the handoff both
        // pass the task-change rule, so they keep Reject…
        assertTrue(
            SvApprovalAccess.canChangeTask(
                me = avp, assignedTo = gm, supervisorId = avp, assignedBy = telecaller, listScope = "team",
            ),
        )
        assertTrue(
            SvApprovalAccess.canChangeTask(
                me = telecaller, assignedTo = gm, supervisorId = avp, assignedBy = telecaller, listScope = "team",
            ),
        )
        // …but the server refuses their Approve ("only the assigned GM can approve").
        assertFalse(approve(avp, permissions = setOf("marketing.siteVisits.view")))
        assertFalse(approve(telecaller))
    }

    @Test
    fun `no staff id never decides`() {
        assertFalse(approve(null, isAdmin = true))
        assertFalse(
            SvApprovalAccess.canChangeTask(
                me = null, assignedTo = null, supervisorId = null, assignedBy = null, listScope = "all",
            ),
        )
    }

    @Test
    fun `all-scope viewers keep the task-change rule`() {
        assertTrue(
            SvApprovalAccess.canChangeTask(
                me = "x", assignedTo = gm, supervisorId = avp, assignedBy = telecaller, listScope = "all",
            ),
        )
        assertFalse(
            SvApprovalAccess.canChangeTask(
                me = "x", assignedTo = gm, supervisorId = avp, assignedBy = telecaller, listScope = "team",
            ),
        )
    }

    @Test
    fun `queue reads the route maximum`() {
        assertEquals(500, SvApprovalQueueFragment.SV_APPROVAL_TASK_LIMIT)
    }
}
