package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.TodayVisit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteVisitListRulesTest {
    @Test
    fun `confirmed linked site visit remains in Site Visits`() {
        val visit = visit(tripType = "site_visit", clientPlaceVisitId = "cp-123")

        assertTrue(SiteVisitListRules.belongsInSiteVisits(visit))
    }

    @Test
    fun `legacy linked site visit without trip type remains in Site Visits`() {
        val visit = visit(tripType = null, clientPlaceVisitId = "cp-123")

        assertTrue(SiteVisitListRules.belongsInSiteVisits(visit))
    }

    @Test
    fun `client place trip remains only in CP Visits`() {
        val visit = visit(tripType = "client_place", clientPlaceVisitId = "cp-123")

        assertFalse(SiteVisitListRules.belongsInSiteVisits(visit))
    }

    @Test
    fun `mixed first page auto fills until a visible window is available`() {
        assertTrue(
            SiteVisitListRules.shouldAutoFill(
                visitCount = 2,
                pageSize = 20,
                hasMore = true,
                loadedExtraPages = 1,
                maxExtraPages = 10,
            ),
        )
    }

    @Test
    fun `auto fill stops when window full server exhausted or safety cap reached`() {
        assertFalse(SiteVisitListRules.shouldAutoFill(20, 20, true, 1, 10))
        assertFalse(SiteVisitListRules.shouldAutoFill(2, 20, false, 1, 10))
        assertFalse(SiteVisitListRules.shouldAutoFill(2, 20, true, 10, 10))
    }

    @Test
    fun `site visit ownership includes field staff and lmo only`() {
        val visit = visit(tripType = "site_visit", clientPlaceVisitId = null).copy(
            bdoStaffId = "field",
            lmoStaffId = "lmo",
        )

        assertTrue(SiteVisitListRules.belongsToAny(visit, setOf("field")))
        assertTrue(SiteVisitListRules.belongsToAny(visit, setOf("lmo")))
        assertFalse(SiteVisitListRules.belongsToAny(visit, setOf("other")))
    }

    @Test
    fun `pagination stops when next offset reaches total`() {
        assertTrue(SiteVisitListRules.hasUsableNextPage(true, "600", "700", 800))
        assertFalse(SiteVisitListRules.hasUsableNextPage(true, "700", "800", 800))
        assertFalse(SiteVisitListRules.hasUsableNextPage(true, "800", "800", 900))
        assertFalse(SiteVisitListRules.hasUsableNextPage(false, "100", "200", 800))
    }

    private fun visit(tripType: String?, clientPlaceVisitId: String?) = TodayVisit(
        id = "visit-1",
        clientPlaceId = "place-1",
        scheduledDate = "2026-09-01",
        status = "scheduled",
        tripType = tripType,
        clientPlaceVisitId = clientPlaceVisitId,
    )
}
