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
            parentFragmentManager.popBackStack()
        }

        taskListContainer = view.findViewById(R.id.taskList)
        skeletonContainer = view.findViewById(R.id.tasksSkeletonContainer)
        emptyState = view.findViewById(R.id.emptyState)
        tabAll = view.findViewById(R.id.tabAll)
        tabInProgress = view.findViewById(R.id.tabInProgress)
        tabCompleted = view.findViewById(R.id.tabCompleted)
        tvSummaryTotal = view.findViewById(R.id.tvSummaryTotal)
        tvSummaryInProgress = view.findViewById(R.id.tvSummaryInProgress)
        tvSummaryCompleted = view.findViewById(R.id.tvSummaryCompleted)
        tvOverallProgress = view.findViewById(R.id.tvOverallProgress)

        tabAll?.setOnClickListener { setFilter(Filter.ALL) }
        tabInProgress?.setOnClickListener { setFilter(Filter.IN_PROGRESS) }
        tabCompleted?.setOnClickListener { setFilter(Filter.COMPLETED) }
        applyTabStyles()

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
        startSkeleton()
        emptyState?.visibility = View.GONE
        taskListContainer?.removeAllViews()
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
            }
        }
    }

    private fun startSkeleton() {
        val sk = skeletonContainer ?: return
        sk.visibility = View.VISIBLE
        if (skeletonAnimator?.isRunning != true) {
            skeletonAnimator = ObjectAnimator.ofFloat(sk, View.ALPHA, 0.55f, 1f).apply {
                duration = 650L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopSkeleton() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        skeletonContainer?.alpha = 1f
        skeletonContainer?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
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
            row.findViewById<TextView>(R.id.tvTaskProgress).text = "${task.progress ?: 0}%"

            val dueText = task.endDate?.let {
                runCatching { parseFmt.parse(it) }.getOrNull()?.let { d -> dateFmt.format(d) }
            } ?: task.endDate ?: "-"
            row.findViewById<TextView>(R.id.tvTaskDueDate).text = "Due $dueText"

            val priority = row.findViewById<TextView>(R.id.tvTaskPriority)
            when (task.priority) {
                "high" -> {
                    priority.text = "High"
                    priority.setBackgroundResource(R.drawable.bg_task_priority_high)
                    priority.setTextColor(Color.parseColor("#B42318"))
                    priority.visibility = View.VISIBLE
                }
                "medium" -> {
                    priority.text = "Medium"
                    priority.setBackgroundResource(R.drawable.bg_task_priority_medium)
                    priority.setTextColor(Color.parseColor("#B54708"))
                    priority.visibility = View.VISIBLE
                }
                "low" -> {
                    priority.text = "Low"
                    priority.setBackgroundResource(R.drawable.bg_task_priority_low)
                    priority.setTextColor(Color.parseColor("#067647"))
                    priority.visibility = View.VISIBLE
                }
                else -> priority.visibility = View.GONE
            }

            row.setOnClickListener { openDetail(task) }
            container.addView(row)
        }
    }

    private fun openDetail(task: TaskData) {
        val fragment = TaskDetailFragment.newInstance(task.id)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }
}
