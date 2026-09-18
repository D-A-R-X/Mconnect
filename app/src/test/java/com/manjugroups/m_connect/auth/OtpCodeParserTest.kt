package com.manjugroups.m_connect.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpCodeParserTest {

    @Test
    fun `reads the code from a typical OTP SMS`() {
        assertEquals("482913", OtpCodeParser.extract("482913 is your Mconnect login OTP. Valid for 5 minutes."))
        assertEquals("482913", OtpCodeParser.extract("Your OTP is 482913. Do not share it."))
    }

    @Test
    fun `a phone number or longer digit run is never taken as the code`() {
        assertNull(OtpCodeParser.extract("Call 9876543210 for help"))
        assertEquals("482913", OtpCodeParser.extract("Call 9876543210. OTP 482913"))
    }

    @Test
    fun `nothing usable returns null`() {
        assertNull(OtpCodeParser.extract(null))
        assertNull(OtpCodeParser.extract("Your code is 1234"))
    }

    @Test
    fun `keyboard and paste input keep digits only`() {
        assertEquals("482913", OtpCodeParser.digits("48 29-13"))
        assertEquals("", OtpCodeParser.digits(null))
    }
}
