package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.MyMarketingCpVisitsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpVisitListScopePolicyTest {
    @Test
    fun legacyNullDirectReportIdsIsTreatedAsEmpty() {
        val response = Gson().fromJson(
            """{"success":true,"visits":[],"directReportIds":null}""",
            MyMarketingCpVisitsResponse::class.java,
        )

        assertEquals(emptyList<String>(), response.safeDirectReportIds)
    }

    @Test
    fun teamContainsOnlyIdsExplicitlyReturnedAsDirectReports() {
        val aVisit = CpVisitDetail(id = "cp-a", assignedStaffId = "A")
        val cVisit = CpVisitDetail(id = "cp-c", assignedStaffId = "C")

        assertTrue(CpVisitListScopePolicy.belongsToAny(aVisit, setOf("A")))
        assertFalse(CpVisitListScopePolicy.belongsToAny(cVisit, setOf("A")))
    }

    @Test
    fun jointParticipantCountsAsOwningTheCp() {
        val jointVisit = CpVisitDetail(
            id = "joint-cp",
            assignedStaffId = "A",
            joint = JointCpSummary(
                participants = listOf(JointCpParticipant(staffId = "B")),
            ),
        )

        assertTrue(CpVisitListScopePolicy.belongsToAny(jointVisit, setOf("B")))
    }

    @Test
    fun jointLeadCountsAsOwningTheCp() {
        val jointVisit = CpVisitDetail(
            id = "joint-cp",
            assignedStaffId = "A",
            joint = JointCpSummary(leadStaffId = "B"),
        )

        assertTrue(CpVisitListScopePolicy.belongsToAny(jointVisit, setOf("B")))
    }

    @Test
    fun telecallerOrProposedSvRoleDoesNotOwnTheCp() {
        val visit = CpVisitDetail(
            id = "cp-a",
            assignedStaffId = "A",
            telecallerStaffId = "B",
        )

        assertFalse(CpVisitListScopePolicy.belongsToAny(visit, setOf("B")))
    }

    @Test
    fun teamRejectsLegacyResponseThatDidNotEchoDirectScope() {
        assertFalse(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.TEAM, null))
        assertFalse(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.TEAM, "subtree"))
        assertTrue(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.TEAM, "direct"))
        assertTrue(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.MY, null))
    }

    @Test
    fun adminDefaultsToAllWhileStaffDefaultsToMine() {
        assertEquals(CpVisitListScope.ALL, CpVisitListScopePolicy.initialScope(isAdmin = true))
        assertEquals(CpVisitListScope.MY, CpVisitListScopePolicy.initialScope(isAdmin = false))
    }

    @Test
    fun allScopeRequiresExplicitServerEcho() {
        assertTrue(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.ALL, "all"))
        assertFalse(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.ALL, null))
        assertFalse(CpVisitListScopePolicy.acceptsResponse(CpVisitListScope.ALL, "direct"))
    }
}
