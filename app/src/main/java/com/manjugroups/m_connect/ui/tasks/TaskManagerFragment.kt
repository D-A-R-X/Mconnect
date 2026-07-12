package com.manjugroups.m_connect.ui.tasks

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.network.UpdateDailyTaskStatusRequest
import com.manjugroups.m_connect.ui.common.HorizontalTabLayout
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Mobile Task Manager — mirrors the web `app/task-manager/page.tsx` (info
 * cards, status tabs, module tabs). The task list is a RecyclerView so a
 * 500-row queue recycles rows instead of inflating them all at once; the
 * expensive per-task module derivation is cached, and status counts are
 * computed in a single pass, so tab switches never re-scan or re-inflate.
 */
class TaskManagerFragment : Fragment() {

    private enum class Status { ALL, PENDING, OVERDUE, IN_PROGRESS, COMPLETED, CANCELLED }

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var status: Status = Status.ALL
    private var moduleFilter: String = "All"
    private var moduleTabsList: List<String> = listOf("All")
    private var allTasks: List<DailyTaskData> = emptyList()
    // Cache the module label per task id — moduleOf() parses the actionUrl,
    // so re-deriving it on every filter switch / bind was a real cost.
    private val moduleCache = HashMap<String, String>()
    private var teamIds: Set<String> = emptySet()
    private var scope: String? = null
    private var hasLoadedOnce = false
    private var todayStr = ""

