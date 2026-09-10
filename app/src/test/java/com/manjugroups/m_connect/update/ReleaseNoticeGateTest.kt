package com.manjugroups.m_connect.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNoticeGateTest {
    @Test
    fun `new campaign is shown exactly until it is acknowledged`() {
        val campaignId = "release-73-apology"

        assertTrue(ReleaseNoticeGate.shouldShow(campaignId, emptySet()))
        assertFalse(ReleaseNoticeGate.shouldShow(campaignId, setOf(campaignId)))
    }

    @Test
    fun `new release campaign remains eligible after an older campaign was seen`() {
        assertTrue(
            ReleaseNoticeGate.shouldShow(
                campaignId = "release-74-whats-new",
                seenCampaignIds = setOf("release-73-apology"),
            ),
        )
    }

    @Test
    fun `disabled campaign never renders`() {
        assertFalse(ReleaseNoticeGate.shouldShow(null, emptySet()))
        assertFalse(ReleaseNoticeGate.shouldShow(" ", emptySet()))
    }
}
