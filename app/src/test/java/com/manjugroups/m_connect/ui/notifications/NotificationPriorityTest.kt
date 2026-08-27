package com.manjugroups.m_connect.ui.notifications

import com.manjugroups.m_connect.network.NotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approvals and tasks are work someone is waiting on, so they get the peach
 * row and the phone's notification tray. Everything else stays quiet — if
 * every notification shouted, none of them would.
 */
class NotificationPriorityTest {

    private fun notif(
        id: String,
        type: String?,
        title: String? = "Something happened",
        read: Boolean = false,
    ) = NotificationData(
        id = id,
        type = type,
        title = title,
        message = "body",
        referenceId = null,
        referenceType = null,
        read = read,
        createdAt = null,
    )

    @Test
    fun `tasks and approvals are high priority`() {
        for (type in listOf(
            "task-manager-task",
            "task-overdue",
            "task-extension-request",
            "task-attendance-blocked",
            "leave-approval",
            "permission-request",
            "attendance-approval",
            "cp-approval",
            "wfh-request",
        )) {
            assertTrue(type, NotificationPriority.isHighPriority(type, "Any title"))
        }
    }

    @Test
    fun `chats and informational pings are not`() {
        for (type in listOf("chat-message", "chat-mention", "announcement", "birthday")) {
            assertFalse(type, NotificationPriority.isHighPriority(type, "Any title"))
        }
    }

    @Test
    fun `a decision someone already made about you is not actionable`() {
        // These arrive AFTER the work is done — nothing left to act on, so
        // they must not buzz the phone.
        assertFalse(NotificationPriority.isHighPriority("task-status-update", "Task updated"))
        assertFalse(
            NotificationPriority.isHighPriority("task-extension-reviewed", "Extension reviewed"),
        )
    }

    @Test
    fun `a recognised type is never promoted by its title`() {
        // "chat-message" titled "Approval needed" must stay a chat — the type
        // is authoritative, the title is only a legacy fallback.
        assertFalse(NotificationPriority.isHighPriority("chat-message", "Approval needed"))
    }

    @Test
    fun `legacy rows with no type fall back to the title`() {
        assertTrue(NotificationPriority.isHighPriority(null, "Leave approval pending"))
        assertTrue(NotificationPriority.isHighPriority("", "Task overdue"))
        assertFalse(NotificationPriority.isHighPriority(null, "Someone mentioned you"))
    }

    @Test
    fun `case and padding do not change the verdict`() {
        assertTrue(NotificationPriority.isHighPriority("  TASK-OVERDUE  ", null))
    }

    @Test
    fun `only unread actionable items reach the tray`() {
        val items = listOf(
            notif("1", "task-overdue"),
            notif("2", "task-overdue", read = true),
            notif("3", "chat-message"),
            notif("4", "leave-approval"),
        )

        val tray = NotificationPriority.trayWorthy(items)

        // Read ones are excluded — re-raising what someone already saw trains
        // people to swipe the tray away without looking.
        assertEquals(listOf("1", "4"), tray.map { it.id })
    }
}
