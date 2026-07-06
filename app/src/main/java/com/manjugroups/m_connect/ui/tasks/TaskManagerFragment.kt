package com.manjugroups.m_connect.ui.tasks

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.network.UpdateDailyTaskStatusRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Mobile Task Manager — the daily-task queue that mirrors the web
 * `app/task-manager/page.tsx`. Backed by `GET /api/dailyTasks/listForTaskManager`
 * (own tasks always; team pending/in-progress for managers; everything for
 * super-admins) and the status filter pills defined in
 * [R.layout.fragment_task_manager].
 *
 * Handoff-source rows expose Complete / Cancel actions that POST to
 * `/api/dailyTasks/updateStatus`, matching the web dropdown fan-out.
 */
class TaskManagerFragment : Fragment() {

    private enum class Filter { ALL, PENDING, IN_PROGRESS, COMPLETED }

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var filter: Filter = Filter.ALL
    private var allTasks: List<DailyTaskData> = emptyList()
    private var hasLoadedOnce = false

    private var listContainer: LinearLayout? = null
    private var skeleton: View? = null
    private var emptyState: View? = null
    private var scopeChip: TextView? = null

    private val apiDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val labelDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_task_manager, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // The layout carries its own in-content header, so push the whole
        // screen below the OS status bar (the top bar is transparent/full-bleed).
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)

        listContainer = view.findViewById(R.id.taskManagerList)
        skeleton = view.findViewById(R.id.taskManagerSkeleton)
        emptyState = view.findViewById(R.id.taskManagerEmpty)
        scopeChip = view.findViewById(R.id.tvTaskManagerScopeChip)

        view.findViewById<View>(R.id.btnTaskManagerBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        wirePill(view.findViewById(R.id.pillTaskAll), Filter.ALL)
        wirePill(view.findViewById(R.id.pillTaskPending), Filter.PENDING)
        wirePill(view.findViewById(R.id.pillTaskInProgress), Filter.IN_PROGRESS)
        wirePill(view.findViewById(R.id.pillTaskCompleted), Filter.COMPLETED)

        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.taskManagerRefresh
        ).setupPullToRefresh { loadTasks(showSkeleton = false) }

        loadTasks(showSkeleton = true)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            main.setTopBarAppearance(Color.TRANSPARENT, darkStatusIcons = !isNight, fullBleed = true)
        }
        // Refresh after returning (a status change elsewhere may have landed).
        if (hasLoadedOnce) loadTasks(showSkeleton = false)
    }

    override fun onDestroyView() {
        skeleton?.let { SkeletonUtils.stopSkeletonPulse(it) }
        super.onDestroyView()
    }

    private fun wirePill(pill: TextView, target: Filter) {
        pill.setOnClickListener {
            if (filter != target) {
                filter = target
                applyPillStyles()
                render()
            }
        }
    }

    private fun applyPillStyles() {
        val view = view ?: return
        listOf(
            view.findViewById<TextView>(R.id.pillTaskAll) to (filter == Filter.ALL),
            view.findViewById<TextView>(R.id.pillTaskPending) to (filter == Filter.PENDING),
            view.findViewById<TextView>(R.id.pillTaskInProgress) to (filter == Filter.IN_PROGRESS),
            view.findViewById<TextView>(R.id.pillTaskCompleted) to (filter == Filter.COMPLETED),
        ).forEach { (pill, selected) ->
            if (selected) {
                pill.setBackgroundResource(R.drawable.bg_chip_active)
                pill.setTextColor(themeColor(R.attr.colorForegroundInverse))
            } else {
                pill.setBackgroundResource(R.drawable.bg_chip_inactive)
                pill.setTextColor(themeColor(R.attr.colorForegroundSecondary))
            }
        }
    }

    private fun loadTasks(showSkeleton: Boolean) {
        if (showSkeleton && allTasks.isEmpty()) {
            skeleton?.visibility = View.VISIBLE
            skeleton?.let { SkeletonUtils.startSkeletonPulse(it) }
            listContainer?.removeAllViews()
            emptyState?.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val refresh = view?.findViewById<
                androidx.swiperefreshlayout.widget.SwipeRefreshLayout
            >(R.id.taskManagerRefresh)
            try {
                val today = apiDateFmt.format(System.currentTimeMillis())
                val resp = api.getTaskManagerTasks(session.bearerToken, today)
                allTasks = resp.tasks
                renderScope(resp.scope)
                render()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (allTasks.isEmpty()) {
                    Toast.makeText(requireContext(), "Couldn't load tasks", Toast.LENGTH_SHORT).show()
                    emptyState?.visibility = View.VISIBLE
                }
            } finally {
                hasLoadedOnce = true
                skeleton?.let { SkeletonUtils.stopSkeletonPulse(it) }
                skeleton?.visibility = View.GONE
                refresh?.isRefreshing = false
            }
        }
    }

    private fun renderScope(scope: String?) {
        val chip = scopeChip ?: return
        val label = when (scope) {
            "own" -> "My tasks"
            "team" -> "Team"
            "all" -> "All tasks"
            else -> null
        }
        if (label == null) {
            chip.visibility = View.GONE
        } else {
            chip.text = label
            chip.visibility = View.VISIBLE
        }
    }

    private fun visibleTasks(): List<DailyTaskData> = when (filter) {
        Filter.ALL -> allTasks
        Filter.PENDING -> allTasks.filter { it.status == "pending" }
        Filter.IN_PROGRESS -> allTasks.filter { it.status == "in-progress" }
        Filter.COMPLETED -> allTasks.filter { it.status == "completed" }
    }

    private fun render() {
        val container = listContainer ?: return
        container.removeAllViews()
        val tasks = visibleTasks()
        emptyState?.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        if (tasks.isEmpty()) return

        val today = apiDateFmt.format(System.currentTimeMillis())
        for (task in tasks) {
            container.addView(buildRow(task, today, container))
        }
    }

    private fun buildRow(task: DailyTaskData, today: String, parent: ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_daily_task, parent, false)

        row.findViewById<TextView>(R.id.tvTaskTitle).text =
            task.title?.takeIf { it.isNotBlank() } ?: task.taskName ?: "Task"

        val nameView = row.findViewById<TextView>(R.id.tvTaskName)
        val subtitle = task.taskName?.takeIf { it.isNotBlank() && it != task.title }
        if (subtitle != null) {
            nameView.text = subtitle
            nameView.visibility = View.VISIBLE
        } else {
            nameView.visibility = View.GONE
        }

        row.findViewById<TextView>(R.id.tvTaskDeadline).text = formatDeadline(task.deadline)
        row.findViewById<TextView>(R.id.tvTaskAssignedTo).text =
            task.assignedToName?.takeIf { it.isNotBlank() } ?: "—"

        applyStatusPill(row.findViewById(R.id.tvTaskStatusPill), task, today)

        // Category badge — prefer the human-friendly label ("Visitor Details")
        // over the raw category slug ("frontdesk"), matching the web row.
        val categoryLabel = row.findViewById<TextView>(R.id.tvTaskCategoryLabel)
        val category = task.label?.takeIf { it.isNotBlank() } ?: task.taskCategory?.takeIf { it.isNotBlank() }
        if (category != null) {
            categoryLabel.text = category
            categoryLabel.visibility = View.VISIBLE
        } else {
            categoryLabel.visibility = View.GONE
        }

        // Handoff Complete / Cancel actions — only for out-of-station handoff
        // tasks, and only while still open. Mirrors the web dropdown.
        val actionRow = row.findViewById<View>(R.id.rowTaskHandoffActions)
        val isOpen = task.status == "pending" || task.status == "in-progress"
        if (task.sourceReferenceType == "out_of_station_handoff" && isOpen) {
            actionRow.visibility = View.VISIBLE
            row.findViewById<View>(R.id.btnTaskHandoffComplete).setOnClickListener {
                updateStatus(task.id, "completed")
            }
            row.findViewById<View>(R.id.btnTaskHandoffCancel).setOnClickListener {
                updateStatus(task.id, "cancelled")
            }
        } else {
            actionRow.visibility = View.GONE
        }

        return row
    }

    private fun applyStatusPill(pill: TextView, task: DailyTaskData, today: String) {
        val isOpen = task.status == "pending" || task.status == "in-progress"
        val deadline = task.deadline?.trim().orEmpty()
        val isOverdue = isOpen && deadline.isNotEmpty() && deadline < today

        val (label, bg, fg) = when {
            isOverdue -> Triple("Overdue", "#FEF3F2", "#B42318")
            task.status == "completed" -> Triple("Completed", "#ECFDF3", "#16A34A")
            task.status == "in-progress" -> Triple("In Progress", "#EFF8FF", "#175CD3")
            task.status == "cancelled" -> Triple("Cancelled", "#FEF3F2", "#B42318")
            else -> Triple("Pending", "#FFFAEB", "#B54708")
        }
        pill.text = label
        pill.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
        pill.setTextColor(Color.parseColor(fg))
    }

    private fun updateStatus(id: String, status: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateDailyTaskStatus(
                    session.bearerToken,
                    UpdateDailyTaskStatusRequest(id = id, status = status)
                )
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        if (status == "completed") "Task completed" else "Task cancelled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadTasks(showSkeleton = false)
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Update failed", Toast.LENGTH_SHORT).show()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatDeadline(raw: String?): String {
        val value = raw?.takeIf { it.isNotBlank() } ?: return "—"
        // Deadlines are stored as yyyy-MM-dd; fall back to the raw string.
        return runCatching { apiDateFmt.parse(value) }
            .getOrNull()
            ?.let { labelDateFmt.format(it) }
            ?: value
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        fun newInstance(): TaskManagerFragment = TaskManagerFragment()
    }
}
