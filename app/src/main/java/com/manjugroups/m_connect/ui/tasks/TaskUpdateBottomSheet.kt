package com.manjugroups.m_connect.ui.tasks

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.UpdateTaskRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sheet for staff to update an assigned task — status, progress, today's
 * notes, blocker, tomorrow's plan. Posts to /api/projects/tasks/update
 * (same payload as the web edit form, scoped to fields a staff member can
 * touch on their own task).
 */
class TaskUpdateBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var taskId: String = ""
    private var status: String = "in-progress"
    private var progress: Int = 0

    private var statusViews: Map<String, TextView> = emptyMap()
    private var seekProgress: SeekBar? = null
    private var tvProgressValue: TextView? = null
    private var etTodaysUpdate: EditText? = null
    private var etBlocker: EditText? = null
    private var etTomorrowsPlan: EditText? = null
    private var errorText: TextView? = null
    private var submitBtn: View? = null
    private var cancelBtn: View? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_task_update, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val args = requireArguments()
        taskId = args.getString(ARG_TASK_ID).orEmpty()
        status = args.getString(ARG_STATUS).takeUnless { it.isNullOrBlank() } ?: "in-progress"
        progress = args.getInt(ARG_PROGRESS, 0).coerceIn(0, 100)

        statusViews = mapOf(
            "not-started" to view.findViewById(R.id.statusNotStarted),
            "in-progress" to view.findViewById(R.id.statusInProgress),
            "completed" to view.findViewById(R.id.statusCompleted),
            "delayed" to view.findViewById(R.id.statusDelayed),
        )
        seekProgress = view.findViewById(R.id.seekProgress)
        tvProgressValue = view.findViewById(R.id.tvProgressValue)
        etTodaysUpdate = view.findViewById(R.id.etTodaysUpdate)
        etBlocker = view.findViewById(R.id.etBlocker)
        etTomorrowsPlan = view.findViewById(R.id.etTomorrowsPlan)
        errorText = view.findViewById(R.id.tvUpdateError)
        submitBtn = view.findViewById(R.id.btnSubmitUpdate)
        cancelBtn = view.findViewById(R.id.btnCancelUpdate)

        etTodaysUpdate?.setText(args.getString(ARG_TODAYS_UPDATE).orEmpty())
        etBlocker?.setText(args.getString(ARG_BLOCKER).orEmpty())
        etTomorrowsPlan?.setText(args.getString(ARG_TOMORROWS_PLAN).orEmpty())

        statusViews.forEach { (key, tv) ->
            tv.setOnClickListener { setStatus(key) }
        }
        setStatus(status)

        seekProgress?.progress = progress
        tvProgressValue?.text = "$progress%"
        seekProgress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                progress = value
                tvProgressValue?.text = "$value%"
                if (fromUser) {
                    when {
                        value >= 100 -> setStatus("completed")
                        value > 0 && status == "not-started" -> setStatus("in-progress")
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cancelBtn?.setOnClickListener { dismissAllowingStateLoss() }
        submitBtn?.setOnClickListener { performUpdate() }
    }

    private fun setStatus(next: String) {
        status = next
        statusViews.forEach { (key, tv) ->
            val selected = key == next
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_task_status_pill_active
                else R.drawable.bg_task_inner_card
            )
            tv.setTextColor(
                if (selected) Color.WHITE else Color.parseColor("#475467")
            )
        }
        if (next == "completed" && (seekProgress?.progress ?: 0) < 100) {
            seekProgress?.progress = 100
        }
    }

    private fun performUpdate() {
        if (taskId.isBlank()) return
        submitBtn?.isEnabled = false
        errorText?.visibility = View.GONE

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val req = UpdateTaskRequest(
            id = taskId,
            status = status,
            progress = progress,
            actualStartDate = if (status == "in-progress" || status == "completed") today else null,
            actualEndDate = if (status == "completed") today else null,
            todaysUpdate = etTodaysUpdate?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            blocker = etBlocker?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            tomorrowsPlan = etTomorrowsPlan?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateTask(session.bearerToken, req)
                if (resp.success) {
                    setFragmentResult(RESULT_KEY, bundleOf(KEY_UPDATED to true))
                    dismissAllowingStateLoss()
                } else {
                    submitBtn?.isEnabled = true
                    showError(resp.error ?: "Failed to update task")
                }
            } catch (e: Exception) {
                submitBtn?.isEnabled = true
                showError("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun showError(message: String) {
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
    }

    companion object {
        const val RESULT_KEY = "task_update_result"
        const val KEY_UPDATED = "updated"
        private const val ARG_TASK_ID = "arg_task_id"
        private const val ARG_STATUS = "arg_status"
        private const val ARG_PROGRESS = "arg_progress"
        private const val ARG_TODAYS_UPDATE = "arg_todays_update"
        private const val ARG_BLOCKER = "arg_blocker"
        private const val ARG_TOMORROWS_PLAN = "arg_tomorrows_plan"

        fun newInstance(
            taskId: String,
            currentStatus: String?,
            currentProgress: Int?,
            currentTodaysUpdate: String?,
            currentBlocker: String?,
            currentTomorrowsPlan: String?,
        ): TaskUpdateBottomSheet = TaskUpdateBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TASK_ID, taskId)
                if (currentStatus != null) putString(ARG_STATUS, currentStatus)
                putInt(ARG_PROGRESS, currentProgress ?: 0)
                if (currentTodaysUpdate != null) putString(ARG_TODAYS_UPDATE, currentTodaysUpdate)
                if (currentBlocker != null) putString(ARG_BLOCKER, currentBlocker)
                if (currentTomorrowsPlan != null) putString(ARG_TOMORROWS_PLAN, currentTomorrowsPlan)
            }
        }
    }
}
