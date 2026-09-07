package com.manjugroups.m_connect.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class EmployeeLoginRetryPolicyTest {
    @Test
    fun `retries only initial host and connection failures`() {
        assertTrue(AuthInitialConnectionRetryPolicy.shouldRetry(UnknownHostException()))
        assertTrue(AuthInitialConnectionRetryPolicy.shouldRetry(ConnectException()))
        assertTrue(AuthInitialConnectionRetryPolicy.shouldRetry(NoRouteToHostException()))
        assertTrue(
            AuthInitialConnectionRetryPolicy.shouldRetry(
                IllegalStateException("wrapped", ConnectException()),
            ),
        )
    }

    @Test
    fun `does not retry timeouts or unrelated failures`() {
        assertFalse(AuthInitialConnectionRetryPolicy.shouldRetry(SocketTimeoutException()))
        assertFalse(AuthInitialConnectionRetryPolicy.shouldRetry(IllegalArgumentException()))
    }
}
