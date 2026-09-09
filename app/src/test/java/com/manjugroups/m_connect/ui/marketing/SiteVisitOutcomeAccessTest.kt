package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteVisitOutcomeAccessTest {

    @Test
    fun `outcome is recordable throughout counselling and return journey`() {
        assertTrue(siteVisitOutcomeCanBeRecorded("on_counselling", null))
        assertTrue(siteVisitOutcomeCanBeRecorded("picked-from-site", ""))
        assertTrue(siteVisitOutcomeCanBeRecorded("dropped", null))
    }

    @Test
    fun `completed without outcome remains recoverable`() {
        assertTrue(siteVisitOutcomeCanBeRecorded("completed", null))
        assertTrue(siteVisitOutcomeCanBeRecorded(" completed ", " "))
    }

    @Test
    fun `saved outcome and pre-counselling statuses stay closed`() {
        assertFalse(siteVisitOutcomeCanBeRecorded("completed", "interested"))
        assertFalse(siteVisitOutcomeCanBeRecorded("scheduled", null))
        assertFalse(siteVisitOutcomeCanBeRecorded("on_site", null))
    }
}
