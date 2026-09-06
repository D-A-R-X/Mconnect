package com.manjugroups.m_connect.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInvalidationPolicyTest {
    private val mmsHost = "api-mfpl.theairix.com"

    @Test
    fun `explicit expired-session MMS 401 invalidates session`() {
        assertTrue(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
                requestPath = "/api/hr/attendance/today",
                responseBody = """{"success":false,"error":"Invalid or expired session"}""",
            ),
        )
    }

    @Test
    fun `operation-specific MMS 401 does not invalidate session`() {
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
                requestPath = "/api/marketing/clientPlaceVisits/list",
                responseBody = """{"success":false,"error":"You cannot view this team"}""",
            ),
        )
        assertFalse(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
                requestPath = "/api/hr/staff/list",
                responseBody = """{"success":false,"error":"Unauthorized"}""",
            ),
        )
    }

    @Test
    fun `validate-session MMS 401 remains authoritative without a body`() {
        assertTrue(
            SessionInvalidationPolicy.shouldInvalidate(
                responseCode = 401,
                authorizationHeader = "Bearer real-session-token",
                requestHost = mmsHost,
                sessionAuthorityHost = mmsHost,
                requestPath = "/api/auth/validate-session",
                responseBody = null,
            ),
        )
    }

    @Test
    fun `known terminal session messages and codes invalidate`() {
        assertTrue(SessionInvalidationResponse.isTerminal("""{"message":"Signed in on another device"}"""))
        assertTrue(SessionInvalidationResponse.isTerminal("""{"code":"SESSION_REVOKED"}"""))
        assertTrue(SessionInvalidationResponse.isTerminal("""{"error":"Not authenticated"}"""))
        assertFalse(SessionInvalidationResponse.isTerminal("""{"error":"Unauthorized"}"""))
        assertFalse(SessionInvalidationResponse.isTerminal(null))
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

    @Test
    fun `late unauthorized response from previous token cannot expire new session`() {
        val signal = SessionInvalidationSignal("old-session-token")

        assertFalse(signal.matchesCurrentToken("new-session-token"))
        assertTrue(signal.matchesCurrentToken("old-session-token"))
        assertFalse(signal.matchesCurrentToken(null))
    }

    @Test
    fun `bearer parsing is case insensitive and rejects malformed headers`() {
        assertTrue(SessionInvalidationToken.extract("bearer token-123") == "token-123")
        assertTrue(SessionInvalidationToken.extract("  Bearer   token-456  ") == "token-456")
        assertTrue(SessionInvalidationToken.extract("Basic token-123") == null)
        assertTrue(SessionInvalidationToken.extract("Bearer") == null)
    }
}
