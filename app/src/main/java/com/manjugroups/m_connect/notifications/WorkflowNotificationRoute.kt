package com.manjugroups.m_connect.notifications

import android.content.Intent

data class WorkflowNotificationRoute(
    val targetTab: String,
    val targetScreen: String?,
    val targetMode: String,
    val entityId: String?,
    val actionUrl: String?,
    val channelId: String?,
    val conversationId: String?,
    val messageId: String?,
    val targetTitle: String?
) {
    companion object {
        const val EXTRA_TARGET_TAB = "workflow_target_tab"
        const val EXTRA_TARGET_SCREEN = "workflow_target_screen"
        const val EXTRA_TARGET_MODE = "workflow_target_mode"
        const val EXTRA_ENTITY_ID = "workflow_entity_id"
        const val EXTRA_ACTION_URL = "workflow_action_url"
        const val EXTRA_CHANNEL_ID = "workflow_channel_id"
        const val EXTRA_CONVERSATION_ID = "workflow_conversation_id"
        const val EXTRA_MESSAGE_ID = "workflow_message_id"
        const val EXTRA_TARGET_TITLE = "workflow_target_title"

        const val TAB_HOME = "home"
        const val TAB_HR = "hr"
        const val TAB_CHAT = "chat"

        const val SCREEN_LEAVES = "leaves"
        const val SCREEN_PERMISSIONS = "permissions"
        const val SCREEN_CHAT_CHANNEL = "chat_channel"
        const val SCREEN_CHAT_CONVERSATION = "chat_conversation"

        const val MODE_HISTORY = "history"
        const val MODE_APPROVAL = "approval"
        const val MODE_CHAT = "chat"

        fun fromIntent(intent: Intent?): WorkflowNotificationRoute? {
            val source = intent ?: return null
            val targetTab = source.getStringExtra(EXTRA_TARGET_TAB) ?: return null
            return WorkflowNotificationRoute(
                targetTab = targetTab,
                targetScreen = source.getStringExtra(EXTRA_TARGET_SCREEN),
                targetMode = source.getStringExtra(EXTRA_TARGET_MODE) ?: MODE_HISTORY,
                entityId = source.getStringExtra(EXTRA_ENTITY_ID),
                actionUrl = source.getStringExtra(EXTRA_ACTION_URL),
                channelId = source.getStringExtra(EXTRA_CHANNEL_ID),
                conversationId = source.getStringExtra(EXTRA_CONVERSATION_ID),
                messageId = source.getStringExtra(EXTRA_MESSAGE_ID),
                targetTitle = source.getStringExtra(EXTRA_TARGET_TITLE)
            )
        }
    }
}
