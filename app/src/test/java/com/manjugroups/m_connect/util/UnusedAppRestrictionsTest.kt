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
}
