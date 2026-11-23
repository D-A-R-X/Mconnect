package com.manjugroups.m_connect.ui.marketing

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyTaskData
import com.manjugroups.m_connect.network.UpdateDailyTaskStatusRequest
import com.manjugroups.m_connect.network.userMessage
import com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet
import com.manjugroups.m_connect.ui.common.ButtonLoader
import com.manjugroups.m_connect.ui.common.Floater
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.showOnce
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SV approvals: out-of-station, immediate-pickup and direct site visits a
 * telecaller (or the CP form) handed to a GM. Each is that GM's task
 * (sourceReferenceType out_of_station_handoff). Approve completes it, which
 * creates the site visit on the server; Reject cancels it.
 *
 * Super admins get tabs: Mine = assigned to me; Team = my team's GMs (task list
 * teamIds); All = every open one (scope "all"). Everyone else gets one list of
 * their own + their team's approvals with no tab row. The server lets the GM, their
 * supervisor, the requester or a super admin change the task; Approve is
 * narrower — only the assigned GM, an admin or a holder of
 * `marketing.siteVisits.confirm` (see [SvApprovalAccess]). Anyone else sees
 * whose turn it is.
 */
class SvApprovalQueueFragment : Fragment() {

    private enum class Tab(val label: String) { MINE("Mine"), TEAM("Team"), ALL("All") }

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private lateinit var list: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private val tabChips = mutableMapOf<Tab, TextView>()
    private var queue: Queue? = null
    private var tab = Tab.MINE
    private var skeleton: ObjectAnimator? = null
    private var submitting = false
    /** Reject button per rendered task, so the reason sheet's result can show its loader. */
    private val rejectButtons = mutableMapOf<String, View>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun font(res: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(requireContext(), res) }.getOrNull()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        session = SessionManager(requireContext())
        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(R.attr.colorSurfaceSecondary))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        page.addView(topBar())

        tabsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            visibility = View.GONE
        }
        page.addView(HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        })

        list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        refresh = SwipeRefreshLayout(requireContext()).apply {
            addView(ScrollView(requireContext()).apply {
                isFillViewport = true
                addView(list)
            })
            setOnRefreshListener { load(showSkeleton = false) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }
        page.addView(refresh)
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.setFragmentResultListener(REJECT_RESULT_KEY, viewLifecycleOwner) { _, b ->
            onRejectReason(
                b.getString(RejectWithReasonBottomSheet.KEY_ITEM_ID).orEmpty(),
                b.getString(RejectWithReasonBottomSheet.KEY_REASON).orEmpty(),
            )
        }
        load(showSkeleton = true)
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

    override fun onDestroyView() {
        skeleton?.cancel()
        skeleton = null
        tabChips.clear()
        rejectButtons.clear()
        super.onDestroyView()
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
            imageTintList = ColorStateList.valueOf(Color.parseColor("#0B61CA"))
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

    // ── Loading ──

    private fun load(showSkeleton: Boolean) {
        if (showSkeleton) {
            skeleton?.cancel()
            skeleton = SkeletonLoader.show(list, count = 3)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { loadQueue(api, session) }
            if (!isAdded) return@launch
            skeleton?.cancel()
            skeleton = null
            list.alpha = 1f
            refresh.isRefreshing = false
            result.onSuccess { q ->
                val first = queue == null
                queue = q
                if (first) tab = when {
                    q.mine.isNotEmpty() -> Tab.MINE
                    q.showTeam && q.team.isNotEmpty() -> Tab.TEAM
                    q.showAll && q.all.isNotEmpty() -> Tab.ALL
                    else -> Tab.MINE
                }
                renderTabs()
                render()
            }.onFailure {
                list.removeAllViews()
                list.addView(message("Couldn't load", it.userMessage(), retry = true))
            }
        }
    }

    private fun itemsFor(t: Tab): List<DailyTaskData> = queue?.let {
        when (t) {
            Tab.MINE -> it.mine
            Tab.TEAM -> it.team
            Tab.ALL -> it.all
        }
    }.orEmpty()

    private fun renderTabs() {
        val q = queue ?: return
        val tabs = listOfNotNull(Tab.MINE, Tab.TEAM.takeIf { q.showTeam }, Tab.ALL.takeIf { q.showAll })
        tabsRow.removeAllViews()
        tabChips.clear()
        tabsRow.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        if (tabs.size <= 1) {
            tab = Tab.MINE
            return
        }
        if (tab !in tabs) tab = Tab.MINE
        tabs.forEach { t ->
            val chip = text("${t.label}  ${itemsFor(t).size}", 13f, "#344054", R.font.inter_semibold).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    if (tab == t) return@setOnClickListener
                    tab = t
                    styleTabs()
                    render()
                }
            }
            tabChips[t] = chip
            tabsRow.addView(chip)
        }
        styleTabs()
    }

    private fun styleTabs() {
        tabChips.forEach { (t, chip) ->
            val active = t == tab
            chip.background = rounded(if (active) "#0B61CA" else "#FFFFFF", if (active) "#0B61CA" else "#E4E7EC", 20)
            chip.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#344054"))
        }
    }

    private fun render() {
        list.removeAllViews()
        rejectButtons.clear()
        val items = itemsFor(tab)
        if (items.isEmpty()) {
            list.addView(message("No site visits to approve", null, retry = false))
            return
        }
        items.forEach { list.addView(card(it)) }
    }

    private fun message(title: String, detail: String?, retry: Boolean): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_check_circle)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#98A2B3"))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            })
            addView(text(title, 15f, "#344054", R.font.inter_semibold).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })
            if (!detail.isNullOrBlank()) {
                addView(text(detail, 13f, "#667085", R.font.inter_regular).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
            }
            if (retry) {
                addView(text("Try again", 14f, "#0B61CA", R.font.inter_semibold).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(8))
                    isClickable = true
                    setOnClickListener { load(showSkeleton = true) }
                })
            }
        }

    // ── Card ──

    private fun card(task: DailyTaskData): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded("#FFFFFF", "#EAECF0", 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(clientName(task), 16f, "#101828", R.font.inter_semibold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        })
        val (chipFg, chipBg) = routeColors(task)
        header.addView(text(routeLabel(task), 11f, chipFg, R.font.inter_semibold).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(chipBg, chipBg, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) }
        })
        card.addView(header)

        card.addView(line(R.drawable.ic_calendar_days, visitDate(task.deadline) ?: "No date", "#101828"))
        val requester = listOfNotNull(task.assignedByName, task.assignedByDesignation)
            .joinToString(" · ").ifBlank { null }
        if (requester != null) card.addView(line(R.drawable.ic_user_outline, requester, "#344054"))
        // Name the GM whenever it isn't me (Team / All tabs, or the single team list).
        if (task.assignedTo != session.staffId && !task.assignedToName.isNullOrBlank()) {
            card.addView(line(R.drawable.ic_profile_office, "GM ${task.assignedToName}", "#344054"))
        }
        task.description?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
            card.addView(text(notes, 13f, "#475467", R.font.inter_regular).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded("#F9FAFB", "#F9FAFB", 10)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            })
        }

        val canReject = canDecide(task)
        val canApprove = canReject && canApprove(task)
        if (!canApprove) {
            card.addView(text("Waiting for ${task.assignedToName ?: "the GM"} to approve", 13f, "#475467", R.font.inter_medium).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded("#F2F4F7", "#F2F4F7", 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) }
            })
        }
        if (canReject) {
            val actions = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            val reject = button("Reject", "#B42318", "#FFFFFF", "#FDA29B") { confirmReject(task) }
            rejectButtons[task.id] = reject
            actions.addView(reject)
            if (canApprove) {
                (reject.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
                lateinit var approve: TextView
                approve = button("Approve", "#FFFFFF", "#169B2F", "#169B2F") { decide(task, "completed", approve) }
                actions.addView(approve)
            }
            card.addView(actions)
        }
        return card
    }

    /** Same rule as the server (canChangeDailyTaskStatus for handoff tasks). Gates Reject. */
    private fun canDecide(task: DailyTaskData): Boolean =
        SvApprovalAccess.canChangeTask(
            me = session.staffId,
            assignedTo = task.assignedTo,
            supervisorId = task.supervisorId,
            assignedBy = task.assignedBy,
            listScope = queue?.scope,
        )

    /** Narrower server rule for Approve (dailyTasks.updateStatus → handoff completion). */
    private fun canApprove(task: DailyTaskData): Boolean =
        SvApprovalAccess.canApprove(
            me = session.staffId,
            assignedTo = task.assignedTo,
            isAdmin = session.isAdmin,
            role = session.role,
            permissions = session.iamPermissions,
        )

    private fun line(icon: Int, value: String, color: String): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(ImageView(requireContext()).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#98A2B3"))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) }
        })
        addView(text(value, 13f, color, R.font.inter_regular).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    /**
     * Same as the web handoff dialog: a rejection needs a reason. Uses the
     * shared reject-with-reason sheet (leaves, permissions, attendance); its
     * result comes back through [REJECT_RESULT_KEY] in onViewCreated.
     */
    private fun confirmReject(task: DailyTaskData) {
        if (submitting) return
        RejectWithReasonBottomSheet.newInstance(
            itemId = task.id,
            resultKey = REJECT_RESULT_KEY,
            title = "Reject site visit",
            subtitle = "Add a reason for rejecting ${clientName(task)}'s site visit",
            buttonText = "Reject",
        ).showOnce(childFragmentManager, "reject_sv_approval")
    }

    private fun onRejectReason(taskId: String, reason: String) {
        val task = queue?.let { (it.mine + it.team + it.all).firstOrNull { t -> t.id == taskId } } ?: return
        val button = rejectButtons[taskId] ?: return
        decide(task, "cancelled", button, reason)
    }

    private fun decide(task: DailyTaskData, status: String, button: View, reason: String? = null) {
        if (submitting) return
        val rejectReason = RejectWithReasonBottomSheet.requiredReason(reason)
        if (status == "cancelled" && rejectReason == null) {
            Floater.error("Please share a reason for the rejection")
            return
        }
        submitting = true
        ButtonLoader.start(button, if (status == "completed") "Approving" else "Rejecting")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                api.updateDailyTaskStatus(
                    session.bearerToken,
                    UpdateDailyTaskStatusRequest(
                        id = task.id,
                        status = status,
                        reason = if (status == "cancelled") rejectReason else null,
                    ),
                )
            }
            submitting = false
            ButtonLoader.stop(button)
            if (!isAdded) return@launch
            val resp = result.getOrNull()
            if (resp?.success == true) {
                Floater.success(if (status == "completed") "Site visit approved" else "Site visit rejected")
                markDecided(task.id)
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf("changed" to true))
                queue = queue?.without(task.id)
                renderTabs()
                render()
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

    /** Text and fill per route. */
    private fun routeColors(task: DailyTaskData): Pair<String, String> = when (task.label) {
        "immediate_pickup" -> "#B42318" to "#FEF3F2"
        "direct_sv" -> "#175CD3" to "#EFF8FF"
        else -> "#B54708" to "#FFFAEB"
    }

    private fun visitDate(ymd: String?): String? = ymd?.let {
        runCatching {
            SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
                .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.take(10))!!)
        }.getOrDefault(it)
    }

    private fun text(value: String, size: Float, color: String, fontRes: Int) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        includeFontPadding = false
        typeface = font(fontRes) ?: typeface
    }

    /** Approve / Reject: the Mconnect button component. */
    private fun button(label: String, fg: String, bg: String, stroke: String, onClick: () -> Unit): TextView =
        com.manjugroups.m_connect.ui.common.MconnectButtons.create(
            requireContext(),
            if (bg.equals("#169B2F", ignoreCase = true)) com.manjugroups.m_connect.ui.common.MconnectButtons.Kind.SUCCESS
            else com.manjugroups.m_connect.ui.common.MconnectButtons.Kind.DANGER_OUTLINE,
            label,
        ) { onClick() }.apply {
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

    /** Open SV approvals by tab; showTeam / showAll follow the task list scope. */
    data class Queue(
        val mine: List<DailyTaskData>,
        val team: List<DailyTaskData>,
        val all: List<DailyTaskData>,
        val scope: String?,
        val showTeam: Boolean,
        val showAll: Boolean,
    ) {
        fun without(id: String) = copy(
            mine = mine.filterNot { it.id == id },
            team = team.filterNot { it.id == id },
            all = all.filterNot { it.id == id },
        )
    }

    companion object {
        const val RESULT_KEY = "sv_approval_changed"
        private const val REJECT_RESULT_KEY = "sv_approval_reject_reason"

        fun newInstance(): SvApprovalQueueFragment = SvApprovalQueueFragment()

        /**
         * The queue is filtered on the phone out of the task-manager list, which
         * defaults to 200 rows (newest first, system-wide for admins / team view).
         * On a busy day older pending approvals fell outside that window and
         * vanished from the queue and the badge. 500 is the route's maximum.
         */
        internal const val SV_APPROVAL_TASK_LIMIT = 500

        // The task list is cached for 60 s (server read cache + Cache-Control
        // max-age=60 in OkHttp), so a reload right after Approve / Reject can
        // still list the decided visit. Hide decided ones for a few minutes so
        // the GM never sees it come back and taps it twice.
        private const val DECIDED_HIDE_MS = 5 * 60_000L
        private val decidedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun markDecided(id: String) {
            decidedAt[id] = System.currentTimeMillis()
        }

        private fun isRecentlyDecided(id: String): Boolean {
            val now = System.currentTimeMillis()
            decidedAt.entries.removeIf { now - it.value >= DECIDED_HIDE_MS }
            return decidedAt.containsKey(id)
        }

        private fun isOpenHandoff(task: DailyTaskData) =
            task.sourceReferenceType == "out_of_station_handoff" &&
                (task.status == "pending" || task.status == "in-progress") &&
                !isRecentlyDecided(task.id)

        /**
         * Super admins: Mine / Team / All tabs. Everyone else: one list of their
         * own + their team's SV approvals and no tab row (user rule, 17 Nov).
         * Earliest visit first.
         */
        suspend fun loadQueue(api: ApiService, session: SessionManager): Queue {
            val me = session.staffId.orEmpty()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val resp = api.getTaskManagerTasks(session.bearerToken, today, limit = SV_APPROVAL_TASK_LIMIT)
            val open = resp.tasks.filter(::isOpenHandoff).sortedBy { it.deadline ?: "" }
            val teamIds = resp.teamIds.toSet()
            val superAdmin = resp.scope == "all" ||
                (session.role ?: "").trim().equals("super-admin", ignoreCase = true)
            val mine = open.filter { it.assignedTo == me }
            val team = open.filter { it.assignedTo != me && it.assignedTo in teamIds }
            if (!superAdmin) {
                // Single list, no tabs: rendered through the MINE slot.
                return Queue(
                    mine = (mine + team).sortedBy { it.deadline ?: "" },
                    team = emptyList(),
                    all = emptyList(),
                    scope = resp.scope,
                    showTeam = false,
                    showAll = false,
                )
            }
            return Queue(
                mine = mine,
                team = team,
                all = if (resp.scope == "all") open else emptyList(),
                scope = resp.scope,
                showTeam = true,
                showAll = resp.scope == "all",
            )
        }

        /** Open SV approvals assigned to the signed-in GM (Apps tab badge). */
        suspend fun loadPending(api: ApiService, session: SessionManager): List<DailyTaskData> {
            val me = session.staffId ?: return emptyList()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val resp = api.getTaskManagerTasks(session.bearerToken, today, limit = SV_APPROVAL_TASK_LIMIT)
            return resp.tasks
                .filter { isOpenHandoff(it) && it.assignedTo == me }
                .sortedBy { it.deadline ?: "" }
        }
    }
}

