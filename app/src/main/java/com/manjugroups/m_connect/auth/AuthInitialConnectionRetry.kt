package com.manjugroups.m_connect.auth

import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Retries only failures that prove the request never received an HTTP response.
 * This is shared by OTP and Employee ID login so the first connection on a
 * newly opened mobile network does not require a manual second tap.
 */
internal object AuthInitialConnectionRetryPolicy {
    const val RETRY_DELAY_MS = 450L

    fun shouldRetry(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is NoRouteToHostException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal suspend fun <T> withAuthInitialConnectionRetry(block: suspend () -> T): T {
    return try {
        block()
    } catch (error: Throwable) {
        if (!AuthInitialConnectionRetryPolicy.shouldRetry(error)) throw error
        delay(AuthInitialConnectionRetryPolicy.RETRY_DELAY_MS)
        block()
    }
}
