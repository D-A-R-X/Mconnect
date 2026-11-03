package com.manjugroups.m_connect.auth

import android.content.Context

class OnboardingPrefs(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True once this install has shown the intro video and onboarding. Tied
     * to the install: Android's backup restores these prefs into a fresh
     * install, which used to skip the intro after a reinstall.
     */
    var onboardingCompleted: Boolean
        get() {
            val install = installTimes()
            val decision = completedForInstall(
                flag = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
                stampedInstall = prefs.getLong(KEY_COMPLETED_INSTALL, 0L),
                firstInstallTime = install?.first,
                lastUpdateTime = install?.second,
            )
            decision.adoptInstall?.let { prefs.edit().putLong(KEY_COMPLETED_INSTALL, it).apply() }
            return decision.completed
        }
        set(value) {
            prefs.edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, value)
                .putLong(KEY_COMPLETED_INSTALL, if (value) installTimes()?.first ?: 0L else 0L)
                .apply()
        }

    private fun installTimes(): Pair<Long, Long>? = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.firstInstallTime to info.lastUpdateTime
    }.getOrNull()

    internal data class Decision(val completed: Boolean, val adoptInstall: Long? = null)

    companion object {
        private const val PREFS_NAME = "mconnect_onboarding"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_COMPLETED_INSTALL = "onboarding_completed_install"

        /**
         * Completed only for the install that completed it. A flag without a
         * stamp comes from a build before the stamp existed: kept (and stamped)
         * when the app has been updated since install, which an in-place
         * update always is; on a never-updated fresh install it can only have
         * come from a backup restore, so the intro shows again.
         */
        internal fun completedForInstall(
            flag: Boolean,
            stampedInstall: Long,
            firstInstallTime: Long?,
            lastUpdateTime: Long?,
        ): Decision {
            if (!flag) return Decision(false)
            if (firstInstallTime == null || lastUpdateTime == null) return Decision(true)
            if (stampedInstall == firstInstallTime) return Decision(true)
            if (stampedInstall == 0L && lastUpdateTime != firstInstallTime) {
                return Decision(true, adoptInstall = firstInstallTime)
            }
            return Decision(false)
        }
    }
}
