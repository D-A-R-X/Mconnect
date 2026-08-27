package com.manjugroups.m_connect.ui.tasks

import com.manjugroups.m_connect.network.DailyTaskData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported bug: a super-admin developer with zero tasks of their own was
 * told "You have 82 pending tasks" — every staff member's open work, inside
 * what is meant to be a personal reminder.
 */
class PendingTaskScopeTest {

    private fun task(id: String, assignedTo: String?, status: String = "pending") =
        DailyTaskData(id = id, assignedTo = assignedTo, status = status)

    @Test
    fun `only the signed-in staff member's tasks are counted`() {
        val mine = task("1", "staff-me")
        val theirs = task("2", "staff-other")

        val result = PendingTaskScope.ownTasks(listOf(mine, theirs), "staff-me")

        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun `a super admin is not special here`() {
        // No role is passed in at all — that is the point. There is no branch
        // left that can hand anyone someone else's personal reminder.
        val others = (1..82).map { task("t$it", "staff-other") }

        val result = PendingTaskScope.ownTasks(others, "staff-admin")

        assertTrue("an admin owes nothing just by being an admin", result.isEmpty())
    }

    @Test
    fun `an unknown staff id yields nothing rather than everything`() {
        val tasks = listOf(task("1", "staff-a"), task("2", "staff-b"))

        // Failing OPEN here would show the whole company's tasks to a session
        // that could not even identify itself.
        assertTrue(PendingTaskScope.ownTasks(tasks, null).isEmpty())
        assertTrue(PendingTaskScope.ownTasks(tasks, "").isEmpty())
        assertTrue(PendingTaskScope.ownTasks(tasks, "   ").isEmpty())
    }

    @Test
    fun `a task with no assignee never counts as mine`() {
        val orphan = task("1", null)

        assertTrue(PendingTaskScope.ownTasks(listOf(orphan), "staff-me").isEmpty())
    }

    @Test
    fun `only open work is a reminder`() {
        val open = listOf(
            task("1", "staff-me", "pending"),
            task("2", "staff-me", "in-progress"),
        )
        val closed = listOf(
            task("3", "staff-me", "completed"),
            task("4", "staff-me", "cancelled"),
        )

        val result = PendingTaskScope.openOnly(open + closed)

        assertEquals(listOf("1", "2"), result.map { it.id })
    }
}
