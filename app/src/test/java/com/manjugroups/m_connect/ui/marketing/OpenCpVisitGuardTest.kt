package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitClient
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.CpVisitLead
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCpVisitGuardTest {

    private fun visit(
        phone: String?,
        status: String?,
        scheduledDate: String = "2026-08-27",
        onClient: Boolean = false,
    ) = CpVisitDetail(
        id = "v-$phone-$status-$scheduledDate",
        status = status,
        scheduledDate = scheduledDate,
        lead = if (onClient) null else CpVisitLead(mobileNumber = phone),
        client = if (onClient) CpVisitClient(mobileNumber = phone) else null,
    )

    @Test
    fun `scheduled and completed visits block the same client on the same day`() {
        for (status in listOf("scheduled", "in_progress", "completed")) {
            val reason = OpenCpVisitGuard.blockReason(
                listOf(visit("9876543210", status)),
                "9876543210",
                "2026-08-27",
            )
            assertNotNull("status=$status must block", reason)
            assertTrue(reason!!.contains("Only one CP visit per client is allowed per day"))
        }
    }

    @Test
    fun `cancelled same-day visit can be replaced`() {
        assertNull(
            OpenCpVisitGuard.blockReason(
                listOf(visit("9876543210", "cancelled")),
                "9876543210",
                "2026-08-27",
            ),
        )
    }

    @Test
    fun `same client on another day is allowed`() {
        assertNull(
            OpenCpVisitGuard.blockReason(
                listOf(visit("9876543210", "scheduled", "2026-08-26")),
                "9876543210",
                "2026-08-27",
            ),
        )
    }

    @Test
    fun `country code formatting and client record cannot hide a duplicate`() {
        for (stored in listOf("9876543210", "+919876543210", "91 98765 43210", "098765-43210")) {
            assertNotNull(
                OpenCpVisitGuard.findSameDayVisit(
                    listOf(visit(stored, "completed", onClient = true)),
                    "+91 98765 43210",
                    "2026-08-27",
                ),
            )
        }
    }

    @Test
    fun `different client and incomplete phone never block`() {
        val visits = listOf(visit("9876543210", "scheduled"))
        assertNull(OpenCpVisitGuard.blockReason(visits, "9000000001", "2026-08-27"))
        assertNull(OpenCpVisitGuard.blockReason(visits, "98765", "2026-08-27"))
    }

    @Test
    fun `legacy missing status is treated as non-cancelled`() {
        assertNotNull(
            OpenCpVisitGuard.blockReason(
                listOf(visit("9876543210", null)),
                "9876543210",
                "2026-08-27",
            ),
        )
    }
}
