package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpFollowUpWindowTest {

    @Test
    fun `plain booking and collection CPs keep their caps`() {
        assertEquals(7, cpFollowUpWindowDays("booking_cp", null))
        assertEquals(5, cpFollowUpWindowDays("collection_cp", null))
    }

    @Test
    fun `joint CPs are capped by their joint category, like the server`() {
        assertEquals(7, cpFollowUpWindowDays("joint_cp", "booking_cp"))
        assertEquals(5, cpFollowUpWindowDays("joint_cp", "collection_cp"))
        assertEquals(5, cpFollowUpWindowDays(" JOINT_CP ", " Collection_CP "))
        assertNull(cpFollowUpWindowDays("joint_cp", "new_client_cp"))
        assertNull(cpFollowUpWindowDays("joint_cp", null))
    }

    @Test
    fun `the joint category is ignored on a non-joint CP`() {
        assertNull(cpFollowUpWindowDays("gift_distribution", "booking_cp"))
        assertEquals(7, cpFollowUpWindowDays("booking_cp", "collection_cp"))
    }

    @Test
    fun `other CP types are uncapped`() {
        assertNull(cpFollowUpWindowDays("sv_cum_cp", null))
        assertNull(cpFollowUpWindowDays(null, null))
    }
}
