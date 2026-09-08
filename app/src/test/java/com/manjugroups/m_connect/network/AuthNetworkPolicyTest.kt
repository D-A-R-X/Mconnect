package com.manjugroups.m_connect.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthNetworkPolicyTest {
    @Test
    fun `all mobile login and recovery routes use the extended auth contract`() {
        listOf(
            "/api/auth/send-otp",
            "/api/auth/verify-otp",
            "/api/auth/login-with-employee-id",
            "/api/auth/device-binding/recovery/request",
            "/api/auth/device-binding/recovery/confirm",
            "/api/auth/device-binding/recovery/confirm-verified-otp",
        ).forEach { path ->
            assertTrue(path, AuthNetworkPolicy.usesMobileEntryContract(path))
        }
        assertEquals(90, AuthNetworkPolicy.READ_TIMEOUT_SECONDS)
    }

    @Test
    fun `authenticated business and session routes keep the default client contract`() {
        listOf(
            "/api/auth/validate-session",
            "/api/auth/logout",
            "/api/marketing/clientPlaceVisits/my",
            "/api/geotrack/visit/complete",
            "/api/storage/uploads",
        ).forEach { path ->
            assertFalse(path, AuthNetworkPolicy.usesMobileEntryContract(path))
        }
    }
}
