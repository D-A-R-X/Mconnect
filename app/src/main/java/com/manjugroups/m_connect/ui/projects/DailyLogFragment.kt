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
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

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
        tab.setBackgroundResource(if (active) R.drawable.bg_loans_segment_active else android.R.color.transparent)
        tab.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#475467"))
    }

    private fun onAddClicked() {
        if (onNewEntryTab) {
            CreateDailyLogBottomSheet.newInstance(null, null)
                .show(childFragmentManager, "create_daily_log")
        } else {
            DprAddRecipientBottomSheet.newInstance()
                .show(childFragmentManager, "dpr_add")
        }
    }

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
            val mine = runCatching { dprApi.listMyDailyLogs(session.bearerToken) }.getOrNull()
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
    private suspend fun ensureProjects(): List<ProjectSummary> {
        if (projects.isNotEmpty()) return projects
        val resp = runCatching { api.getMarketingProjects(session.bearerToken) }.getOrNull()
        projects = resp?.projects?.map { ProjectSummary(id = it.id, name = it.name, status = it.status) } ?: projects
        return projects
    }

    private fun renderLogs(logs: List<DailyLogEntry>) {
        logsSkeleton?.cancel(); logsSkeleton = null
        val c = binding.logsContainer
        c.alpha = 1f
        c.removeAllViews()
        binding.emptyLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEntriesTitle.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        logs.forEach { log ->
            val ctx = requireContext()
            val cardView = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeColor = Color.parseColor("#EAECF0")
                strokeWidth = dp(1)
                setCardBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
                setOnClickListener { openLogDetail(log) }
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            weatherIcon(log.weather).takeIf { it != 0 }?.let { wIcon ->
                header.addView(ImageView(ctx).apply {
                    setImageResource(wIcon)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        .apply { marginEnd = dp(7) }
                })
            }
            header.addView(TextView(ctx).apply {
                text = displayDate(log.date)
                textSize = 14f
                setTextColor(Color.parseColor("#101828"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            log.projectName?.takeIf { it.isNotBlank() }?.let { header.addView(projectChip(ctx, it)) }
            content.addView(header)

            content.addView(TextView(ctx).apply {
                text = log.workSummary?.trim().takeUnless { it.isNullOrBlank() } ?: "—"
                textSize = 13f
                setTextColor(Color.parseColor("#475467"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(7), 0, 0)
            })

            val pills = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            var hasPill = false
            log.labourCount?.let {
                pills.addView(metaPill(ctx, R.drawable.ic_chat_users, "$it")); hasPill = true
            }
            log.labourHours?.let {
                pills.addView(metaPill(ctx, R.drawable.ic_clock, "${trimNum(it)} hrs")); hasPill = true
            }
            log.siteConditions?.takeIf { it.isNotBlank() }?.let {
                pills.addView(metaPill(ctx, 0, it.replaceFirstChar(Char::uppercase))); hasPill = true
            }
            val atts = mergedAttachments(ctx, log)
            if (atts.isNotEmpty()) {
                pills.addView(metaPill(ctx, R.drawable.ic_attach_image, "${atts.size}")); hasPill = true
            }
            if (hasPill) content.addView(pills)

            if (atts.isNotEmpty()) content.addView(buildAttachmentStrip(ctx, atts))

            cardView.addView(content)
            c.addView(cardView)
        }
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
            val mine = runCatching { dprApi.getMyDpr(session.bearerToken) }.getOrNull()
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

    private fun renderRecipients(list: List<DprRecipient>) {
        dprSkeleton?.cancel(); dprSkeleton = null
        val c = binding.recipientsContainer
        c.alpha = 1f
        c.removeAllViews()
        binding.tvRecipientsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // No recipients → hide Send + history (the add form above stays).
        binding.dprManageSection.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.tvRecipientsCount.text = "${list.count { it.isActive }} active"
        list.forEach { r ->
            val ctx = requireContext()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(ctx).apply { text = r.name ?: "Recipient"; textSize = 14f; setTextColor(Color.parseColor("#101828")) })
            val sub = listOfNotNull(
                r.normalizedPhone ?: r.phone,
                r.projectName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            info.addView(TextView(ctx).apply { text = sub; textSize = 12f; setTextColor(Color.parseColor("#667085")) })
            row.addView(info)
            row.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                isChecked = r.isActive
                setOnCheckedChangeListener { _, checked -> toggleRecipient(r.id, checked) }
            })
            row.addView(TextView(ctx).apply {
                text = "✕"; textSize = 15f; setTextColor(Color.parseColor("#B42318")); setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { removeRecipient(r.id) }
            })
            c.addView(row)
        }
    }

    private fun renderReports(list: List<DprReport>) {
        val c = binding.historyContainer
        c.removeAllViews()
        binding.tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { rep ->
            val label = listOfNotNull(
                displayDate(rep.date),
                rep.projectName?.takeIf { it.isNotBlank() },
                "${rep.sentCount ?: 0} sent / ${rep.failedCount ?: 0} failed",
            ).joinToString("  ·  ")
            c.addView(TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#475467"))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            })
        }
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
        DailyLogDetailBottomSheet.newInstance(json).show(childFragmentManager, "daily_log_detail")
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
