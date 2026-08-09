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

        assertFalse(cpTypeSupportsOtherOutcome("collection_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("follow_up"))
        assertFalse(cpTypeSupportsOtherOutcome("sv_cum_cp"))
        assertFalse(cpTypeSupportsOtherOutcome("direct_cp"))
        assertFalse(cpTypeSupportsOtherOutcome(null))
        assertFalse(cpTypeSupportsOtherOutcome(""))
    }
}
