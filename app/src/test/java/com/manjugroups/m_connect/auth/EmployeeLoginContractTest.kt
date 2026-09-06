package com.manjugroups.m_connect.auth

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.EmployeePasswordLoginRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeLoginContractTest {
    @Test
    fun `employee login request keeps backend field names`() {
        val json = Gson().toJson(
            EmployeePasswordLoginRequest(
                employeeId = "22026",
                password = "secret-value",
                deviceId = "android-id",
                devicePlatform = "android",
                deviceModel = "Test phone",
                batteryPct = 80.0,
            ),
        )
        val objectValue = JsonParser.parseString(json).asJsonObject

        assertEquals("22026", objectValue["employeeId"].asString)
        assertEquals("secret-value", objectValue["password"].asString)
        assertEquals("android-id", objectValue["deviceId"].asString)
        assertEquals("android", objectValue["devicePlatform"].asString)
        assertEquals("Test phone", objectValue["deviceModel"].asString)
        assertEquals(80.0, objectValue["batteryPct"].asDouble, 0.0)
        assertFalse(objectValue.has("a"))
    }

    @Test
    fun `login error parser reads direct and nested server messages`() {
        assertEquals(
            "Invalid Employee ID or password",
            EmployeeLoginErrorParser.parseResponseMessage(
                """{"success":false,"error":"Invalid Employee ID or password"}""",
            ),
        )
        assertEquals(
            "This account is already locked to another device",
            EmployeeLoginErrorParser.parseResponseMessage(
                """{"error":{"detail":"This account is already locked to another device"}}""",
            ),
        )
    }

    @Test
    fun `empty 401 never leaks raw HTTP status`() {
        val message = EmployeeLoginErrorParser.message(401, null, "Unable to sign in")

        assertTrue(message.contains("Sign-in was rejected"))
        assertTrue(message.contains("device lock"))
        assertFalse(message.contains("HTTP 401"))
    }
}
