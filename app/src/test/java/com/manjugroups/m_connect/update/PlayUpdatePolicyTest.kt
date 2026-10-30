package com.manjugroups.m_connect.update

import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayUpdatePolicyTest {
    @Test
    fun `prefers background-capable download when both update types are allowed`() {
        assertEquals(
            AppUpdateType.FLEXIBLE,
            preferredPlayUpdateType(UpdateAvailability.UPDATE_AVAILABLE, true, true),
        )
    }

    @Test
    fun `uses whichever supported update type Play offers`() {
        assertEquals(
            AppUpdateType.FLEXIBLE,
            preferredPlayUpdateType(UpdateAvailability.UPDATE_AVAILABLE, true, false),
        )
        assertEquals(
            AppUpdateType.IMMEDIATE,
            preferredPlayUpdateType(UpdateAvailability.UPDATE_AVAILABLE, false, true),
        )
        assertNull(preferredPlayUpdateType(UpdateAvailability.UPDATE_AVAILABLE, false, false))
    }

    @Test
    fun `resumes an immediate update already in progress instead of switching modes`() {
        assertEquals(
            AppUpdateType.IMMEDIATE,
            preferredPlayUpdateType(
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                true,
                true,
            ),
        )
    }

    @Test
    fun `does not start a flow for a release not offered to this account`() {
        assertNull(preferredPlayUpdateType(UpdateAvailability.UPDATE_NOT_AVAILABLE, true, true))
        assertNull(preferredPlayUpdateType(UpdateAvailability.UNKNOWN, true, true))
    }
}
