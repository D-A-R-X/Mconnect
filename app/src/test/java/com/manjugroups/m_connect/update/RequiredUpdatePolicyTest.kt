package com.manjugroups.m_connect.update

import com.manjugroups.m_connect.network.MobileAppVersionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredUpdatePolicyTest {

    private val now = 1_763_452_911_000L
    private fun server(build: Int) = RequiredUpdate(build, "1.0 ($build)", "https://store/x", true, false, now)

    @Test
    fun aSatisfiedRequirementIsNotEnforced() {
        assertNull(RequiredUpdatePolicy.enforce(server(108), installedBuild = 108))
        assertNull(RequiredUpdatePolicy.enforce(server(108), installedBuild = 109))
        assertEquals(109, RequiredUpdatePolicy.enforce(server(109), installedBuild = 108)?.build)
        assertNull(RequiredUpdatePolicy.enforce(null, installedBuild = 1))
    }

    @Test
    fun aPlayOfferBecomesRequiredAndSurvivesFlakyNoUpdateAnswers() {
        val afterOffer = RequiredUpdatePolicy.withPlayOffer(null, 109, "https://play", 108, now)
        assertNotNull(afterOffer)
        assertTrue(afterOffer!!.fromPlay)
        // Play then says "no update" on reopen (cache / staged rollout): still required.
        val afterNoUpdate = RequiredUpdatePolicy.withPlayNoUpdate(afterOffer, serverHasNoMinimum = true, installedBuild = 108, nowMs = now + 60_000)
        assertEquals(109, afterNoUpdate?.build)
        // Only a long-halted rollout (and no server minimum) releases it.
        val stale = RequiredUpdatePolicy.withPlayNoUpdate(
            afterOffer, serverHasNoMinimum = true, installedBuild = 108,
            nowMs = now + RequiredUpdatePolicy.PLAY_ONLY_STALE_MS,
        )
        assertNull(stale)
        // A server minimum is never dropped by Play's answer.
        val withServer = RequiredUpdatePolicy.withServer(afterOffer, server(109), 108)
        assertEquals(109, RequiredUpdatePolicy.withPlayNoUpdate(withServer, false, 108, now + RequiredUpdatePolicy.PLAY_ONLY_STALE_MS * 2)?.build)
    }

    @Test
    fun aRepeatedPlayOfferKeepsTheFirstTimeItWasSeen() {
        val first = RequiredUpdatePolicy.withPlayOffer(null, 109, "https://play", 108, now)
        val again = RequiredUpdatePolicy.withPlayOffer(first, 109, "https://play", 108, now + 3_600_000)
        assertEquals(now, again?.savedAtMs)
    }

    @Test
    fun theServerCanDropItsMinimumButNotAPlayRequirement() {
        val stored = RequiredUpdatePolicy.withServer(null, server(109), 108)
        assertEquals(109, stored?.build)
        assertNull("server lowered its minimum", RequiredUpdatePolicy.withServer(stored, null, 108))

        val play = RequiredUpdatePolicy.withPlayOffer(null, 110, "https://play", 108, now)
        val stillPlay = RequiredUpdatePolicy.withServer(play, null, 108)
        assertEquals(110, stillPlay?.build)
    }

    @Test
    fun theHigherOfPlayAndServerWins() {
        val play = RequiredUpdatePolicy.withPlayOffer(null, 110, "https://play", 108, now)
        val merged = RequiredUpdatePolicy.withServer(play, server(109), 108)
        assertEquals(110, merged?.build)
        val sameBuild = RequiredUpdatePolicy.withServer(
            RequiredUpdatePolicy.withPlayOffer(null, 109, "https://play", 108, now), server(109), 108,
        )
        assertTrue(sameBuild!!.fromPlay && sameBuild.fromServer)
        assertEquals("https://store/x", sameBuild.url)
    }

    @Test
    fun aServerMinimumWithoutAStoreLinkStillForcesTheUpdate() {
        val policy = MobileAppVersionResponse(
            success = true,
            minimumSupportedVersion = "1.0",
            minimumSupportedBuildNumber = 109,
            updateUrl = null,
        )
        val required = serverRequirement(policy, installedBuildNumber = 108, nowMs = now)
        assertEquals(109, required?.build)
        assertEquals(InAppUpdateManager.PLAY_STORE_URL, required?.url)
        assertEquals("1.0 (109)", required?.version)
        assertNull(serverRequirement(policy, installedBuildNumber = 109, nowMs = now))
    }
}