    private var recycler: RecyclerView? = null
    private val adapter = TaskAdapter()
    private var skeleton: View? = null
    private var emptyState: View? = null
    private var statusTabs: HorizontalTabLayout? = null
    private var moduleTabs: HorizontalTabLayout? = null

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

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)

        recycler = view.findViewById<RecyclerView>(R.id.taskManagerRecycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@TaskManagerFragment.adapter
            setHasFixedSize(true)
            itemAnimator = null // no cross-fade churn on filter switches
        }
        skeleton = view.findViewById(R.id.taskManagerSkeleton)
        emptyState = view.findViewById(R.id.taskManagerEmpty)
        statusTabs = view.findViewById(R.id.taskStatusTabs)
        moduleTabs = view.findViewById(R.id.taskModuleTabs)

        view.findViewById<View>(R.id.btnTaskManagerBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val statusOrder = Status.values()
        statusTabs?.setOnTabSelectedListener(object : HorizontalTabLayout.OnTabSelectedListener {
            override fun onTabSelected(index: Int) {
                status = statusOrder.getOrElse(index) { Status.ALL }
                render()
            }
        })
        moduleTabs?.setOnTabSelectedListener(object : HorizontalTabLayout.OnTabSelectedListener {
            override fun onTabSelected(index: Int) {
                moduleFilter = moduleTabsList.getOrElse(index) { "All" }
                render()
            }
        })
        statusTabs?.setTabs(
            listOf("All", "Pending", "Overdue", "In progress", "Completed", "Cancelled")
                .map { HorizontalTabLayout.Tab(it) },
            defaultSelection = 0,
        )

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
        if (hasLoadedOnce) loadTasks(showSkeleton = false)
    }

    override fun onDestroyView() {
        skeleton?.let { SkeletonUtils.stopSkeletonPulse(it) }
        recycler?.adapter = null
        super.onDestroyView()
    }

    private fun loadTasks(showSkeleton: Boolean) {
        if (showSkeleton && allTasks.isEmpty()) {
            skeleton?.visibility = View.VISIBLE
            skeleton?.let { SkeletonUtils.startSkeletonPulse(it) }
            recycler?.visibility = View.GONE
            emptyState?.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val refresh = view?.findViewById<
                androidx.swiperefreshlayout.widget.SwipeRefreshLayout
            >(R.id.taskManagerRefresh)
            try {
                todayStr = apiDateFmt.format(System.currentTimeMillis())
                val resp = api.getTaskManagerTasks(session.bearerToken, todayStr)
                allTasks = resp.tasks.sortedByDescending { it.creationTime ?: 0.0 }
                teamIds = resp.teamIds.toSet()
                scope = resp.scope
                // Rebuild the module cache once per load.
                moduleCache.clear()
                for (t in allTasks) moduleCache[t.id] = computeModule(t)

                renderStats()
                renderModuleTabs()
                render()
                recycler?.visibility = View.VISIBLE
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

    // ── Info cards + status counts (single pass) ──
    private fun renderStats() {
        val v = view ?: return
        val me = session.staffId
        var my = 0; var team = 0; var assigned = 0; var ext = 0; var overdue = 0
        // status counts, indexed by Status.ordinal
        val sc = IntArray(Status.values().size)
        for (t in allTasks) {
            if (me != null && t.assignedTo == me) my++
            if (scope == "all" || (t.assignedTo != null && teamIds.contains(t.assignedTo))) team++
            if (me != null && t.assignedBy == me) assigned++
            if (t.pendingExtensionRequest == true) ext++
            val od = isOverdue(t)
            if (od) overdue++

            sc[Status.ALL.ordinal]++
            when (t.status) {
                "pending" -> sc[Status.PENDING.ordinal]++
                "in-progress" -> sc[Status.IN_PROGRESS.ordinal]++
                "completed" -> sc[Status.COMPLETED.ordinal]++
                "cancelled" -> sc[Status.CANCELLED.ordinal]++
            }
            if (od) sc[Status.OVERDUE.ordinal]++
        }
        v.findViewById<TextView>(R.id.tvCardMyCount).text = my.toString()
        v.findViewById<TextView>(R.id.tvCardTeamCount).text = team.toString()
        v.findViewById<TextView>(R.id.tvCardAssignedCount).text = assigned.toString()
        v.findViewById<TextView>(R.id.tvCardExtCount).text = ext.toString()
        v.findViewById<TextView>(R.id.tvCardOverdueCount).text = overdue.toString()

        statusTabs?.setTabs(
            listOf(
                HorizontalTabLayout.Tab("All", sc[Status.ALL.ordinal]),
                HorizontalTabLayout.Tab("Pending", sc[Status.PENDING.ordinal]),
                HorizontalTabLayout.Tab("Overdue", sc[Status.OVERDUE.ordinal]),
                HorizontalTabLayout.Tab("In progress", sc[Status.IN_PROGRESS.ordinal]),
                HorizontalTabLayout.Tab("Completed", sc[Status.COMPLETED.ordinal]),
                HorizontalTabLayout.Tab("Cancelled", sc[Status.CANCELLED.ordinal]),
            ),
            defaultSelection = Status.values().indexOf(status).coerceAtLeast(0),
        )
    }

    private fun renderModuleTabs() {
        val tabs = moduleTabs ?: return
        val modules = allTasks.map { moduleOf(it) }.distinct().sorted()
        moduleTabsList = listOf("All") + modules
        if (moduleFilter !in moduleTabsList) moduleFilter = "All"
        tabs.setTabs(
            moduleTabsList.map { HorizontalTabLayout.Tab(it) },
            defaultSelection = moduleTabsList.indexOf(moduleFilter).coerceAtLeast(0),
        )
    }

    private fun visibleTasks(): List<DailyTaskData> =
        allTasks.filter { matchesStatus(it, status) }
            .filter { moduleFilter == "All" || moduleOf(it) == moduleFilter }

    private fun matchesStatus(t: DailyTaskData, s: Status): Boolean = when (s) {
        Status.ALL -> true
        Status.PENDING -> t.status == "pending"
        Status.OVERDUE -> isOverdue(t)
        Status.IN_PROGRESS -> t.status == "in-progress"
        Status.COMPLETED -> t.status == "completed"
        Status.CANCELLED -> t.status == "cancelled"
    }

    /** Mirrors the web isOverdueTask: open + deadline past, or >24h since creation. */
    private fun isOverdue(t: DailyTaskData): Boolean {
        if (t.status == "completed" || t.status == "cancelled") return false
        val deadline = t.deadline?.trim().orEmpty()
        if (deadline.isNotEmpty()) return deadline < todayStr
        val created = t.creationTime ?: return false
        return System.currentTimeMillis() - created >= 24L * 60 * 60 * 1000
    }

    /** Filter + push to the adapter — no re-inflation, RecyclerView recycles. */
    private fun render() {
        val list = visibleTasks()
        adapter.submit(list)
        emptyState?.visibility = if (list.isEmpty() && hasLoadedOnce) View.VISIBLE else View.GONE
    }

    private fun bindRow(h: TaskAdapter.VH, task: DailyTaskData) {
        h.title.text = task.title?.takeIf { it.isNotBlank() } ?: task.taskName ?: "Task"

        val subtitle = task.taskName?.takeIf { it.isNotBlank() && it != task.title }
        if (subtitle != null) {
            h.name.text = subtitle
            h.name.visibility = View.VISIBLE
        } else h.name.visibility = View.GONE

        h.deadline.text = formatDeadline(task.deadline)
        h.assignedTo.text = task.assignedToName?.takeIf { it.isNotBlank() } ?: "—"

        applyStatusPill(h.statusPill, task)

        val badge = moduleOf(task).takeIf { it.isNotBlank() && it != "Task Manager" }
            ?: task.label?.takeIf { it.isNotBlank() }
            ?: task.taskCategory?.takeIf { it.isNotBlank() }
        if (badge != null) {
            h.categoryLabel.text = badge
            h.categoryLabel.visibility = View.VISIBLE
        } else h.categoryLabel.visibility = View.GONE

        val isOpen = task.status == "pending" || task.status == "in-progress"
        if (task.sourceReferenceType == "out_of_station_handoff" && isOpen) {
            h.actionRow.visibility = View.VISIBLE
            h.btnComplete.setOnClickListener { updateStatus(task.id, "completed") }
            h.btnCancel.setOnClickListener { updateStatus(task.id, "cancelled") }
        } else h.actionRow.visibility = View.GONE

        // Tap → the task's mobile screen, or a "open in web" dialog for web-only.
        h.itemView.setOnClickListener { TaskNavRouter.open(requireActivity(), task) }
    }

    private fun applyStatusPill(pill: TextView, task: DailyTaskData) {
        val (label, bg, fg) = when {
            isOverdue(task) -> Triple("Overdue", "#FEF3F2", "#B42318")
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
                    // force — the banner must reflect the completion now, not
                    // whenever its background throttle window reopens.
                    (activity as? com.manjugroups.m_connect.MainActivity)
                        ?.refreshTasksBanner(force = true)
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
        return runCatching { apiDateFmt.parse(value) }.getOrNull()
            ?.let { labelDateFmt.format(it) } ?: value
    }

    private fun moduleOf(task: DailyTaskData): String =
        moduleCache[task.id] ?: computeModule(task).also { moduleCache[task.id] = it }

    // ── Module derivation — ported from web features/task-manager/utils.ts ──
    private fun computeModule(task: DailyTaskData): String {
        if (!task.module.isNullOrBlank()) return task.module!!
        val path = task.actionUrl?.trim()?.let {
            runCatching { java.net.URI(it).path ?: it }.getOrDefault(it)
        }?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        val source = task.sourceReferenceType?.lowercase().orEmpty()

        return when {
            path.startsWith("/telecaller") -> "Telecaller"
            path.startsWith("/marketing/fleet") || path.startsWith("/fleet") -> "Fleets"
            path.startsWith("/marketing") -> "Marketing"
            path.startsWith("/post-sales") -> "Post Sales"
            path.startsWith("/projects") || path.startsWith("/tasks") || path.startsWith("/issues") ||
                path.startsWith("/library") || path.startsWith("/workforce") || path.startsWith("/procurement") ||
                path.startsWith("/materials") || path.startsWith("/purchase-orders") || path.startsWith("/vendors") ||
                path.startsWith("/equipment") || path.startsWith("/assets") || path.startsWith("/manpower") ||
                path.startsWith("/geotrack") -> "Project Management"
            path.startsWith("/land-procurement") -> "Land Procurement"
            path.startsWith("/hr") || path.startsWith("/attendance") || path.startsWith("/my-team") ||
                path.startsWith("/payroll") -> "HR"
            path.startsWith("/accounts") -> "Accounts"
            path.startsWith("/finance") || path.startsWith("/financials") -> "Finance"
            path.startsWith("/complaints") -> "CRM"
            path.startsWith("/settings") -> "Settings"
            path.startsWith("/task-manager") -> "Task Manager"
            source in setOf("staff-attendance", "leave", "permission", "work-from-home", "loan",
                "hiring-request", "onboarding", "staff-asset", "shift_update") -> "HR"
            source.startsWith("loan_") -> "Post Sales"
            source.contains("booking") -> "Marketing"
            source.contains("site_visit") || source == "out_of_station_handoff" ||
                source == "client_place_visit" -> "Marketing"
            source.contains("project") || source.contains("boq") || source == "work-order" ||
                source == "issue" -> "Project Management"
            else -> "Task Manager"
        }
    }

    private inner class TaskAdapter : RecyclerView.Adapter<TaskAdapter.VH>() {
        private var items: List<DailyTaskData> = emptyList()

        fun submit(list: List<DailyTaskData>) {
            // Diff instead of wholesale notifyDataSetChanged so the silent
            // re-entry refreshes don't rebind (and visibly churn) every row.
            val old = items
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = list.size
                    override fun areItemsTheSame(o: Int, n: Int) =
                        old[o].id == list[n].id

                    override fun areContentsTheSame(o: Int, n: Int): Boolean {
                        val a = old[o]
                        val b = list[n]
                        return a.status == b.status && a.deadline == b.deadline &&
                            a.title == b.title && a.taskName == b.taskName &&
                            a.assignedToName == b.assignedToName
                    }
                }
            )
            items = list
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTaskTitle)
            val name: TextView = view.findViewById(R.id.tvTaskName)
            val deadline: TextView = view.findViewById(R.id.tvTaskDeadline)
            val assignedTo: TextView = view.findViewById(R.id.tvTaskAssignedTo)
            val statusPill: TextView = view.findViewById(R.id.tvTaskStatusPill)
            val categoryLabel: TextView = view.findViewById(R.id.tvTaskCategoryLabel)
            val actionRow: View = view.findViewById(R.id.rowTaskHandoffActions)
            val btnComplete: View = view.findViewById(R.id.btnTaskHandoffComplete)
            val btnCancel: View = view.findViewById(R.id.btnTaskHandoffCancel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_daily_task, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) =
            bindRow(holder, items[position])
    }

    companion object {
        fun newInstance(): TaskManagerFragment = TaskManagerFragment()
    }
}
