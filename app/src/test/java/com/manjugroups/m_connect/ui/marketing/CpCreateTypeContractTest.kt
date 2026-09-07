package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpCreateTypeContractTest {
    @Test
    fun `normal CP stores its selected purpose as cpType`() {
        val contract = CpCreateTypeContract.from("old_client", isJoint = false)

        assertEquals("old_client", contract?.cpType)
        assertNull(contract?.jointCpCategory)
        assertTrue(contract?.matches("old_client", null) == true)
    }

    @Test
    fun `joint CP stores mode and selected purpose separately`() {
        val contract = CpCreateTypeContract.from("sv_cum_cp", isJoint = true)

        assertEquals("joint_cp", contract?.cpType)
        assertEquals("sv_cum_cp", contract?.jointCpCategory)
        assertTrue(contract?.matches("joint_cp", "sv_cum_cp") == true)
    }

    @Test
    fun `blank changed or incomplete persisted types are rejected`() {
        val normal = CpCreateTypeContract.from("gift_distribution", isJoint = false)!!
        val joint = CpCreateTypeContract.from("collection_cp", isJoint = true)!!

        assertFalse(normal.matches(null, null))
        assertFalse(normal.matches("other_cp", null))
        assertFalse(joint.matches("joint_cp", null))
        assertFalse(joint.matches("joint_cp", "booking_cp"))
        assertNull(CpCreateTypeContract.from("joint_cp", isJoint = false))
    }
}
