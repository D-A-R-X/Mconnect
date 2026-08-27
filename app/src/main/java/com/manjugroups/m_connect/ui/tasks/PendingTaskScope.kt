package com.manjugroups.m_connect.ui.tasks

import com.manjugroups.m_connect.network.DailyTaskData

/**
 * Who the Home "you have N pending tasks" banner is about.
 *
 * It is a PERSONAL reminder, so it only ever counts tasks assigned to the
 * signed-in staff member. This used to exempt super-admins and hand them the
 * whole company's open tasks — a developer with zero tasks of their own was
 * nudged about 82 CP visits belonging to other people.
 *
 * Company-wide visibility belongs in the Task Manager, which is built for it
 * and labels whose task each row is.
 */
object PendingTaskScope {

    /** The signed-in staff member's own tasks, and nobody else's. */
    fun ownTasks(tasks: List<DailyTaskData>, myStaffId: String?): List<DailyTaskData> {
        val me = myStaffId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return tasks.filter { it.assignedTo == me }
    }

    /** Open work only — completed and cancelled rows are not a reminder. */
    fun openOnly(tasks: List<DailyTaskData>): List<DailyTaskData> =
        tasks.filter { it.status == "pending" || it.status == "in-progress" }
}
