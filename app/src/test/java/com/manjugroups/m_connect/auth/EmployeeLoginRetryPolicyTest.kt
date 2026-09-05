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
        assertTrue(EmployeeLoginRetryPolicy.shouldRetryInitialConnection(UnknownHostException()))
        assertTrue(EmployeeLoginRetryPolicy.shouldRetryInitialConnection(ConnectException()))
        assertTrue(EmployeeLoginRetryPolicy.shouldRetryInitialConnection(NoRouteToHostException()))
        assertTrue(
            EmployeeLoginRetryPolicy.shouldRetryInitialConnection(
                IllegalStateException("wrapped", ConnectException()),
            ),
        )
    }

    @Test
    fun `does not retry timeouts or unrelated failures`() {
        assertFalse(EmployeeLoginRetryPolicy.shouldRetryInitialConnection(SocketTimeoutException()))
        assertFalse(EmployeeLoginRetryPolicy.shouldRetryInitialConnection(IllegalArgumentException()))
    }
}
