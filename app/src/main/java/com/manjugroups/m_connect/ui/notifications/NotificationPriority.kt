package com.manjugroups.m_connect.ui.notifications

import com.manjugroups.m_connect.network.NotificationData
import java.util.Locale

/**
 * Which notifications are ACTIONABLE — something the staff has to do or
 * decide — as opposed to something that merely happened.
 *
 * Approvals and tasks block other people's work while they sit unread, so
 * they are tinted in the list and also raised on the phone's notification
 * tray. Chat mentions, status updates and informational pings stay in the
 * default styling and do not compete for attention.
 *
 * Matching is on the notification TYPE first, because that is set by the
 * backend and stable. Title text is only a fallback for older rows written
 * before the types settled.
 */
object NotificationPriority {

    /** Type prefixes/keys the backend uses for work that needs an action. */
    private val HIGH_PRIORITY_TYPE_MARKERS = listOf(
        "task-",          // task-manager-task, task-overdue, task-extension-*
        "approval",
        "approve",
        "leave-",
        "permission-",
        "attendance-",
        "cp-approval",
        "wfh-",
    )

    /** Types that are explicitly NOT actionable even though they may match
     *  a marker above — a decision someone else already made about you. */
    private val NEVER_HIGH_PRIORITY_TYPES = setOf(
        "task-status-update",
        "task-extension-reviewed",
    )

    /** Fallback for legacy rows whose type is missing or unrecognised. */
    private val TITLE_MARKERS = listOf("approval", "approve", "overdue", "pending")

    fun isHighPriority(item: NotificationData): Boolean =
        isHighPriority(item.type, item.title)

    fun isHighPriority(type: String?, title: String?): Boolean {
        val normalizedType = type?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalizedType.isNotEmpty()) {
            if (NEVER_HIGH_PRIORITY_TYPES.contains(normalizedType)) return false
            if (HIGH_PRIORITY_TYPE_MARKERS.any { normalizedType.contains(it) }) return true
            // A recognised type that matched nothing is a deliberate "no" —
            // don't let the loose title fallback promote it back.
            return false
        }
        val normalizedTitle = title?.trim()?.lowercase(Locale.US).orEmpty()
        return TITLE_MARKERS.any { normalizedTitle.contains(it) }
    }

    /**
     * Unread, actionable notifications the phone should surface on its tray.
     * Read rows are excluded: the staff has already seen them, and re-raising
     * them would train people to swipe the tray away without looking.
     */
    fun trayWorthy(items: List<NotificationData>): List<NotificationData> =
        items.filter { !it.read && isHighPriority(it) }
}
