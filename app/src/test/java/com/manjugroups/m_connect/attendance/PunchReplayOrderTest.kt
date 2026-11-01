package com.manjugroups.m_connect.attendance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PunchReplayOrderTest {
    @Test fun failedSelfieOrRetryableRequestStopsDependentClockOutUntilNextReplay() = runTest {
        for (failure in listOf("selfie", "request")) {
            val pending = mutableListOf("IN", "OUT")
            val attempted = mutableListOf<String>()
            replayPunchesInOrder(pending.toList()) {
                attempted += "$it:$failure"
                false
            }
            assertEquals(listOf("IN:$failure"), attempted)
            assertEquals(listOf("IN", "OUT"), pending)
            replayPunchesInOrder(pending.toList()) {
                attempted += "$it:recovered"
                pending.remove(it)
                true
            }
            assertEquals(listOf("IN:$failure", "IN:recovered", "OUT:recovered"), attempted)
            assertTrue(pending.isEmpty())
        }
    }

    @Test fun resolvedBusinessRejectionStillAllowsNextPunchWithoutChangingItsPolicy() = runTest {
        val attempted = mutableListOf<String>()
        replayPunchesInOrder(listOf("duplicate-IN", "OUT")) {
            attempted += it
            true
        }
        assertEquals(listOf("duplicate-IN", "OUT"), attempted)
    }

    @Test fun cancellationPropagatesAndNeverStartsDependentPunch() = runTest {
        val attempted = mutableListOf<String>()
        try {
            replayPunchesInOrder(listOf("IN", "OUT")) {
                attempted += it
                throw CancellationException("Stopped worker")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("IN"), attempted)
        }
    }
}
