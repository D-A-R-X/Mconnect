package com.manjugroups.m_connect.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatListReadPolicyTest {
    @Test fun hiddenOrPausedScreensCannotRead() {
        val policy = ChatListReadPolicy()
        for (visible in listOf(false, true)) for (resumed in listOf(false, true)) {
            assertEquals(visible && resumed, policy.canRead(visible, resumed, 0))
        }
    }

    @Test fun overloadSkipsFuturePollsWithoutRetrying() {
        for (status in listOf(429, 503)) {
            val policy = ChatListReadPolicy()
            policy.onFailure(status, null, 100)
            assertFalse(policy.canRead(true, true, 10_099))
            assertTrue(policy.canRead(true, true, 10_100))
            policy.onFailure(status, null, 10_100)
            assertFalse(policy.canRead(true, true, 30_099))
            assertTrue(policy.canRead(true, true, 30_100))
            policy.onSuccess()
            assertTrue(policy.canRead(true, true, 30_100))
        }
    }

    @Test fun retryAfterIsBoundedAndCannotShortenCooldown() {
        val policy = ChatListReadPolicy()
        policy.onFailure(429, 120, 0)
        policy.onFailure(503, -1, 1)
        assertFalse(policy.canRead(true, true, 119_999))
        assertTrue(policy.canRead(true, true, 120_000))
        policy.onFailure(429, Long.MAX_VALUE, 120_000)
        assertFalse(policy.canRead(true, true, 719_999))
        assertTrue(policy.canRead(true, true, 720_000))
    }

    @Test fun normalFailuresDoNotChangeCadenceOrInvalidateSession() {
        for (status in listOf(null, 400, 401, 403, 404, 500)) {
            val policy = ChatListReadPolicy()
            policy.onFailure(status, null, 0)
            assertTrue(policy.canRead(true, true, 0))
        }
    }

    @Test fun absentPhotosAreNotFetchedOnEveryRefresh() {
        val policy = ChatListReadPolicy()
        assertTrue(policy.beginPhotoRead("staff-a", 0))
        assertFalse(policy.beginPhotoRead("staff-a", 5_000))
        assertFalse(policy.beginPhotoRead("staff-a", 299_999))
        assertTrue(policy.beginPhotoRead("staff-a", 300_000))
        assertTrue(policy.beginPhotoRead("staff-b", 300_000))
    }

    @Test fun overloadStopsPhotoFanoutAndNewScreenHasIndependentBudget() {
        val first = ChatListReadPolicy()
        first.onFailure(503, null, 0)
        assertFalse(first.beginPhotoRead("staff-a", 1))
        assertTrue(ChatListReadPolicy().beginPhotoRead("staff-a", 1))
    }

    @Test fun photoAttemptMemoryIsBounded() {
        val policy = ChatListReadPolicy()
        repeat(256) { assertTrue(policy.beginPhotoRead("staff-$it", 0)) }
        assertFalse(policy.beginPhotoRead("staff-256", 1))
        assertFalse(policy.beginPhotoRead("staff-0", 1))
        assertTrue(policy.beginPhotoRead("staff-256", 300_000))
    }
}
