package com.manjugroups.m_connect.network

/** Public, write-only mobile project token, not a personal/admin API key. */
internal object PostHogConfig {
    const val ENABLED = true
    const val BATCH_URL = "https://us.i.posthog.com/batch/"
    const val PROJECT_TOKEN = "phc_zxNkVWBu5hjr2sMQZEEMJTBMrXGnnT7GmAdLJbhXATqL"
}
