package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpOutcomePolicyTest {

    @Test
    fun `others is available only for the approved CP categories`() {
        assertTrue(cpTypeSupportsOtherOutcome("booking_cp"))
        assertTrue(cpTypeSupportsOtherOutcome(" GIFT_DISTRIBUTION "))
        assertTrue(cpTypeSupportsOtherOutcome("Old_Client"))
        assertTrue(cpTypeSupportsOtherOutcome("other_cp"))

        assertFalse(cpTypeSupportsOtherOutcome("follow_up"))
        assertFalse(cpTypeSupportsOtherOutcome("collection_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("sv_cum_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("direct_cp"))
        assertFalse(cpTypeSupportsOtherOutcome(null))
        assertFalse(cpTypeSupportsOtherOutcome(""))
    }

    @Test
    fun `sv confirmation CP no longer offers others`() {
        // The regression this guards: sv_cum_cp used to reach the Others
        // option through the shared "SV-style" branch, even though the policy
        // set above already excluded it.
        assertFalse(shouldOfferOtherOutcome(isPureSiteVisit = false, cpType = "sv_cum_cp"))
        assertFalse(shouldOfferOtherOutcome(isPureSiteVisit = false, cpType = "SV_CUM_CP"))
    }

    @Test
    fun `a pure site visit keeps others`() {
        // Only sv_cum_cp was asked for — a plain site visit is unchanged.
        assertTrue(shouldOfferOtherOutcome(isPureSiteVisit = true, cpType = null))
    }

    @Test
    fun `the approved CP types are unaffected`() {
        for (type in listOf("booking_cp", "gift_distribution", "old_client", "other_cp")) {
            assertTrue(type, shouldOfferOtherOutcome(isPureSiteVisit = false, cpType = type))
        }
        for (type in listOf("collection_cp", "follow_up", "direct_cp", "new_client_cp")) {
            assertFalse(type, shouldOfferOtherOutcome(isPureSiteVisit = false, cpType = type))
        }
    }

}
