package com.manjugroups.m_connect.ui.tasks

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import com.manjugroups.m_connect.ui.common.commitOnce

/**
 * Routes a daily task to the place the user can actually DO it.
 *
 * Source-backed tasks (attendance review, CP/SV visits, land inspection,
 * issues) open the matching mobile screen. Anything without a mobile home
 * (manual desk tasks, web-only sources) shows a short dialog pointing the
 * user at the web app — instead of dead-ending on a Task Manager list that
 * can't complete the work.
 */
object TaskNavRouter {

    // Web app origin — tasks with no mobile screen are completed here.
    private const val WEB_APP_URL = "https://mg.theairix.com"

    fun open(activity: FragmentActivity, task: DailyTaskData) {
        val source = task.sourceReferenceType?.trim()?.lowercase().orEmpty()
        val fragment: Fragment? = when {
            source == "staff-attendance" ->
                com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment()
            source == "client_place_visit" || source == "clientplacevisit" ->
                com.manjugroups.m_connect.ui.marketing.CpVisitsFragment()
            source == "site_visit" || source == "sitevisit" ->
                com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment()
            source == "land-inspection" || source == "landinspection" || source == "landproperty" ->
                com.manjugroups.m_connect.ui.library.land.LandInspectionFragment()
            source == "issue" ->
                com.manjugroups.m_connect.ui.issues.IssuesFragment()
            // Approval/request tasks — open the module screen where the work is
            // actually done (approve / act on it) instead of dead-ending on an
            // "open in web" dialog.
            source == "leave" ->
                com.manjugroups.m_connect.ui.hr.LeavesFragment.newInstance()
            source == "permission" ->
                com.manjugroups.m_connect.ui.hr.PermissionsFragment.newInstance()
            source == "fine" || source == "fines" || source.startsWith("fine_") ->
                com.manjugroups.m_connect.ui.hr.FinesDeductionsFragment()
            source.startsWith("loan_") ->
                com.manjugroups.m_connect.ui.library.loans.LoanDeskFragment()
            source == "loan" || source.startsWith("loan") ->
                com.manjugroups.m_connect.ui.library.loans.LoansFragment()
            source.contains("booking") ->
                com.manjugroups.m_connect.ui.marketing.bookings.BookingsFragment.newInstance()
            else -> null
        }

        if (fragment != null) {
            activity.supportFragmentManager.beginTransaction()
                .applySmoothTransitions()
                .replace(R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commitOnce()
        } else {
            showWebTaskDialog(activity, task)
        }
    }

    /** Web-only task → app-styled sheet with Open / Copy of the deep link. */
    private fun showWebTaskDialog(activity: FragmentActivity, task: DailyTaskData) {
        val label = task.title?.trim().takeUnless { it.isNullOrBlank() }
            ?: task.taskName?.trim().takeUnless { it.isNullOrBlank() }
            ?: "This task"
        val url = task.actionUrl?.trim()?.let { path ->
            if (path.startsWith("http")) path else WEB_APP_URL + (if (path.startsWith("/")) path else "/$path")
        } ?: WEB_APP_URL

        WebTaskLinkBottomSheet.newInstance(title = label, url = url)
            .show(activity.supportFragmentManager, "web_task_link")
    }
}
