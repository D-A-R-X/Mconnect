package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.CpVisitClient
import com.manjugroups.m_connect.network.CpVisitLead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Do not allow a new CP request if one is already pending."
 *
 * The two things that quietly break this rule are phone formatting (+91 vs
 * plain) and the several spellings the backend uses for in-progress, so both
 * are pinned here.
 */
class OpenCpVisitGuardTest {

    private fun visit(
        phone: String?,
        status: String?,
        scheduledDate: String? = "2026-08-27",
        onClient: Boolean = false,
    ) = CpVisitDetail(
        id = "v-$phone-$status",
        status = status,
        scheduledDate = scheduledDate,
        lead = if (onClient) null else CpVisitLead(mobileNumber = phone),
        client = if (onClient) CpVisitClient(mobileNumber = phone) else null,
    )

    @Test
    fun `a scheduled visit for the same client blocks a new one`() {
        val reason = OpenCpVisitGuard.blockReason(
            listOf(visit("9876543210", "scheduled")),
            "9876543210",
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("2026-08-27"))
    }

    @Test
    fun `a completed or cancelled visit does not block a new one`() {
        for (status in listOf("completed", "cancelled")) {
            assertNull(
                "status=$status must not block",
                OpenCpVisitGuard.blockReason(listOf(visit("9876543210", status)), "9876543210"),
            )
        }
    }

    @Test
    fun `every in-progress spelling counts as open`() {
        for (status in listOf(
            "scheduled", "in-progress", "in_progress", "ongoing", "started", "active", "arrived",
        )) {
            assertTrue("status=$status must count as open", OpenCpVisitGuard.isOpen(status))
        }
        assertTrue(OpenCpVisitGuard.isOpen("SCHEDULED"))
        assertTrue(OpenCpVisitGuard.isOpen("  Scheduled  "))
    }

    @Test
    fun `country code and formatting do not hide an existing visit`() {
        // Same person, written four ways — all must match.
        for (stored in listOf("9876543210", "+919876543210", "91 98765 43210", "098765-43210")) {
            assertNotNull(
                "stored=$stored should match",
                OpenCpVisitGuard.findOpenVisit(listOf(visit(stored, "scheduled")), "+91 98765 43210"),
            )
        }
    }

    @Test
    fun `a different client never blocks`() {
        assertNull(
            OpenCpVisitGuard.blockReason(
                listOf(visit("9876543210", "scheduled")),
                "9000000001",
            ),
        )
    }

    @Test
    fun `a visit carrying the number on the client record is matched too`() {
        assertNotNull(
            OpenCpVisitGuard.findOpenVisit(
                listOf(visit("9876543210", "scheduled", onClient = true)),
                "9876543210",
            ),
        )
    }

    @Test
    fun `an empty list or an incomplete number never blocks`() {
        assertNull(OpenCpVisitGuard.blockReason(emptyList(), "9876543210"))
        // Half-typed numbers must not match anyone.
        assertNull(
            OpenCpVisitGuard.blockReason(listOf(visit("9876543210", "scheduled")), "98765"),
        )
    }

    @Test
    fun `the open visit is preferred over closed ones for the same client`() {
        val found = OpenCpVisitGuard.findOpenVisit(
            listOf(
                visit("9876543210", "completed", scheduledDate = "2026-08-01"),
                visit("9876543210", "cancelled", scheduledDate = "2026-08-02"),
                visit("9876543210", "scheduled", scheduledDate = "2026-08-27"),
            ),
            "9876543210",
        )
        assertEquals("2026-08-27", found?.scheduledDate)
    }

    @Test
    fun `a missing status is not treated as open`() {
        assertNull(OpenCpVisitGuard.blockReason(listOf(visit("9876543210", null)), "9876543210"))
    }
}
