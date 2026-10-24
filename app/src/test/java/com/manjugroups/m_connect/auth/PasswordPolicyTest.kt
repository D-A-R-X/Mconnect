package com.manjugroups.m_connect.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordPolicyTest {

    @Test
    fun `a strong password passes`() {
        assertNull(PasswordPolicy.problem("Manju@2026"))
    }

    @Test
    fun `each missing kind of character is named`() {
        assertEquals("Use at least 8 characters.", PasswordPolicy.problem("Ab1@"))
        assertEquals("Add an uppercase letter.", PasswordPolicy.problem("manju@2026"))
        assertEquals("Add a lowercase letter.", PasswordPolicy.problem("MANJU@2026"))
        assertEquals("Add a number.", PasswordPolicy.problem("Manju@Groups"))
        assertEquals("Add a special character.", PasswordPolicy.problem("Manju2026"))
        assertNotNull(PasswordPolicy.problem("Manju @2026"))
    }

    @Test
    fun `generated passwords always satisfy the rule`() {
        repeat(200) {
            val generated = PasswordPolicy.generate()
            assertEquals(12, generated.length)
            assertNull(generated, PasswordPolicy.problem(generated))
        }
    }
}
