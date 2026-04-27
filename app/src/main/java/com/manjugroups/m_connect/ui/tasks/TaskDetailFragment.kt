package com.manjugroups.m_connect.ui.tasks

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.TaskData
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Task detail screen — fetches `/api/projects/tasks/get` and renders.
 * Tap "Update Task" to open [TaskUpdateBottomSheet] which posts changes
 * to /api/projects/tasks/update.
 */
class TaskDetailFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var taskId: String? = null
    private var currentTask: TaskData? = null
    private var skeletonAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_task_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        taskId = arguments?.getString(ARG_TASK_ID)

        view.findViewById<View>(R.id.btnDetailBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnUpdateTask).setOnClickListener {
            openUpdateSheet()
        }

        setFragmentResultListener(TaskUpdateBottomSheet.RESULT_KEY) { _, bundle ->
            if (bundle.getBoolean(TaskUpdateBottomSheet.KEY_UPDATED)) {
                Toast.makeText(requireContext(), "Task updated", Toast.LENGTH_SHORT).show()
                taskId?.let { loadDetail(view, it) }
            }
        }

        val id = taskId
        if (id.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing task id", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }
        loadDetail(view, id)
    }

    override fun onResume() {
        super.onResume()
        // White system-bar background to match the white in-fragment header.
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
    }

    override fun onPause() {
        // Restore the default light background expected by sibling tabs.
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    override fun onDestroyView() {
        stopSkeleton()
        super.onDestroyView()
    }

    private fun loadDetail(root: View, id: String) {
        val skeleton = root.findViewById<View>(R.id.detailSkeletonContainer)
        val scroll = root.findViewById<View>(R.id.detailScroll)
        val updateBtn = root.findViewById<View>(R.id.btnUpdateTask)
        startSkeleton(skeleton)
        scroll.visibility = View.GONE
        updateBtn.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getTaskDetail(session.bearerToken, id)
                val task = resp.task
                if (!resp.success || task == null) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load task",
                        Toast.LENGTH_LONG
                    ).show()
                    parentFragmentManager.popBackStack()
                    return@launch
                }
                currentTask = task
                bind(root, task)
                stopSkeleton()
                skeleton.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                updateBtn.visibility = if (canUpdate(task)) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun canUpdate(task: TaskData): Boolean {
        // Backend already checks staffAssignedTo == auth.user._id; this is just
        // a UI gate so non-assigned viewers don't see the button.
        val mine = task.staffAssignedTo
        val me = session.staffId
        return !mine.isNullOrBlank() && !me.isNullOrBlank() && mine == me
    }

    private fun bind(root: View, task: TaskData) {
        root.findViewById<TextView>(R.id.tvDetailName).text = task.name ?: "Untitled task"
        root.findViewById<TextView>(R.id.tvDetailProject).text =
            buildString {
                append(task.projectName?.takeIf { it.isNotBlank() } ?: "Project")
                if (!task.taskId.isNullOrBlank()) append("  •  ").append(task.taskId)
            }
        root.findViewById<TextView>(R.id.tvDetailDescription).text =
            task.description?.takeIf { it.isNotBlank() } ?: "No description provided."
        root.findViewById<TextView>(R.id.tvDetailProgress).text = "${task.progress ?: 0}%"

        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        fun fmt(d: String?): String =
            d?.let { runCatching { parseFmt.parse(it) }.getOrNull()?.let(outFmt::format) ?: it } ?: "-"

        root.findViewById<TextView>(R.id.tvDetailStartDate).text = fmt(task.startDate)
        root.findViewById<TextView>(R.id.tvDetailEndDate).text = fmt(task.endDate)

        val statusPill = root.findViewById<TextView>(R.id.tvDetailStatus)
        when (task.status) {
            "in-progress" -> {
                statusPill.text = "In Progress"
                statusPill.setBackgroundResource(R.drawable.bg_task_priority_medium)
                statusPill.setTextColor(Color.parseColor("#B54708"))
            }
            "completed" -> {
                statusPill.text = "Completed"
                statusPill.setBackgroundResource(R.drawable.bg_task_priority_low)
                statusPill.setTextColor(Color.parseColor("#067647"))
            }
            "delayed" -> {
                statusPill.text = "Delayed"
                statusPill.setBackgroundResource(R.drawable.bg_task_priority_high)
                statusPill.setTextColor(Color.parseColor("#B42318"))
            }
            else -> {
                statusPill.text = "Not Started"
                statusPill.setBackgroundResource(R.drawable.bg_task_inner_card)
                statusPill.setTextColor(Color.parseColor("#475467"))
            }
        }

        // Notes rows — only show those with content.
        bindNoteRow(
            root, R.id.rowTodaysUpdate, R.id.tvDetailTodaysUpdate, task.todaysUpdate
        )
        bindNoteRow(root, R.id.rowBlocker, R.id.tvDetailBlocker, task.blocker)
        bindNoteRow(
            root, R.id.rowTomorrowsPlan, R.id.tvDetailTomorrowsPlan, task.tomorrowsPlan
        )
    }

    private fun bindNoteRow(root: View, rowId: Int, textId: Int, value: String?) {
        val row = root.findViewById<View>(rowId)
        val text = root.findViewById<TextView>(textId)
        if (value.isNullOrBlank()) {
            row.visibility = View.GONE
        } else {
            row.visibility = View.VISIBLE
            text.text = value
        }
    }

    private fun openUpdateSheet() {
        val task = currentTask ?: return
        TaskUpdateBottomSheet.newInstance(
            taskId = task.id,
            currentStatus = task.status,
            currentProgress = task.progress,
            currentTodaysUpdate = task.todaysUpdate,
            currentBlocker = task.blocker,
            currentTomorrowsPlan = task.tomorrowsPlan
        ).show(parentFragmentManager, "task_update")
    }

    private fun startSkeleton(target: View) {
        target.visibility = View.VISIBLE
        if (skeletonAnimator?.isRunning != true) {
            skeletonAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 0.55f, 1f).apply {
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
    }

    companion object {
        private const val ARG_TASK_ID = "arg_task_id"

        fun newInstance(taskId: String): TaskDetailFragment = TaskDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_TASK_ID, taskId) }
        }
    }
}
