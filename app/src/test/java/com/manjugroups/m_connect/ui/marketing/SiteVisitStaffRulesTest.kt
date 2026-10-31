package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.manjugroups.m_connect.network.StaffListResponse
import org.junit.Assert.*
import org.junit.Test

class SiteVisitStaffRulesTest {
    @Test fun assistantManagersAndBusinessDevelopmentStaffBothRemainSelectable() {
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Sales & Marketing", "Assistant Manager"))
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Sales & Marketing", "Senior Business Development Executive"))
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Sales and Marketing", "General Manager"))
    }

    @Test fun telesalesPreservesTheExistingApiLmoEligibility() {
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Telesales", "LMO"))
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Telesales", "Lead Management Executive"))
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Telesales", "Telecaller"))
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", "Telesales", "Assistant Manager"))
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", "Telesales", null))
    }

    @Test fun channelPartnersPreserveTheExistingApiDepartmentRule() {
        assertTrue(SiteVisitStaffRules.lmoOrBdo("active", "Channel-Partner", "Executive"))
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", "External", "Channel Partner"))
    }

    @Test fun ineligibleDepartmentsAndInactiveStaffStayExcluded() {
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", "Accounts", "BDO"))
        assertFalse(SiteVisitStaffRules.lmoOrBdo("active", null, "Assistant Manager"))
        for (status in listOf("inactive", "terminated", "suspended", "invited", "pending-approval")) {
            assertFalse(SiteVisitStaffRules.lmoOrBdo(status, "Sales & Marketing", "BDO"))
        }
    }

    @Test fun webCompatibleNormalizationAndLegacyMissingStatus() {
        assertTrue(SiteVisitStaffRules.lmoOrBdo(" ACTIVE ", " SALES&MARKETING ", null))
        assertTrue(SiteVisitStaffRules.lmoOrBdo(null, "Sales / Marketing", "Assistant Manager"))
        assertTrue(SiteVisitStaffRules.lmoOrBdo("", "Telesales", "LMO"))
        assertTrue(SiteVisitStaffRules.salesMarketing("active", "Sales&Marketing"))
        assertFalse(SiteVisitStaffRules.salesMarketing("active", "Telesales"))
        assertFalse(SiteVisitStaffRules.salesMarketing("terminated", "Sales & Marketing"))
    }

    @Test fun pickerDoesNotDropAssistantManagerFromAValidApiStaffPayload() {
        val payload = """{"success":true,"staff":[
            {"_id":"imran-fixture","name":"IMRAN.A","employeeId":"21413","status":"active","designation":"Assistant Manager","department":"Sales & Marketing"},
            {"_id":"manimaran-fixture","name":"MANIMARAN.S","employeeId":"20998","status":"active","designation":"Senior Business Development Executive","department":"Sales & Marketing"},
            {"_id":"inactive-fixture","name":"Inactive","status":"inactive","designation":"BDO","department":"Sales & Marketing"}
        ]}"""
        val staff = Gson().fromJson(payload, StaffListResponse::class.java).staff
        val matches = staff.filter { SiteVisitStaffRules.lmoOrBdo(it.status, it.department, it.designation) }
            .filter { it.name.orEmpty().contains("im", ignoreCase = true) }
        assertEquals(listOf("imran-fixture", "manimaran-fixture"), matches.map { it.id })
    }
}
