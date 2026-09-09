package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerLoadGateTest {
    @Test fun `repeated taps are ignored until the first load finishes`() {
        val gate = PickerLoadGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())

        gate.finish()
        assertTrue(gate.tryStart())
    }
}
