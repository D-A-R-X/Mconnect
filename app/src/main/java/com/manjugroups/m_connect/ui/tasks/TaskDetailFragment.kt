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
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

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
            navigateUp()
        }
        view.findViewById<View>(R.id.btnUpdateTask).setOnClickListener {
            openUpdateSheet()
        }
        // Clock-history icon in the top-right of the header → opens the
        // Time Line sheet with every daily update on this task.
        view.findViewById<View>(R.id.btnDetailTimeline).setOnClickListener {
            val id = taskId ?: return@setOnClickListener
            TaskTimelineBottomSheet.newInstance(id)
                .showOnce(parentFragmentManager, "task_timeline")
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
            navigateUp()
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
                    navigateUp()
                    return@launch
                }
                currentTask = task
                bind(root, task)
                stopSkeleton(skeleton)
                skeleton.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                updateBtn.visibility = if (canUpdate(task)) View.VISIBLE else View.GONE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Detached (Back pressed mid-load) → don't touch requireContext().
                context?.let { ctx ->
                    Toast.makeText(ctx, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                    navigateUp()
                }
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
        // Work-category card uses tvDetailProject as its value slot — show
        // workCategory if the task has one, else fall back to the project
        // name. Either way, append the human-readable TSK-id for context.
        root.findViewById<TextView>(R.id.tvDetailProject).text =
            buildString {
                val primary = task.workCategory?.takeIf { it.isNotBlank() }
                    ?: task.projectName?.takeIf { it.isNotBlank() }
                    ?: "Project"
                append(primary)
                if (!task.taskId.isNullOrBlank()) append("  •  ").append(task.taskId)
            }

        // Task description — show the full text. The previous 3-line cap
        // + "View more" toggle hid important task details (Section / Unit
        // / Qty / etc.) behind a button some users never tapped, and the
        // ellipsis-detection sometimes failed to surface the toggle at
        // all so the user could only ever see the first 3 lines. The
        // description card already lives inside the scrollable detail
        // view, so unbounded height is fine here.
        val descView = root.findViewById<TextView>(R.id.tvDetailDescription)
        val viewMoreBtn = root.findViewById<TextView>(R.id.btnDetailViewMore)
        descView.text = task.description?.takeIf { it.isNotBlank() }
            ?: "No description provided."
        descView.maxLines = Int.MAX_VALUE
        descView.ellipsize = null
        viewMoreBtn.visibility = View.GONE

        // Assigned-to pill. Layout reserves detailAttendeesContainer but
        // nothing was populating it, so the "Assigned To" column appeared
        // empty even when the task had an assignee. Render one small chip
        // with the assignee's initial avatar + name.
        renderAssignedTo(root, task)
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
        // Est. Cost — now exposed on TaskData. Show "₹ <amount>" when set,
        // dash when the task has no estimated cost yet.
        root.findViewById<TextView>(R.id.tvDetailEstCost).text =
            task.estimatedCost?.takeIf { it > 0 }?.let { "₹ ${formatRupees(it)}" } ?: "-"
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
                // Labour is a head-count, the others are quantities. Both
                // equipment and material qtys come from `actualQty` —
                // the web's task-resources panel sends user-entered Qty
                // into actualQty (budgetQty / plannedQty default to 0
                // there), so summing budgetQty as we used to always
                // returned 0 and the Materials Qty card showed a dash
                // even when web clearly had a 20-Boxes steel row.
                val labour = resp.resources.filter { it.resourceType == "labour" }
                val equipment = resp.resources.filter { it.resourceType == "equipment" }
                val materials = resp.resources.filter { it.resourceType == "material" }

                val equipmentQty = equipment.sumOf { it.actualQty ?: 0.0 }
                val materialsQty = materials.sumOf { it.actualQty ?: 0.0 }

                // Material / equipment can each have their own unit (e.g.
                // steel in "Boxes", cement in "kg"). Show the per-resource
                // unit when one is set and consistent across rows; fall
                // back to the task unit otherwise.
                val materialUnit = materials.mapNotNull {
                    it.unit?.takeIf { u -> u.isNotBlank() }
                }.distinct().singleOrNull()
                val equipmentUnit = equipment.mapNotNull {
                    it.unit?.takeIf { u -> u.isNotBlank() }
                }.distinct().singleOrNull()

                tvLabour.text = labour.size.toString()
                tvEquipment.text = if (equipmentQty > 0)
                    "${trimDouble(equipmentQty)} ${equipmentUnit ?: task.unit ?: ""}".trim()
                else equipment.size.toString()
                tvMaterials.text = if (materialsQty > 0)
                    "${trimDouble(materialsQty)} ${materialUnit ?: task.unit ?: ""}".trim()
                else if (materials.isNotEmpty()) materials.size.toString()
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

    /** Format as Indian-style number string for the Est. Cost card. */
    private fun formatRupees(value: Double): String {
        val nf = java.text.NumberFormat.getInstance(java.util.Locale("en", "IN"))
        nf.maximumFractionDigits = if (value == value.toLong().toDouble()) 0 else 2
        return nf.format(value)
    }

    /**
     * Populate the Assigned To column. Layout reserves
     * `detailAttendeesContainer` but nothing was filling it, so even
     * tasks with a clear assignee rendered as an empty column. Render
     * a small initial-avatar + name chip so the user can see at a
     * glance who owns the task.
     */
    private fun renderAssignedTo(root: View, task: TaskData) {
        val container = root.findViewById<android.widget.LinearLayout>(
            R.id.detailAttendeesContainer
        ) ?: return
        container.removeAllViews()
        val name = task.assignedToName?.trim().orEmpty()
        if (name.isBlank()) {
            val empty = TextView(container.context).apply {
                text = "Unassigned"
                setTextColor(Color.parseColor("#667085"))
                textSize = 13f
            }
            container.addView(empty)
            return
        }
        val avatar = TextView(container.context).apply {
            text = name.first().uppercaseChar().toString()
            // Same brand-blue text colour the Home avatar uses, since the
            // shared drawable is a light grey circle (not a coloured one).
            setTextColor(Color.parseColor("#0B61CA"))
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_home_new_avatar_circle)
            val sz = (24 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz)
        }
        val label = TextView(container.context).apply {
            text = name
            setTextColor(Color.parseColor("#101828"))
            textSize = 13f
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * resources.displayMetrics.density).toInt() }
            layoutParams = lp
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                container.context, R.font.inter_medium
            )
        }
        container.gravity = android.view.Gravity.CENTER_VERTICAL
        container.addView(avatar)
        container.addView(label)
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
        ).showOnce(parentFragmentManager, "task_update")
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
