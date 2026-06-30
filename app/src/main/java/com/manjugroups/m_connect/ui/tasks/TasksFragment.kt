package com.manjugroups.m_connect.ui.tasks

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.TaskData
import com.manjugroups.m_connect.network.TaskSummaryData
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "My Tasks" — lists tasks assigned to the authenticated staff across
 * all projects. Mirrors pencil node `gQjwe` (TaskMenu) and `Xh3t5` (empty).
 *
 * Tap a row to open [TaskDetailFragment].
 */
class TasksFragment : Fragment() {

    private enum class Filter { ALL, IN_PROGRESS, COMPLETED }

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var filter: Filter = Filter.ALL
    private var allTasks: List<TaskData> = emptyList()

    private var taskListContainer: LinearLayout? = null
    private var skeletonContainer: LinearLayout? = null
    private var skeletonAnimator: ObjectAnimator? = null
    private var emptyState: View? = null
    private var tabAll: TextView? = null
    private var tabInProgress: TextView? = null
    private var tabCompleted: TextView? = null
    private var tvSummaryTotal: TextView? = null
    private var tvSummaryInProgress: TextView? = null
    private var tvSummaryCompleted: TextView? = null
    private var tvOverallProgress: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_tasks, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnTasksBack).setOnClickListener {
            navigateUp()
        }

        // Push the blue header below the OS status bar and grow its height
        // by the same amount so the gradient still extends edge-to-edge
        // behind the system clock. Without this, the header started at
        // y=0 in full-bleed mode leaving a white strip above the title.
        val headerContainer = view.findViewById<View>(R.id.headerContainer)
        val baseHeaderHeight = headerContainer.layoutParams.height
        val baseHeaderTopPadding = headerContainer.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(headerContainer) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
            ).top
            v.layoutParams = v.layoutParams.apply { height = baseHeaderHeight + topInset }
            v.setPadding(
                v.paddingLeft,
                baseHeaderTopPadding + topInset,
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(headerContainer)

        taskListContainer = view.findViewById(R.id.taskList)
        emptyState = view.findViewById(R.id.emptyState)
        tabAll = view.findViewById(R.id.tabAll)
        tabInProgress = view.findViewById(R.id.tabInProgress)
        tabCompleted = view.findViewById(R.id.tabCompleted)

        // Pull-to-refresh — same load path as initial open + tab returns.
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.tasksRefresh
        ).setupPullToRefresh { loadTasks() }
        // The redesigned layout dropped the skeleton + summary header — left
        // the fields nullable so existing call-sites (`?.text`, `?: return`)
        // silently no-op. Re-introduce the views if the summary returns.

        val chips = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.categoryPills)
        chips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            filter = when (checkedId) {
                R.id.tabInProgress -> Filter.IN_PROGRESS
                R.id.tabPending -> Filter.ALL // Assuming Pending maps to All or similar in this context
                R.id.tabCompleted -> Filter.COMPLETED
                else -> Filter.ALL
            }
            renderTasks()
        }

        loadTasks()
    }

    private fun setFilter(next: Filter) {
        if (filter == next) return
        filter = next
        applyTabStyles()
        renderTasks()
    }

    private fun applyTabStyles() {
        listOf(
            tabAll to (filter == Filter.ALL),
            tabInProgress to (filter == Filter.IN_PROGRESS),
            tabCompleted to (filter == Filter.COMPLETED),
        ).forEach { (tab, selected) ->
            tab ?: return@forEach
            if (selected) {
                tab.setBackgroundResource(R.drawable.bg_task_status_pill_active)
                tab.setTextColor(Color.WHITE)
            } else {
                tab.background = null
                tab.setTextColor(Color.parseColor("#475467"))
            }
        }
    }

    fun loadTasks() {
        // Skip the skeleton when a pull-refresh is already on screen —
        // the swipe spinner is enough of a loading signal.
        val tasksRefresh = view?.findViewById<
            androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        >(R.id.tasksRefresh)
        val isPullRefresh = tasksRefresh?.isRefreshing == true
        // Skeleton + clear only on the genuine first load. A pull-refresh
        // or the onResume reload (coming back from a task detail) keeps the
        // existing rows on screen — renderTasks() rebuilds them in place,
        // so we never blank loaded content back to a skeleton.
        if (!isPullRefresh && allTasks.isEmpty()) {
            startSkeleton()
            emptyState?.visibility = View.GONE
            taskListContainer?.removeAllViews()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tasksResp = api.getMyTasks(session.bearerToken)
                val summaryResp = runCatching { api.getMyTasksSummary(session.bearerToken) }.getOrNull()
                allTasks = tasksResp.tasks
                renderSummary(summaryResp?.summary ?: deriveSummary(allTasks))
                renderTasks()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to load tasks: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                allTasks = emptyList()
                renderTasks()
            } finally {
                stopSkeleton()
                tasksRefresh?.isRefreshing = false
            }
        }
    }

    private fun startSkeleton() {
        val sk = skeletonContainer ?: return
        com.manjugroups.m_connect.ui.common.SkeletonUtils.startSkeletonPulse(sk)
        taskListContainer?.visibility = View.GONE
    }

    private fun stopSkeleton() {
        val sk = skeletonContainer ?: return
        com.manjugroups.m_connect.ui.common.SkeletonUtils.stopSkeletonPulse(sk)
        taskListContainer?.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            // Blue full-bleed header: gradient draws behind a transparent
            // status bar; light icons stay readable on the blue.
            main.setTopBarAppearance(
                android.graphics.Color.parseColor("#0B61CA"),
                darkStatusIcons = false,
                fullBleed = true,
            )
        }
        // Coming back from TaskDetailFragment after an update — refresh.
        if (allTasks.isNotEmpty()) loadTasks()
    }

    override fun onDestroyView() {
        stopSkeleton()
        super.onDestroyView()
    }

    private fun deriveSummary(tasks: List<TaskData>): TaskSummaryData {
        var inProg = 0
        var done = 0
        var prog = 0
        for (t in tasks) {
            when (t.status) {
                "in-progress" -> inProg++
                "completed" -> done++
            }
            prog += t.progress ?: 0
        }
        return TaskSummaryData(
            total = tasks.size,
            inProgress = inProg,
            completed = done,
            overallProgress = if (tasks.isNotEmpty()) prog / tasks.size else 0
        )
    }

    private fun renderSummary(summary: TaskSummaryData) {
        tvSummaryTotal?.text = summary.total.toString()
        tvSummaryInProgress?.text = summary.inProgress.toString()
        tvSummaryCompleted?.text = summary.completed.toString()
        tvOverallProgress?.text = "${summary.overallProgress}%"
    }

    private fun renderTasks() {
        val container = taskListContainer ?: return
        container.removeAllViews()
        val visible = when (filter) {
            Filter.ALL -> allTasks
            Filter.IN_PROGRESS -> allTasks.filter { it.status == "in-progress" }
            Filter.COMPLETED -> allTasks.filter { it.status == "completed" }
        }
        emptyState?.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (visible.isEmpty()) return

        val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        for (task in visible) {
            val row = layoutInflater.inflate(R.layout.item_task, container, false)
            row.findViewById<TextView>(R.id.tvTaskName).text = task.name ?: "Untitled task"
            row.findViewById<TextView>(R.id.tvTaskProject).text =
                task.projectName?.takeIf { it.isNotBlank() } ?: task.workCategory ?: "Project"
            val progress = (task.progress ?: 0).coerceIn(0, 100)
            row.findViewById<TextView>(R.id.tvTaskProgress).text = "$progress%"

            // Size the progress fill bar after the track lays out, since
            // we need the track's measured width to compute the fill width.
            val progressFill = row.findViewById<View>(R.id.progressFill)
            val progressTrack = row.findViewById<View>(R.id.progressTrack)
            progressTrack.post {
                val totalWidth = progressTrack.width
                val lp = progressFill.layoutParams
                lp.width = (totalWidth * progress / 100f).toInt()
                progressFill.layoutParams = lp
            }

            val dueText = task.endDate?.let {
                runCatching { parseFmt.parse(it) }.getOrNull()?.let { d -> dateFmt.format(d) }
            } ?: task.endDate ?: "-"
            row.findViewById<TextView>(R.id.tvTaskDueDate).text = dueText

            // Status pill — colour matches Figma:
            //   in-progress → blue (#175CD3 on #EFF8FF)
            //   completed   → green (#16A34A on #ECFDF3)
            //   delayed     → red   (#B42318 on #FEF3F2)
            //   pending/other → amber (#B54708 on #FFFAEB)
            val statusPill = row.findViewById<TextView>(R.id.tvTaskStatus)
            val (statusLabel, statusBg, statusText) = when (task.status) {
                "completed" -> Triple("Completed", "#ECFDF3", "#16A34A")
                "in-progress" -> Triple("In Progress", "#EFF8FF", "#175CD3")
                "delayed" -> Triple("Delayed", "#FEF3F2", "#B42318")
                else -> Triple(
                    task.status?.replaceFirstChar { it.uppercase() } ?: "Pending",
                    "#FFFAEB",
                    "#B54708",
                )
            }
            statusPill.text = statusLabel
            statusPill.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(statusBg))
            statusPill.setTextColor(Color.parseColor(statusText))

            val priority = row.findViewById<TextView>(R.id.tvTaskPriority)
            priority.text = task.priority?.replaceFirstChar { it.uppercase() } ?: "Medium"
            val priorityColor = when (task.priority) {
                "high" -> "#F04438"
                "low" -> "#16A34A"
                else -> "#F97316" // Medium/Orange
            }
            priority.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(priorityColor))

            row.setOnClickListener { openDetail(task) }
            container.addView(row)
        }
    }

    private fun openDetail(task: TaskData) {
        val fragment = TaskDetailFragment.newInstance(task.id)
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }
}
