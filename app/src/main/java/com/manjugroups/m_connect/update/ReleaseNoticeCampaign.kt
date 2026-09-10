package com.manjugroups.m_connect.update

import android.content.Context

data class ReleaseNoticeCampaign(
    val id: String,
    val label: String,
    val title: String,
    val tamilTitle: String,
    val message: String,
    val highlights: List<String>,
    val attribution: String,
)

/**
 * Release-only switch for the notice shown after sign-in.
 *
 * Keep the same ID to prevent repeats. Change the campaign only when a release
 * is intentionally meant to show a new notice; set it to null for no banner.
 */
object ReleaseNoticeConfig {
    val activeCampaign: ReleaseNoticeCampaign? = ReleaseNoticeCampaign(
        id = "2026-09-10-service-apology-v1",
        label = "SERVICE UPDATE",
        title = "Sorry for the inconvenience",
        tamilTitle = "தடங்கல்களுக்கு வருந்துகிறோம்\nThadangalukku varundhugindrom",
        message = "Thank you for your patience. We have improved MConnect and will continue making your daily work faster and more reliable.",
        highlights = listOf(
            "Improved CP and OTP handling",
            "More reliable visit completion and syncing",
            "General stability improvements",
        ),
        attribution = "MMS IT Team",
    )
}

internal object ReleaseNoticeGate {
    fun shouldShow(campaignId: String?, seenCampaignIds: Set<String>): Boolean =
        !campaignId.isNullOrBlank() && campaignId !in seenCampaignIds
}

class ReleaseNoticePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShow(campaign: ReleaseNoticeCampaign): Boolean =
        ReleaseNoticeGate.shouldShow(campaign.id, seenCampaignIds())

    fun markSeen(campaignId: String) {
        if (campaignId.isBlank()) return
        preferences.edit()
            .putStringSet(KEY_SEEN_CAMPAIGN_IDS, seenCampaignIds() + campaignId)
            .apply()
    }

    private fun seenCampaignIds(): Set<String> =
        preferences.getStringSet(KEY_SEEN_CAMPAIGN_IDS, emptySet())?.toSet().orEmpty()

    private companion object {
        const val PREFS_NAME = "release_notice_preferences"
        const val KEY_SEEN_CAMPAIGN_IDS = "seen_campaign_ids"
    }
}
