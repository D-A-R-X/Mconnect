package com.manjugroups.m_connect.ui.tasks

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.ui.common.applySmoothTransitions

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
        val fragment: Fragment? = when (task.sourceReferenceType?.trim()?.lowercase()) {
            "staff-attendance" ->
                com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment()
            "client_place_visit", "clientplacevisit" ->
                com.manjugroups.m_connect.ui.marketing.CpVisitsFragment()
            "site_visit", "sitevisit" ->
                com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment()
            "land-inspection", "landinspection", "landproperty" ->
                com.manjugroups.m_connect.ui.library.land.LandInspectionFragment()
            "issue" ->
                com.manjugroups.m_connect.ui.issues.IssuesFragment()
            else -> null
        }

        if (fragment != null) {
            activity.supportFragmentManager.beginTransaction()
                .applySmoothTransitions()
                .replace(R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commit()
        } else {
            showWebTaskDialog(activity, task)
        }
    }

    /** Web-only task → tell the user to finish it on the web app. */
    private fun showWebTaskDialog(activity: FragmentActivity, task: DailyTaskData) {
        val label = task.title?.trim().takeUnless { it.isNullOrBlank() }
            ?: task.taskName?.trim().takeUnless { it.isNullOrBlank() }
            ?: "This task"
        val url = task.actionUrl?.trim()?.let { path ->
            if (path.startsWith("http")) path else WEB_APP_URL + (if (path.startsWith("/")) path else "/$path")
        } ?: WEB_APP_URL

        AlertDialog.Builder(activity)
            .setTitle("Complete on the web app")
            .setMessage("“$label” is completed from the web app. Open it in a browser to finish this task.")
            .setPositiveButton("Open Web") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
