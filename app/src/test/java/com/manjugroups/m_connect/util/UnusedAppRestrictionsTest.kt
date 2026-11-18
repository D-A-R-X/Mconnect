package com.manjugroups.m_connect.util

import androidx.core.content.UnusedAppRestrictionsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnusedAppRestrictionsTest {
    @Test fun `unsupported setting is hidden and never blocks setup`() {
        val state = UnusedAppRestrictions.uiState(
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
            canOpenSettings = false,
        )

        assertFalse(state.visible)
        assertTrue(state.satisfied)
    }

    @Test fun `enabled restriction without settings screen is non blocking`() {
        val state = UnusedAppRestrictions.uiState(
            UnusedAppRestrictionsConstants.API_31,
            canOpenSettings = false,
        )

        assertFalse(state.visible)
        assertTrue(state.satisfied)
    }

    @Test fun `unknown status without settings screen is non blocking`() {
        val state = UnusedAppRestrictions.uiState(
            status = null,
            canOpenSettings = false,
        )

        assertFalse(state.visible)
        assertTrue(state.satisfied)
    }

    @Test fun `enabled restriction with settings screen remains required`() {
        val state = UnusedAppRestrictions.uiState(
            UnusedAppRestrictionsConstants.API_31,
            canOpenSettings = true,
        )

        assertTrue(state.visible)
        assertFalse(state.satisfied)
        assertFalse(state.restrictionDisabled)
    }

    @Test fun `disabled restriction is shown as complete when supported`() {
        val state = UnusedAppRestrictions.uiState(
            UnusedAppRestrictionsConstants.DISABLED,
            canOpenSettings = true,
        )

        assertTrue(state.visible)
        assertTrue(state.satisfied)
        assertTrue(state.restrictionDisabled)
    }

    @Test fun `only an OS-reported ON status counts as on`() {
        for (on in listOf(
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31,
        )) assertTrue(UnusedAppRestrictions.isRestrictionOn(on))
        // Already off, absent, errored or unknown: never send staff to the toggle.
        for (off in listOf<Int?>(
            UnusedAppRestrictionsConstants.DISABLED,
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
            UnusedAppRestrictionsConstants.ERROR,
            null,
        )) assertFalse(UnusedAppRestrictions.isRestrictionOn(off))
    }

    @Test fun `row subtitle names the system setting and says it must be OFF`() {
        assertTrue(UnusedAppRestrictions.rowSubtitle(true).contains("is OFF"))
        assertTrue(UnusedAppRestrictions.rowSubtitle(false).contains("turn OFF"))
    }

    @Test fun `error status is hidden and never blocks`() {
        val state = UnusedAppRestrictions.uiState(UnusedAppRestrictionsConstants.ERROR, canOpenSettings = true)
        assertFalse(state.visible)
        assertTrue(state.satisfied)
    }
}
