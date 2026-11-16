package com.manjugroups.m_connect.ui.marketing

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.network.UpdateDailyTaskStatusRequest
import com.manjugroups.m_connect.network.userMessage
import com.manjugroups.m_connect.ui.common.ButtonLoader
import com.manjugroups.m_connect.ui.common.Floater
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * GM queue for site visits waiting on their approval: out-of-station,
 * immediate-pickup and direct SVs a telecaller (or the CP form) handed to the
 * GM. Each is the GM's "SV approval" task (sourceReferenceType
 * out_of_station_handoff, assigned to them). Approve completes that task,
 * which on the server creates the real site visit (the same path as the web
 * Fixed tab and the Task Manager); Reject cancels it, closing the handoff.
 */
class SvApprovalQueueFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private var submitting = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun font(res: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(requireContext(), res) }.getOrNull()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        session = SessionManager(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }
        root.addView(text("Site visits to approve", 18f, "#101828", R.font.inter_semibold))
        root.addView(text(
            "Out-of-station, immediate-pickup and direct site visits waiting on you. " +
                "Approve creates the site visit; reject sends it back.",
            13f, "#667085", R.font.inter_regular,
        ).apply { setPadding(0, dp(4), 0, dp(16)) })
        progress = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        }
        emptyView = text("Nothing to approve right now.", 14f, "#667085", R.font.inter_regular).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }
        container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(progress)
        root.addView(emptyView)
        root.addView(container)

        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.colorSurfaceSecondary))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        page.addView(topBar())
        page.addView(ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(root)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        })
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let {
            it.setTopBarAppearance(Color.WHITE, true)
            it.setTabBarVisible(false)
        }
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun topBar(): View {
        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            setBackgroundColor(themeColor(R.attr.colorSurfacePrimary))
        }
        bar.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_arrow_left)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0B61CA"))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setPadding(dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { navigateUp() }
        })
        bar.addView(text("SV Approvals", 17f, "#101828", R.font.inter_semibold).apply {
            setPadding(dp(4), 0, 0, 0)
        })
        return bar
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        container.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { loadPending(api, session) }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            result.onSuccess { items ->
                if (items.isEmpty()) showMessage("Nothing to approve right now.", retry = false)
                else items.forEach { container.addView(card(it)) }
            }.onFailure {
                showMessage("Couldn't load SV approvals. ${it.userMessage()} Tap to retry.", retry = true)
            }
        }
    }

    private fun showMessage(msg: String, retry: Boolean) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        emptyView.isClickable = retry
        emptyView.setOnClickListener(if (retry) View.OnClickListener { load() } else null)
    }

    private fun card(task: DailyTaskData): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
            background = rounded("#FFFFFF", "#EAECF0", 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(clientName(task), 16f, "#101828", R.font.inter_semibold).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        })
        header.addView(text(routeLabel(task), 12f, "#B54708", R.font.inter_medium).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded("#FFFAEB", "#FEDF89", 20)
        })
        card.addView(header)

        fun fact(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            card.addView(text(label, 11f, "#667085", R.font.inter_medium).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(text(value, 13f, "#101828", R.font.inter_regular).apply { setPadding(0, dp(2), 0, 0) })
        }
        fact("Visit date", visitDate(task.deadline))
        fact("Requested by", listOfNotNull(task.assignedByName, task.assignedByDesignation).joinToString(" · ").ifBlank { null })
        fact("Requested on", task.creationTime?.let { stamp(it.toLong()) })
        fact("Notes", task.description)

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        lateinit var reject: TextView
        reject = button("Reject", "#B42318", "#FEE4E2") { confirmReject(task, reject) }
            .also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) }
        lateinit var approve: TextView
        approve = button("Approve", "#FFFFFF", "#169B2F") { decide(task, "completed", approve) }
        actions.addView(reject)
        actions.addView(approve)
        card.addView(actions)
        return card
    }

    private fun confirmReject(task: DailyTaskData, button: View) {
        AlertDialog.Builder(requireContext())
            .setTitle("Reject this site visit?")
            .setMessage("The request from ${task.assignedByName ?: "the telecaller"} for ${clientName(task)} is closed. No site visit is created.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Reject") { _, _ -> decide(task, "cancelled", button) }
            .show()
    }

    private fun decide(task: DailyTaskData, status: String, button: View) {
        if (submitting) return
        submitting = true
        ButtonLoader.start(button, if (status == "completed") "Approving" else "Rejecting")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                api.updateDailyTaskStatus(session.bearerToken, UpdateDailyTaskStatusRequest(id = task.id, status = status))
            }
            submitting = false
            ButtonLoader.stop(button)
            if (!isAdded) return@launch
            val resp = result.getOrNull()
            if (resp?.success == true) {
                Floater.success(if (status == "completed") "Site visit approved and created" else "Site visit rejected")
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf("changed" to true))
                load()
            } else {
                Floater.error(
                    resp?.error?.takeIf { it.isNotBlank() }
                        ?: result.exceptionOrNull()?.userMessage()
                        ?: if (status == "completed") "Could not approve" else "Could not reject",
                )
            }
        }
    }

    // ── Formatting ──

    /** "Out-of-station SV: Ramesh" → "Ramesh". */
    private fun clientName(task: DailyTaskData): String =
        task.taskName?.substringAfter(": ", task.taskName)?.trim()?.ifBlank { null }
            ?: task.title ?: "Client"

    private fun routeLabel(task: DailyTaskData): String = when (task.label) {
        "immediate_pickup" -> "Immediate pickup"
        "direct_sv" -> "Direct SV"
        "out_of_station" -> "Out of station"
        else -> task.title?.removeSuffix(" Approval") ?: "Site visit"
    }

    private fun visitDate(ymd: String?): String? = ymd?.let {
        runCatching {
            SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
                .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.take(10))!!)
        }.getOrDefault(it)
    }

    private fun stamp(ms: Long) = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(ms))

    private fun text(value: String, size: Float, color: String, fontRes: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        includeFontPadding = false
        typeface = font(fontRes) ?: typeface
    }

    private fun button(label: String, fg: String, bg: String, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.parseColor(fg))
        includeFontPadding = false
        typeface = font(R.font.inter_semibold) ?: typeface
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = rounded(bg, bg, 12)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
    }

    private fun rounded(fill: String, stroke: String, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.parseColor(fill))
        setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) requireContext().getColor(tv.resourceId) else tv.data
    }

    companion object {
        const val RESULT_KEY = "sv_approval_changed"

        fun newInstance(): SvApprovalQueueFragment = SvApprovalQueueFragment()

        /** Open SV approvals assigned to the signed-in GM, oldest visit first. */
        suspend fun loadPending(api: ApiService, session: SessionManager): List<DailyTaskData> {
            val me = session.staffId ?: return emptyList()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val resp = api.getTaskManagerTasks(session.bearerToken, today)
            return resp.tasks
                .filter {
                    it.sourceReferenceType == "out_of_station_handoff" &&
                        it.assignedTo == me &&
                        (it.status == "pending" || it.status == "in-progress")
                }
                .sortedBy { it.deadline ?: "" }
        }
    }
}
