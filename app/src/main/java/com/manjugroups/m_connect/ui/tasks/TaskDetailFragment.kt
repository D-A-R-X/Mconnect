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
        // Clock-history icon in the top-right of the header → opens the
        // Time Line sheet with every daily update on this task.
        view.findViewById<View>(R.id.btnDetailTimeline).setOnClickListener {
            val id = taskId ?: return@setOnClickListener
            TaskTimelineBottomSheet.newInstance(id)
                .show(parentFragmentManager, "task_timeline")
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
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        // Restore the default light background expected by sibling tabs.
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    override fun onDestroyView() {
        com.manjugroups.m_connect.ui.common.SkeletonUtils.stopAll()
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
                stopSkeleton(skeleton)
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
        // Task description + expand/collapse "View more" toggle.
        // The TextView is capped at 3 lines by the layout; if the text
        // actually overflows that cap, the View more button becomes
        // visible and toggles between collapsed (3 lines + "View more")
        // and expanded (no cap + "View less").
        val descView = root.findViewById<TextView>(R.id.tvDetailDescription)
        val viewMoreBtn = root.findViewById<TextView>(R.id.btnDetailViewMore)
        descView.text = task.description?.takeIf { it.isNotBlank() }
            ?: "No description provided."
        descView.maxLines = 3
        viewMoreBtn.visibility = View.GONE
        viewMoreBtn.text = "View more ⌵"
        // Once the TextView has laid out we can check lineCount; if it
        // hit the cap and there's clipped content, surface the toggle.
        descView.post {
            val truncated = descView.layout?.let { layout ->
                layout.lineCount >= 3 && layout.getEllipsisCount(2) > 0
            } ?: false
            if (truncated) {
                viewMoreBtn.visibility = View.VISIBLE
                var expanded = false
                viewMoreBtn.setOnClickListener {
                    expanded = !expanded
                    if (expanded) {
                        descView.maxLines = Int.MAX_VALUE
                        descView.ellipsize = null
                        viewMoreBtn.text = "View less ⌃"
                    } else {
                        descView.maxLines = 3
                        descView.ellipsize = android.text.TextUtils.TruncateAt.END
                        viewMoreBtn.text = "View more ⌵"
                    }
                }
            }
        }
        val progress = (task.progress ?: 0).coerceIn(0, 100)
        root.findViewById<TextView>(R.id.tvDetailProgress).text = "$progress%"

        // Size the progress fill bar (overlay on top of the track) so the
        // big horizontal progress strip actually shows a filled portion.
        val fill = root.findViewById<View>(R.id.detailProgressFill)
        val track = root.findViewById<View>(R.id.detailProgressTrack)
        track.post {
            val lp = fill.layoutParams
            lp.width = (track.width * progress / 100f).toInt()
            fill.layoutParams = lp
        }

        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        fun fmt(d: String?): String =
            d?.let { runCatching { parseFmt.parse(it) }.getOrNull()?.let(outFmt::format) ?: it } ?: "-"

        root.findViewById<TextView>(R.id.tvDetailStartDate).text = fmt(task.startDate)
        root.findViewById<TextView>(R.id.tvDetailEndDate).text = fmt(task.endDate)

        // Status label — the redesigned layout uses tvDetailStatusLabel
        // as a plain inline label (no pill background). Just set the text;
        // background/colour styling lives in the XML now.
        val statusLabel = root.findViewById<TextView>(R.id.tvDetailStatusLabel)
        statusLabel.text = when (task.status) {
            "in-progress" -> "In Progress"
            "completed" -> "Completed"
            "delayed" -> "Delayed"
            else -> "Not Started"
        }

        // ── Resource Summary cards ──────────────────────────────────────
        // Total Quantity + Unit come straight from the task row; the
        // per-resource counts (labour / equipment / materials) need a
        // second roundtrip to /api/projects/tasks/resources, fetched
        // below.
        val unitLabel = task.unit?.takeIf { it.isNotBlank() } ?: "-"
        root.findViewById<TextView>(R.id.tvDetailTotalQty).text =
            task.totalQuantity?.let { "${trimDouble(it)} ${task.unit ?: ""}".trim() } ?: "-"
        root.findViewById<TextView>(R.id.tvDetailUnit).text = unitLabel
        // Est. Cost isn't on TaskData (lives on the schema as estimatedCost
        // but the Retrofit model doesn't expose it yet) — show a dash
        // until the backend ships it on the mobile DTO.
        root.findViewById<TextView>(R.id.tvDetailEstCost).text = "-"
        // Seed resource fields with placeholders; will fill in below.
        val tvLabour = root.findViewById<TextView>(R.id.tvDetailLabourCount)
        val tvEquipment = root.findViewById<TextView>(R.id.tvDetailEquipmentQty)
        val tvMaterials = root.findViewById<TextView>(R.id.tvDetailMaterialsQty)
        tvLabour.text = "-"
        tvEquipment.text = "-"
        tvMaterials.text = "-"

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getTaskResources(session.bearerToken, task.id)
            }.getOrNull()?.takeIf { it.success }?.let { resp ->
                val labourCount = resp.resources.count { it.resourceType == "labour" }
                val equipmentQty = resp.resources
                    .filter { it.resourceType == "equipment" }
                    .sumOf { it.budgetQty ?: 0.0 }
                val materialsQty = resp.resources
                    .filter { it.resourceType == "material" }
                    .sumOf { it.budgetQty ?: 0.0 }
                tvLabour.text = labourCount.toString()
                tvEquipment.text = trimDouble(equipmentQty)
                tvMaterials.text = if (materialsQty > 0)
                    "${trimDouble(materialsQty)} ${task.unit ?: ""}".trim()
                else "-"
            }
        }

        // Notes rows (Today's Update / Blocker / Tomorrow's Plan) were
        // removed from the layout in the redesign — the per-day notes
        // now live on the Time Line screen, not on the static detail view.
    }

    /** Trims trailing ".0" so 120.00 → "120", but keeps "120.5". */
    private fun trimDouble(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            "%.2f".format(value)
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
        com.manjugroups.m_connect.ui.common.SkeletonUtils.startSkeletonPulse(target)
    }

    private fun stopSkeleton(target: View) {
        com.manjugroups.m_connect.ui.common.SkeletonUtils.stopSkeletonPulse(target)
    }

    companion object {
        private const val ARG_TASK_ID = "arg_task_id"

        fun newInstance(taskId: String): TaskDetailFragment = TaskDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_TASK_ID, taskId) }
        }
    }
}
