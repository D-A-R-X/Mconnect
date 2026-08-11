package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpOutcomePolicyTest {

    @Test
    fun `others is available only for the approved CP categories`() {
        assertTrue(cpTypeSupportsOtherOutcome("booking_cp"))
        assertTrue(cpTypeSupportsOtherOutcome(" GIFT_DISTRIBUTION "))

        // Old Client CP no longer offers "Others" — both its outcomes already
        // capture remarks, so the free-text option was redundant (web parity:
        // CP_TYPES_WITH_OTHER_OUTCOME = {booking_cp, gift_distribution}).
        assertFalse(cpTypeSupportsOtherOutcome("Old_Client"))
        assertFalse(cpTypeSupportsOtherOutcome("collection_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("follow_up"))
        assertFalse(cpTypeSupportsOtherOutcome("sv_cum_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("direct_cp"))
        assertFalse(cpTypeSupportsOtherOutcome(null))
        assertFalse(cpTypeSupportsOtherOutcome(""))
    }
}
