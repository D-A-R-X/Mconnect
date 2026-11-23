package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.CreateSiteVisitRequest
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import org.junit.Assert.*
import org.junit.Test

class SiteVisitDraftPolicyTest {
    @Test fun `lookup results belong only to the unchanged complete phone`() {
        assertTrue(siteVisitLookupMatches("9000000001", "9000000001"))
        assertFalse(siteVisitLookupMatches("9000000001", "9000000002"))
        assertFalse(siteVisitLookupMatches("9000000001", "900000000"))
    }

    @Test fun `coordinates match web rules without rejecting either geographic axis`() {
        assertTrue(siteVisitPickupIsUsable(0.0, 80.0))
        assertTrue(siteVisitPickupIsUsable(12.0, 0.0))
        assertFalse(siteVisitPickupIsUsable(0.0, 0.0))
        assertFalse(siteVisitPickupIsUsable(0.00000001, 0.00000001))
        assertFalse(siteVisitPickupIsUsable(null, 80.0))
        assertFalse(siteVisitPickupIsUsable(Double.NaN, 80.0))
        assertFalse(siteVisitPickupIsUsable(91.0, 80.0))
    }

    @Test fun `GM handoffs require a named visitor before any create call`() {
        assertFalse(siteVisitHasRequiredVisitor("out_of_station", emptyList()))
        assertFalse(siteVisitHasRequiredVisitor("immediate_pickup", listOf(" ")))
        assertTrue(siteVisitHasRequiredVisitor("out_of_station", listOf("Test client")))
        assertTrue(siteVisitHasRequiredVisitor("direct_sv", emptyList()))
        assertTrue(siteVisitHasRequiredVisitor("same_area", emptyList()))
    }

    @Test fun `web Core and legacy lead identities use their respective fields`() {
        val uuid = "7b76f254-b620-4bdd-89de-a6de3b61d769"
        assertEquals(SiteVisitLeadIdentity(null, uuid), siteVisitLeadIdentity(uuid, null))
        assertEquals(SiteVisitLeadIdentity("legacy-lead", uuid), siteVisitLeadIdentity("legacy-lead", " $uuid "))
        assertEquals(SiteVisitLeadIdentity("legacy-lead", null), siteVisitLeadIdentity("legacy-lead", null))
        assertEquals(SiteVisitLeadIdentity(null, null), siteVisitLeadIdentity(null, null))
        val json = JsonParser.parseString(Gson().toJson(draft().copy(leadId = null, coreLeadId = uuid))).asJsonObject
        assertEquals(uuid, json["coreLeadId"].asString)
        assertFalse(json.has("leadId"))
    }

    @Test fun `unchanged retry preserves fingerprint while every edited payload is a new intent`() {
        val draft = draft()
        val original = siteVisitCreateFingerprint(draft)
        assertEquals(original, siteVisitCreateFingerprint(draft.copy(requestId = "different-key")))
        for (changed in listOf(
            draft.copy(scheduledDate = "2026-12-02"), draft.copy(routing = "out_of_station"),
            draft.copy(mobileNumber = "9000000002"), draft.copy(pickupLat = 13.0),
            draft.copy(gmStaffId = "other-gm"), draft.copy(notes = "Changed by operator"),
            draft.copy(attendees = listOf(SiteVisitAttendeeRequest(name = "Different visitor"))),
        )) assertNotEquals(original, siteVisitCreateFingerprint(changed))
    }

    @Test fun `late context does not replace an outcome choice or a child action`() {
        assertTrue(shouldRefreshCpOutcomePicker(false, false))
        assertFalse(shouldRefreshCpOutcomePicker(true, false))
        assertFalse(shouldRefreshCpOutcomePicker(false, true))
        assertFalse(shouldRefreshCpOutcomePicker(true, true))
    }

    @Test fun `site assignment pickers support the APIs role fallback but not inactive staff`() {
        assertTrue(SiteVisitStaffRules.salesMarketing("active", null, "Sales and Marketing Manager"))
        assertTrue(SiteVisitStaffRules.salesMarketing("active", "Operations", role = "sales-marketing"))
        assertFalse(SiteVisitStaffRules.salesMarketing("inactive", null, "Sales and Marketing Manager"))
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", "Operations", "Sales and Marketing Manager"))
    }

    private fun draft() = CreateSiteVisitRequest(
        requestId = "first-key", routing = "same_area", origin = "telecaller",
        leadId = "legacy-lead", mobileNumber = "9000000001", projectId = "project",
        scheduledDate = "2026-12-01", scheduledTime = "11:00", pickupTime = "10:00",
        travelMode = "cab", telecallerId = "lmo", bdoStaffId = "bdo", inchargeStaffId = "incharge",
        fieldStaffId = "field", hodStaffId = "hod", avpStaffId = "avp", gmStaffId = "gm",
        seniorManagerStaffId = "senior", pickupAddress = "Test location", pickupLat = 12.0, pickupLng = 80.0,
    )
}
