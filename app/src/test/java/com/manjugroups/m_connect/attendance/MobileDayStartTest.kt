package com.manjugroups.m_connect.attendance

import com.manjugroups.m_connect.network.SessionData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDayStartTest {

    private fun session(
        inTime: String?,
        source: String?,
        outTime: String? = null,
        outSource: String? = null,
    ) = SessionData(punchInTime = inTime, punchOutTime = outTime, source = source, punchOutSource = outSource)

    @Test
    fun `a gate punch-in alone is a day started elsewhere`() {
        assertTrue(MobileDayStart.startedElsewhere(listOf(session("2026-10-23T09:29:33+05:30", "biometric"))))
    }

    @Test
    fun `gate in and out with no app punch is still started elsewhere`() {
        assertTrue(
            MobileDayStart.startedElsewhere(
                listOf(session("2026-10-23T09:29:33+05:30", "biometric", "2026-10-23T13:00:00+05:30", "biometric")),
            ),
        )
    }

    @Test
    fun `an app clock-in means the day is started in the app`() {
        assertFalse(
            MobileDayStart.startedElsewhere(
                listOf(
                    session("2026-10-23T09:29:33+05:30", "biometric", "2026-10-23T13:00:00+05:30", "biometric"),
                    session("2026-10-23T14:00:00+05:30", "mobile"),
                ),
            ),
        )
    }

    @Test
    fun `an app clock-out of a gate session ends the day, not a new start`() {
        assertFalse(
            MobileDayStart.startedElsewhere(
                listOf(session("2026-10-23T09:29:33+05:30", "biometric", "2026-10-23T18:30:00+05:30", "mobile")),
            ),
        )
    }

    @Test
    fun `no punches today is not started`() {
        assertFalse(MobileDayStart.startedElsewhere(emptyList()))
    }
}
