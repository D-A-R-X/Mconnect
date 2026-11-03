package com.manjugroups.m_connect.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPrefsTest {
    private val installed = 1_790_000_000_000L
    private val updated = 1_793_000_000_000L

    private fun decide(flag: Boolean, stamp: Long, first: Long?, last: Long?) =
        OnboardingPrefs.completedForInstall(flag, stamp, first, last)

    @Test fun neverCompletedShowsTheIntro() {
        assertEquals(OnboardingPrefs.Decision(false), decide(false, 0L, installed, installed))
        assertEquals(OnboardingPrefs.Decision(false), decide(false, installed, installed, updated))
    }

    @Test fun completedOnThisInstallStaysCompleted() {
        assertEquals(OnboardingPrefs.Decision(true), decide(true, installed, installed, installed))
        assertEquals(OnboardingPrefs.Decision(true), decide(true, installed, installed, updated))
    }

    @Test fun existingUserUpdatingFromAnOlderBuildKeepsSkippingAndIsStamped() {
        assertEquals(OnboardingPrefs.Decision(true, adoptInstall = installed), decide(true, 0L, installed, updated))
    }

    @Test fun flagRestoredFromBackupIntoAFreshInstallShowsTheIntroAgain() {
        // Restored from a build before the stamp existed: fresh install, never updated.
        assertEquals(OnboardingPrefs.Decision(false), decide(true, 0L, updated, updated))
        // Restored from a stamped backup of an earlier install.
        assertEquals(OnboardingPrefs.Decision(false), decide(true, installed, updated, updated))
    }

    @Test fun unknownInstallTimesFallBackToTheStoredFlag() {
        assertEquals(OnboardingPrefs.Decision(true), decide(true, 0L, null, null))
    }
}
