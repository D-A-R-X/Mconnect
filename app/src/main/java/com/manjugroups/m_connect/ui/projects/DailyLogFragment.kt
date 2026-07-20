package com.manjugroups.m_connect.ui.projects

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import coil.load
import com.manjugroups.m_connect.BuildConfig
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentDailyLogBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyLogApi
import com.manjugroups.m_connect.network.DailyLogAttachment
import com.manjugroups.m_connect.network.DailyLogEntry
import com.manjugroups.m_connect.network.DprRecipient
import com.manjugroups.m_connect.network.DprReport
import com.manjugroups.m_connect.network.IdOnlyRequest
import com.manjugroups.m_connect.network.ProjectSummary
import com.manjugroups.m_connect.network.SendDprRequest
import com.manjugroups.m_connect.network.UpdateDprRecipientRequest
import com.manjugroups.m_connect.ui.common.IconPillView
import com.manjugroups.m_connect.ui.common.ImagePreviewDialog
import com.manjugroups.m_connect.ui.common.InfiniteScrollPager
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

/**
 * Daily Log — loans-style page. The lists aggregate the caller's entries /
 * DPR recipients across every project they can access; the "+" beside the
 * tabs opens the create sheet for the active tab (which carries its own
 * project selector). No form fields live on this page.
 */
class DailyLogFragment : Fragment() {

    private var _binding: FragmentDailyLogBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private val dprApi = DailyLogApi.create()
    private val session by lazy { SessionManager(requireContext()) }

    // Projects power the send-DPR picker.
    private var projects: List<ProjectSummary> = emptyList()
    private var projectsLoading = false
    private var onNewEntryTab = true

    // Skeleton pulse animators, cancelled once real data renders.
    private var logsSkeleton: android.animation.ObjectAnimator? = null
    private var dprSkeleton: android.animation.ObjectAnimator? = null

