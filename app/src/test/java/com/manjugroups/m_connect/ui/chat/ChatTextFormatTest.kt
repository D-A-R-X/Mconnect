package com.manjugroups.m_connect.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The OTP-assist request puts the code in bold, and the web client already
 * renders message bodies as markdown — so Android was showing raw asterisks.
 * This must fix that WITHOUT changing any message that lacks a matched pair.
 *
 * Tests target [ChatTextFormat.parse], the pure half: Android's span classes
 * are unavailable in plain unit tests, and the span application is a trivial
 * loop over what parse returns.
 */
class ChatTextFormatTest {

    @Test
    fun `markers are stripped and the text between them survives`() {
        val parsed = ChatTextFormat.parse("OTP: **123456**")!!
        assertEquals("OTP: 123456", parsed.text)
        // "OTP: " is 5 chars, so the code occupies 5..10.
        assertEquals(listOf(5 until 11), parsed.boldRanges)
    }

    @Test
    fun `plain text is not formatted at all`() {
        assertNull(ChatTextFormat.parse("Just a normal message"))
    }

    @Test
    fun `an unmatched marker is left exactly as typed`() {
        // Someone writing "2**3" or a stray "**" must see what they typed.
        assertNull(ChatTextFormat.parse("2**3 is not bold"))
        assertNull(ChatTextFormat.parse("trailing **"))
    }

    @Test
    fun `several bold runs in one message all render`() {
        val parsed = ChatTextFormat.parse("**A** middle **B**")!!
        assertEquals("A middle B", parsed.text)
        assertEquals(listOf(0 until 1, 9 until 10), parsed.boldRanges)
    }

    @Test
    fun `bold spanning a line break still works`() {
        val parsed = ChatTextFormat.parse("**line one\nline two**")!!
        assertEquals("line one\nline two", parsed.text)
    }

    @Test
    fun `empty and null are safe`() {
        assertNull(ChatTextFormat.parse(null))
        assertNull(ChatTextFormat.parse(""))
    }

    @Test
    fun `surrounding text is preserved on both sides`() {
        val parsed = ChatTextFormat.parse("before **mid** after")!!
        assertEquals("before mid after", parsed.text)
        assertEquals(listOf(7 until 10), parsed.boldRanges)
    }
}
