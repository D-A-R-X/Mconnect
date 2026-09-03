package com.manjugroups.m_connect.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInvalidationPolicyTest {
    private val mmsHost = "api-mfpl.theairix.com"

    @Test
    fun `authenticated MMS 401 invalidates session`() {
        assertTrue(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
            ),
        )
    }

    @Test
    fun `direct GeoTrack 401 does not invalidate MMS session`() {
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = "api-geo.theairix.com",
                sessionAuthorityHost = mmsHost,
            ),
        )
    }

    @Test
    fun `bearer-less login 401 does not invalidate existing session`() {
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = null,
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
            ),
        )
    }

    @Test
    fun `non-401 response never invalidates session`() {
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 403,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
            ),
        )
    }

    @Test
    fun `developer bypass token keeps existing exploration behavior`() {
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer ${AuthBypass.TOKEN}",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
            ),
        )
    }
}