    // Infinite scroll for the New-Entry logs list: render 20 cards, extend by
    // 20 as the shared NestedScrollView nears its end. Reset when the source
    // log list is replaced (new fetch / aggregate) so the window starts over.
    private var logsSource: List<DailyLogEntry> = emptyList()
    private var logsFilteredCount: Int = 0
    private var logsWindowCtx: String? = null
    private val logsPager = InfiniteScrollPager(onLoadMore = { renderLogs(logsSource) })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDailyLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Loans pattern: the blue hero extends behind the status bar and its
        // content is pushed below the notch by padding the hero itself.
        val headerBasePadding = binding.dailyLogHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.dailyLogHeader.updatePadding(top = headerBasePadding + sys.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.btnDailyLogBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnDailyLogAdd.setOnClickListener { onAddClicked() }
        binding.tabNewEntry.setOnClickListener { showTab(true) }
        binding.tabDpr.setOnClickListener { showTab(false) }
        binding.btnSendDpr.setOnClickListener { sendDpr() }

        childFragmentManager.setFragmentResultListener(CreateDailyLogBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, _ -> loadLogs() }
        childFragmentManager.setFragmentResultListener(DprAddRecipientBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, _ -> loadDpr() }

        // Infinite scroll: grow the logs window as the shared page nears the
        // bottom. Bound once; totalCount reads the full filtered logs size.
        logsPager.bindNestedScroll(binding.dailyLogScroll, totalCount = { logsFilteredCount })

        showTab(true)
        loadProjects()
        loadLogs()
        loadDpr()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
        }
    }

    private fun showTab(newEntry: Boolean) {
        if (_binding == null) return
        onNewEntryTab = newEntry
        binding.newEntryContent.visibility = if (newEntry) View.VISIBLE else View.GONE
        binding.dprContent.visibility = if (newEntry) View.GONE else View.VISIBLE
        styleTab(binding.tabNewEntry, newEntry)
        styleTab(binding.tabDpr, !newEntry)
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        tab.setBackgroundResource(
            if (active) R.drawable.bg_loans_segment_active else android.R.color.transparent,
        )
        tab.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#475467"))
    }

    private fun onAddClicked() {
        if (onNewEntryTab) {
            CreateDailyLogBottomSheet.newInstance(null, null)
                .showOnce(childFragmentManager, "create_daily_log")
        } else {
            DprAddRecipientBottomSheet.newInstance()
                .showOnce(childFragmentManager, "dpr_add")
        }
    }

    /**
     * Run a /mine aggregate, falling back ONLY when the route genuinely isn't
     * on the server (404).
     *
     * This used to be a bare runCatching{}.getOrNull(), so ANY failure — a
     * dropped packet, a timeout — silently swapped to the client-side
     * per-project aggregation, which walks a different, 30-project-capped set.
     * The screen then showed a different subset of the same data with nothing
     * indicating why, which is a large part of why this page looked like it
     * "sometimes works".
     */
    private suspend fun <T> aggregateOrNull(call: suspend () -> T): T? =
        runCatching { call() }.fold(
            onSuccess = { it },
            onFailure = { err ->
                val missingRoute = (err as? retrofit2.HttpException)?.code() == 404
                if (!missingRoute) {
                    android.util.Log.w("DailyLog", "aggregate failed: ${err.message}")
                }
                null
            },
        )

    private fun loadProjects() {
        if (projectsLoading) return
        projectsLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            // Marketing/projects returns all projects the caller can access
            // (viewAll admins get everything) so the send-DPR + fallback pickers
            // aren't limited to the scoped /api/projects set.
            val resp = runCatching { api.getMarketingProjects(session.bearerToken) }.getOrNull()
            projectsLoading = false
            if (_binding == null) return@launch
            projects = resp?.projects
                ?.map { ProjectSummary(id = it.id, name = it.name, status = it.status) }
                ?: emptyList()
        }
    }

    // ── New Entry: my recent logs across projects ──

    private fun loadLogs() {
        // Skeleton only on a cold load (empty list); silent refresh otherwise.
        if (binding.logsContainer.childCount == 0) {
            binding.emptyLogs.visibility = View.GONE
            binding.tvEntriesTitle.visibility = View.VISIBLE
            logsSkeleton?.cancel()
            logsSkeleton = SkeletonLoader.show(binding.logsContainer, 3)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Prefer the one-shot aggregate route; if the backend predates it
            // (older prod returns 404 → null), fall back to aggregating the
            // still-deployed per-project route client-side so entries show
            // without waiting on a backend deploy.
            val mine = aggregateOrNull { dprApi.listMyDailyLogs(session.bearerToken) }
            val logs = mine?.logs ?: aggregateLogs()
            if (_binding == null) return@launch
            renderLogs(logs)
        }
    }

    /** Client-side "my logs" — one per-project call each, run in parallel. */
    private suspend fun aggregateLogs(): List<DailyLogEntry> = coroutineScope {
        val projs = ensureProjects().take(AGG_PROJECT_CAP)
        if (projs.isEmpty()) return@coroutineScope emptyList()
        projs.map { p ->
            async {
                runCatching { dprApi.listDailyLogs(session.bearerToken, p.id) }
                    .getOrNull()?.logs.orEmpty()
                    .map { it.copy(projectName = it.projectName ?: p.name) }
            }
        }.awaitAll().flatten()
            .sortedByDescending { it.date ?: "" }
            .take(100)
    }

    /** Load projects once (the picker pre-warm may already have them). */
    /**
     * Projects for the pickers AND for the client-side aggregate fallback.
     *
     * Uses /api/projects (projects.listAccessibleForStaff) — the same query
     * behind the /mine aggregates this screen reads back. /api/marketing/projects
     * routes through projects.listForUser instead, a broader set, so a picker
     * fed from it could offer a project whose entries and DPR recipients the
     * list would never return.
     */
    private suspend fun ensureProjects(): List<ProjectSummary> {
        if (projects.isNotEmpty()) return projects
        val resp = runCatching { api.getMyProjects(session.bearerToken) }.getOrNull()
        projects = resp?.projects ?: projects
        return projects
    }

    /** Which project cards are open. Survives re-render so a refresh doesn't collapse them. */
    private val expandedProjects = mutableSetOf<String>()

    /** A "Label: value" row for the expanded card, or null when there's nothing to show. */
    private fun detailRow(ctx: android.content.Context, label: String, value: String?): View? {
        val text = value?.trim()?.takeUnless { it.isEmpty() } ?: return null
        return TextView(ctx).apply {
            this.text = "$label: $text"
            textSize = 12.5f
            setTextColor(Color.parseColor("#475467"))
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun renderLogs(logs: List<DailyLogEntry>) {
        logsSkeleton?.cancel(); logsSkeleton = null
        // Window the full filtered list; empty-state + count stay keyed off the
        // FULL size, only the render is capped to the current window.
        logsSource = logs
        logsFilteredCount = logs.size
        // Reset the scroll window whenever the source list is replaced (new
        // fetch / aggregate); re-renders from an extend keep the same identity.
        val windowCtx = System.identityHashCode(logs).toString()
        if (windowCtx != logsWindowCtx) {
            logsWindowCtx = windowCtx
            logsPager.reset()
        }
        val c = binding.logsContainer
        c.alpha = 1f
        c.removeAllViews()
        binding.emptyLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEntriesTitle.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        // One card PER PROJECT, expanding to reveal that project's entries.
        // Projects are ordered by most-recent activity, entries newest-first
        // within each. The scroll window caps entries, and a project appears as
        // soon as any of its entries fall inside it.
        val windowed = logs
            .groupBy { it.projectName?.trim().takeUnless { n -> n.isNullOrBlank() } ?: "Other" }
            .toList()
            .sortedByDescending { (_, entries) -> entries.maxOfOrNull { it.date ?: "" } ?: "" }
            .flatMap { (name, entries) -> entries.sortedByDescending { it.date ?: "" }.map { name to it } }
            .take(logsPager.limit)

        // Already ordered by most-recent activity, so the first group is the
        // freshest — flagged so it can carry the "Recent" badge.
        windowed.groupBy({ it.first }, { it.second })
            .entries
            .forEachIndexed { index, (projectName, entries) ->
                c.addView(
                    projectCard(requireContext(), projectName, entries, isMostRecent = index == 0),
                )
            }
    }

    /**
     * A project's card: name + entry count, expanding to list its entries.
     *
     * Replaces the old flat list of one card per entry under a plain project
     * header — with several projects in play that read as an undifferentiated
     * stream, and there was no way to collapse a project you weren't reading.
     */
    private fun projectCard(
        ctx: android.content.Context,
        projectName: String,
        entries: List<DailyLogEntry>,
        isMostRecent: Boolean,
    ): View {
        val expanded = expandedProjects.contains(projectName)

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Name + count stacked, so the count reads as the card's headline
        // figure rather than a caption tucked against the chevron.
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(ctx).apply {
            text = projectName
            textSize = 17f
            setTextColor(Color.parseColor("#101828"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (isMostRecent) {
            nameRow.addView(TextView(ctx).apply {
                text = "Recent"
                textSize = 10.5f
                setTextColor(Color.parseColor("#067647"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_daily_log_recent_badge)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
        }
        titleBox.addView(nameRow)
        titleBox.addView(TextView(ctx).apply {
            text = if (entries.size == 1) "1 entry" else entries.size.toString() + " entries"
            textSize = 13f
            setTextColor(Color.parseColor("#0B61CA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        })
        // Latest activity on this project: the day the log is FOR, plus the
        // time it was actually filed (from _creationTime — `date` has no clock).
        lastActivityLabel(entries)?.let { label ->
            titleBox.addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(3), 0, 0)
            })
        }
        header.addView(titleBox)
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back_chevron)
            rotation = if (expanded) 90f else 270f
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#98A2B3"))
        }
        header.addView(chevron)
        content.addView(header)

        val entriesBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        entries.forEach { log ->
            // Each entry is its own tinted block inside the white card, so
            // multiple entries read as separate records rather than one wall
            // of text separated by hairlines.
            entriesBox.addView(entryRow(ctx, log))
        }
        content.addView(entriesBox)

        card.setOnClickListener {
            val open = !expandedProjects.contains(projectName)
            if (open) expandedProjects.add(projectName) else expandedProjects.remove(projectName)
            entriesBox.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 90f else 270f).setDuration(160).start()
        }

        card.addView(content)
        return card
    }

    private val logTimeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** "Last: 11 Jul 2026 - 4:32 PM", or null when there's nothing to date. */
    private fun lastActivityLabel(entries: List<DailyLogEntry>): String? {
        val latest = entries.maxByOrNull { it.date ?: "" } ?: return null
        val day = displayDate(latest.date).takeUnless { it.isBlank() } ?: return null
        val time = latest.creationTime?.let { ms ->
            runCatching { logTimeFormatter.format(java.util.Date(ms.toLong())) }.getOrNull()
        }
        return if (time != null) "Last: " + day + " - " + time else "Last: " + day
    }

    /** One entry inside a project card: date, summary, meta pills, attachments. */
    private fun entryRow(ctx: android.content.Context, log: DailyLogEntry): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_daily_log_entry_block)
            isClickable = true
            isFocusable = true
            setOnClickListener { openLogDetail(log) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherIcon(log.weather).takeIf { it != 0 }?.let { wIcon ->
            head.addView(ImageView(ctx).apply {
                setImageResource(wIcon)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                    .apply { marginEnd = dp(7) }
            })
        }
        head.addView(TextView(ctx).apply {
            text = displayDate(log.date)
            textSize = 14f
            setTextColor(Color.parseColor("#101828"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(head)

        row.addView(TextView(ctx).apply {
            text = log.workSummary?.trim().takeUnless { it.isNullOrBlank() } ?: "-"
            textSize = 13f
            setTextColor(Color.parseColor("#475467"))
            setPadding(0, dp(6), 0, 0)
        })

        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        var hasPill = false
        log.labourCount?.let {
            pills.addView(metaPill(ctx, R.drawable.ic_chat_users, it.toString())); hasPill = true
        }
        log.labourHours?.let {
            pills.addView(metaPill(ctx, R.drawable.ic_clock, trimNum(it) + " hrs")); hasPill = true
        }
        log.siteConditions?.takeIf { it.isNotBlank() }?.let {
            pills.addView(metaPill(ctx, 0, it.replaceFirstChar(Char::uppercase))); hasPill = true
        }
        val atts = mergedAttachments(ctx, log)
        if (atts.isNotEmpty()) {
            pills.addView(metaPill(ctx, R.drawable.ic_attach_image, atts.size.toString())); hasPill = true
        }
        if (hasPill) row.addView(pills)
        if (atts.isNotEmpty()) row.addView(buildAttachmentStrip(ctx, atts))

        detailRow(ctx, "Issues", log.issuesEncountered)?.let { row.addView(it) }
        detailRow(ctx, "Safety", log.safetyObservations)?.let { row.addView(it) }
        return row
    }

    private fun weatherIcon(w: String?): Int = when (w?.lowercase(Locale.US)) {
        "sunny" -> R.drawable.ic_weather_sunny
        "cloudy" -> R.drawable.ic_weather_cloudy
        "rainy" -> R.drawable.ic_weather_rainy
        "windy" -> R.drawable.ic_weather_windy
        "stormy" -> R.drawable.ic_weather_stormy
        else -> 0
    }

    /** A reusable metadata pill (leading icon + label). iconRes 0 = text-only. */
    private fun metaPill(ctx: android.content.Context, iconRes: Int, text: String): IconPillView =
        IconPillView(ctx).apply {
            bind(iconRes, text)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    /** Section header preceding a project's run of log cards. */
    private fun projectSectionHeader(ctx: android.content.Context, name: String): View =
        TextView(ctx).apply {
            text = name
            textSize = 13f
            setTextColor(Color.parseColor("#0B61CA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(16), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun projectChip(ctx: android.content.Context, name: String): TextView = TextView(ctx).apply {
        text = name
        textSize = 11f
        setTextColor(Color.parseColor("#0B61CA"))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(9), dp(4), dp(9), dp(5))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(Color.parseColor("#EAF2FE"))
        }
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ── DPR: recipients + history across projects ──

    private fun loadDpr() {
        if (binding.recipientsContainer.childCount == 0) {
            dprSkeleton?.cancel()
            dprSkeleton = SkeletonLoader.show(binding.recipientsContainer, 3)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Same graceful-degradation as loadLogs: aggregate route first,
            // else fan out over the deployed per-project recipient/report routes.
            val mine = aggregateOrNull { dprApi.getMyDpr(session.bearerToken) }
            val recipients: List<DprRecipient>
            val reports: List<DprReport>
            if (mine != null) {
                recipients = mine.recipients
                reports = mine.reports
            } else {
                val agg = aggregateDpr()
                recipients = agg.first
                reports = agg.second
            }
            if (_binding == null) return@launch
            renderRecipients(recipients)
            renderReports(reports)
        }
    }

    /** Client-side DPR aggregate over the deployed per-project routes. */
    private suspend fun aggregateDpr(): Pair<List<DprRecipient>, List<DprReport>> = coroutineScope {
        val projs = ensureProjects().take(AGG_PROJECT_CAP)
        if (projs.isEmpty()) return@coroutineScope emptyList<DprRecipient>() to emptyList()
        val recipJobs = projs.map { p ->
            async {
                runCatching { dprApi.listDprRecipients(session.bearerToken, p.id) }
                    .getOrNull()?.recipients.orEmpty()
                    .map { it.copy(projectName = it.projectName ?: p.name) }
            }
        }
        val reportJobs = projs.map { p ->
            async {
                runCatching { dprApi.listDprReports(session.bearerToken, p.id) }
                    .getOrNull()?.reports.orEmpty()
                    .map { it.copy(projectName = it.projectName ?: p.name) }
            }
        }
        val recips = recipJobs.awaitAll().flatten()
        val reps = reportJobs.awaitAll().flatten()
            .sortedByDescending { it.date ?: "" }
            .take(30)
        recips to reps
    }

    /** White rounded card matching the entry-page log cards, for consistent
     *  "neat card" handling across the New Entry and DPR tabs. */
    private fun neatCard(ctx: android.content.Context): com.google.android.material.card.MaterialCardView =
        com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeColor = Color.parseColor("#EAECF0")
            strokeWidth = dp(1)
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

    /** Round monogram avatar (first letter) for a recipient card. */
    private fun avatarCircle(ctx: android.content.Context, name: String): TextView = TextView(ctx).apply {
        text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Color.parseColor("#0B61CA"))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#EAF2FE"))
        }
    }

    /** Green (all-sent) / red (has failures) status pill for DPR history. */
    private fun dprStatusPill(ctx: android.content.Context, sent: Int, failed: Int): TextView =
        TextView(ctx).apply {
            val ok = failed <= 0
            text = "$sent sent · $failed failed"
            textSize = 11f
            setTextColor(Color.parseColor(if (ok) "#067647" else "#B42318"))
            setPadding(dp(9), dp(4), dp(9), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor(if (ok) "#ECFDF3" else "#FEF3F2"))
            }
        }

    private fun renderRecipients(list: List<DprRecipient>) {
        dprSkeleton?.cancel(); dprSkeleton = null
        val c = binding.recipientsContainer
        c.alpha = 1f
        c.removeAllViews()
        binding.tvRecipientsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // History is always visible — it's a record of what went out, and hiding
        // it whenever the recipient list happens to be empty made past sends
        // look like they never happened. Only the Send action is conditional,
        // since there's nobody to send to.
        binding.dprManageSection.visibility = View.VISIBLE
        binding.btnSendDpr.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.tvRecipientsCount.text = "${list.count { it.isActive }} active"
        list.forEach { r ->
            val ctx = requireContext()
            val card = neatCard(ctx)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(8), dp(12))
            }
            row.addView(avatarCircle(ctx, r.name ?: "?"))

            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(ctx).apply {
                text = r.name ?: "Recipient"
                textSize = 14f
                setTextColor(Color.parseColor("#101828"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val subRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            (r.normalizedPhone ?: r.phone)?.takeIf { it.isNotBlank() }?.let {
                subRow.addView(TextView(ctx).apply {
                    text = it; textSize = 12f; setTextColor(Color.parseColor("#667085"))
                })
            }
            r.projectName?.takeIf { it.isNotBlank() }?.let {
                subRow.addView(projectChip(ctx, it).apply {
                    (layoutParams as? LinearLayout.LayoutParams
                        ?: LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )).also { lp -> lp.marginStart = dp(6); layoutParams = lp }
                })
            }
            info.addView(subRow)
            row.addView(info)

            row.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = r.isActive
                setOnCheckedChangeListener { _, checked -> toggleRecipient(r.id, checked) }
            })
            row.addView(TextView(ctx).apply {
                text = "✕"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#B42318"))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#FEF3F2"))
                }
                setOnClickListener { removeRecipient(r.id) }
            })
            card.addView(row)
            c.addView(card)
        }
    }

    /** Which DPR history project cards are open. */
    private val expandedDprProjects = mutableSetOf<String>()

    /**
     * DPR history as one expandable card per project, mirroring Recent Entries.
     *
     * It used to be a flat run of date rows with the project name as a subtitle,
     * so several projects' sends interleaved into an undifferentiated list.
     */
    private fun renderReports(list: List<DprReport>) {
        val c = binding.historyContainer
        c.removeAllViews()
        binding.tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) return

        list
            .groupBy { it.projectName?.trim().takeUnless { n -> n.isNullOrBlank() } ?: "Other" }
            .toList()
            .sortedByDescending { (_, reports) -> reports.maxOfOrNull { it.date ?: "" } ?: "" }
            .forEachIndexed { index, (projectName, reports) ->
                c.addView(
                    dprProjectCard(
                        requireContext(),
                        projectName,
                        reports.sortedByDescending { it.date ?: "" },
                        isMostRecent = index == 0,
                    ),
                )
            }
    }

    private fun dprProjectCard(
        ctx: android.content.Context,
        projectName: String,
        reports: List<DprReport>,
        isMostRecent: Boolean,
    ): View {
        val expanded = expandedDprProjects.contains(projectName)

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(ctx).apply {
            text = projectName
            textSize = 17f
            setTextColor(Color.parseColor("#101828"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (isMostRecent) {
            nameRow.addView(TextView(ctx).apply {
                text = "Recent"
                textSize = 10.5f
                setTextColor(Color.parseColor("#067647"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_daily_log_recent_badge)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) }
            })
        }
        titleBox.addView(nameRow)
        titleBox.addView(TextView(ctx).apply {
            text = if (reports.size == 1) "1 report" else reports.size.toString() + " reports"
            textSize = 13f
            setTextColor(Color.parseColor("#0B61CA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        })
        reports.firstOrNull()?.date?.let { latest ->
            displayDate(latest).takeUnless { it.isBlank() }?.let { day ->
                titleBox.addView(TextView(ctx).apply {
                    text = "Last sent: " + day
                    textSize = 12f
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }
        header.addView(titleBox)

        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back_chevron)
            rotation = if (expanded) 90f else 270f
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#98A2B3"))
        }
        header.addView(chevron)
        content.addView(header)

        val reportsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        reports.forEach { rep ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_daily_log_entry_block)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            }
            row.addView(TextView(ctx).apply {
                text = displayDate(rep.date)
                textSize = 13f
                setTextColor(Color.parseColor("#101828"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            })
            row.addView(dprStatusPill(ctx, rep.sentCount ?: 0, rep.failedCount ?: 0))
            reportsBox.addView(row)
        }
        content.addView(reportsBox)

        card.setOnClickListener {
            val open = !expandedDprProjects.contains(projectName)
            if (open) expandedDprProjects.add(projectName) else expandedDprProjects.remove(projectName)
            reportsBox.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 90f else 270f).setDuration(160).start()
        }

        card.addView(content)
        return card
    }

    private fun toggleRecipient(id: String, active: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { dprApi.updateDprRecipient(session.bearerToken, UpdateDprRecipientRequest(id = id, isActive = active)) }
            if (_binding == null) return@launch
            loadDpr()
        }
    }

    private fun removeRecipient(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { dprApi.removeDprRecipient(session.bearerToken, IdOnlyRequest(id)) }
            if (_binding == null) return@launch
            loadDpr()
        }
    }

    /** Send needs a project — pick it here (the page carries no selector). */
    private fun sendDpr() {
        if (projects.isEmpty()) {
            toast(if (projectsLoading) "Loading projects…" else "No projects available")
            if (!projectsLoading) loadProjects()
            return
        }
        val options = projects.map { SearchableOption(it, it.name ?: "Untitled project", it.status) }
        SearchableSelectionDialog.show(requireContext(), "Send DPR for project", options) { p ->
            if (_binding == null) return@show
            binding.btnSendDpr.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val resp = runCatching { dprApi.sendDprNow(session.bearerToken, SendDprRequest(p.id)) }.getOrNull()
                if (_binding == null) return@launch
                binding.btnSendDpr.isEnabled = true
                val msg = when {
                    resp?.success != true -> resp?.error ?: "Couldn't send DPR"
                    resp.skipped == true -> "No active recipients for ${p.name ?: "this project"}"
                    else -> "DPR sent: ${resp.sentCount ?: 0} sent, ${resp.failedCount ?: 0} failed"
                }
                toast(msg)
                loadDpr()
            }
        }
    }

    /** Horizontal strip of attachment thumbnails; tap opens the media. */
    private fun buildAttachmentStrip(ctx: android.content.Context, atts: List<DailyLogAttachment>): View {
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        atts.take(12).forEach { a ->
            val url = a.url?.takeIf { it.isNotBlank() }
                ?: (BuildConfig.BASE_URL + "api/storage/serve?storageId=" + a.storageId)
            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) }
            }
            frame.addView(ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(64))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_input)
                clipToOutline = true
                load(url)
            })
            if (a.type == "video") {
                frame.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_home_trip_play)
                    setColorFilter(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_home_new_action_circle)
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#66000000"))
                    val p = dp(6); setPadding(p, p, p, p)
                    layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
                })
            }
            frame.setOnClickListener {
                // Images preview in-app; video still opens externally.
                if (a.type == "video") openMedia(url)
                else ImagePreviewDialog.show(requireContext(), url)
            }
            row.addView(frame)
        }
        scroll.addView(row)
        return scroll
    }

    private fun openMedia(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Can't open this file") }
    }

    private fun openLogDetail(log: DailyLogEntry) {
        // Fold in device-cached media when the server has none (pre-deploy).
        val merged = log.copy(attachments = mergedAttachments(requireContext(), log))
        val json = runCatching { com.google.gson.Gson().toJson(merged) }.getOrNull() ?: return
        DailyLogDetailBottomSheet.newInstance(json).showOnce(childFragmentManager, "daily_log_detail")
    }

    /** Server attachments if present, else the device-local cache for this log. */
    private fun mergedAttachments(ctx: android.content.Context, log: DailyLogEntry): List<DailyLogAttachment> {
        val server = log.attachments.orEmpty().filter { it.storageId.isNotBlank() }
        if (server.isNotEmpty()) return server
        val bySig = DailyLogAttachmentCache.get(
            ctx, DailyLogAttachmentCache.key(log.projectId, log.date, log.workSummary),
        )
        return bySig.ifEmpty { DailyLogAttachmentCache.get(ctx, log.id) }
            .filter { it.storageId.isNotBlank() }
    }

    private fun displayDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
        }.getOrDefault(iso)
    }

    private fun toast(m: String) { context?.let { Toast.makeText(it, m, Toast.LENGTH_SHORT).show() } }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        logsSkeleton?.cancel(); logsSkeleton = null
        dprSkeleton?.cancel(); dprSkeleton = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        // Client-side fallback fans out one call per project; bound it to the
        // same window the server-side /mine aggregate uses so a big project
        // list can't spawn hundreds of parallel requests.
        private const val AGG_PROJECT_CAP = 30
    }
}