/** Who may act on an SV-approval (out_of_station_handoff) task, mirroring the server. */
internal object SvApprovalAccess {

    const val CONFIRM_PERMISSION = "marketing.siteVisits.confirm"

    /**
     * Server `canChangeDailyTaskStatus` for handoff tasks: the GM it is
     * assigned to, their supervisor, the requester, or an "all"-scope viewer.
     * This is what Reject needs.
     */
    fun canChangeTask(
        me: String?,
        assignedTo: String?,
        supervisorId: String?,
        assignedBy: String?,
        listScope: String?,
    ): Boolean {
        if (me.isNullOrBlank()) return false
        return assignedTo == me || supervisorId == me || assignedBy == me || listScope == "all"
    }

    /**
     * Approving mints the site visit, and the server (dailyTasks.updateStatus)
     * allows that only for the handoff's assigned GM (the task's assignee), an
     * admin (staff.isAdmin or super-admin role), or a holder of
     * [CONFIRM_PERMISSION]. A supervisor or requester who passes
     * [canChangeTask] still gets "FORBIDDEN: only the assigned GM can approve".
     */
    fun canApprove(
        me: String?,
        assignedTo: String?,
        isAdmin: Boolean,
        role: String?,
        permissions: Set<String>,
    ): Boolean {
        if (me.isNullOrBlank()) return false
        if (assignedTo == me) return true
        if (isAdmin) return true
        if (role?.trim().equals("super-admin", ignoreCase = true)) return true
        return CONFIRM_PERMISSION in permissions
    }
}
