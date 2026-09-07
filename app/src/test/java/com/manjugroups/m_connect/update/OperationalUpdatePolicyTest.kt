package com.manjugroups.m_connect.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalUpdatePolicyTest {
    private val idle = OperationalUpdateState(
        loggedIn = true,
        trackingRequested = false,
        trackingSessionActive = false,
        fieldActivityActive = false,
        onDutyActive = false,
        dialerCallActive = false,
        pendingLocationPoints = 0,
        pendingTrackingEvents = 0,
        pendingPunches = 0,
        pendingChatMessages = 0,
        attendanceSessionOpen = false,
    )

    @Test
    fun fullyIdleStateAllowsUpdate() {
        assertTrue(OperationalUpdatePolicy.isSafe(idle))
    }

    @Test
    fun everyOperationalSignalBlocksUpdate() {
        listOf(
            idle.copy(loggedIn = false),
            idle.copy(trackingRequested = true),
            idle.copy(trackingSessionActive = true),
            idle.copy(fieldActivityActive = true),
            idle.copy(onDutyActive = true),
            idle.copy(dialerCallActive = true),
            idle.copy(pendingLocationPoints = 1),
            idle.copy(pendingTrackingEvents = 1),
            idle.copy(pendingPunches = 1),
            idle.copy(pendingChatMessages = 1),
            idle.copy(attendanceSessionOpen = true),
            idle.copy(attendanceSessionOpen = null),
        ).forEach { state ->
            assertFalse(state.toString(), OperationalUpdatePolicy.isSafe(state))
        }
    }
}
