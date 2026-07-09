package com.manjugroups.m_connect.ui.projects

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentDailyLogBinding
import com.manjugroups.m_connect.network.AddDprRecipientRequest
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DailyLogApi
import com.manjugroups.m_connect.network.DailyLogEntry
import com.manjugroups.m_connect.network.DprRecipient
import com.manjugroups.m_connect.network.DprReport
import com.manjugroups.m_connect.network.IdOnlyRequest
import com.manjugroups.m_connect.network.ProjectSummary
import com.manjugroups.m_connect.network.SendDprRequest
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.network.UpdateDprRecipientRequest
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
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

    // Projects power both the send-DPR picker and the inline add-recipient form.
    private var projects: List<ProjectSummary> = emptyList()
    private var projectsLoading = false
    private var onNewEntryTab = true

    // Inline DPR "Add Recipient" form state (mirrors the web modal).
    private var staffCache: List<StaffData>? = null
    private var staffLoading = false
    private var pendingInlineProjectPick = false
    private var dprProjectId: String = ""
    private var dprStaffId: String? = null

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

        // Inline DPR add form (the DPR tab is a full manager — no "+" needed).
        binding.fieldDprProjectInline.setOnClickListener { pickDprProject() }
        binding.fieldDprStaffInline.setOnClickListener { pickDprStaff() }
        binding.btnAddRecipientInline.setOnClickListener { addRecipientInline() }

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
        // DPR tab carries its own inline add form, so the "+" only applies to
        // New Entry (whose form is a full-screen sheet).
        binding.btnDailyLogAdd.visibility = if (newEntry) View.VISIBLE else View.GONE
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
            val resp = runCatching { api.getMyProjects(session.bearerToken) }.getOrNull()
            projectsLoading = false
            if (_binding == null) return@launch
            projects = resp?.projects ?: emptyList()
            // A tap that arrived while the list was still loading opens now.
            if (pendingInlineProjectPick && projects.isNotEmpty()) {
                pendingInlineProjectPick = false
                pickDprProject()
            }
        }
    }

    // ── New Entry: my recent logs across projects ──

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { dprApi.listMyDailyLogs(session.bearerToken) }.getOrNull()
            if (_binding == null) return@launch
            renderLogs(resp?.logs ?: emptyList())
        }
    }

    private fun renderLogs(logs: List<DailyLogEntry>) {
        val c = binding.logsContainer
        c.removeAllViews()
        binding.emptyLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEntriesTitle.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        logs.forEach { log ->
            val ctx = requireContext()
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(ctx).apply {
                text = displayDate(log.date)
                textSize = 13f
                setTextColor(Color.parseColor("#101828"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            log.projectName?.takeIf { it.isNotBlank() }?.let { pn ->
                header.addView(TextView(ctx).apply {
                    text = pn
                    textSize = 11f
                    setTextColor(Color.parseColor("#0B61CA"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            card.addView(header)
            card.addView(TextView(ctx).apply {
                text = log.workSummary?.trim().takeUnless { it.isNullOrBlank() } ?: "—"
                textSize = 13f
                setTextColor(Color.parseColor("#475467"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            val meta = buildString {
                log.labourCount?.let { append("$it labour") }
                log.labourHours?.let { if (isNotEmpty()) append(" · "); append("${it.toInt()} hrs") }
                log.weather?.let { if (isNotEmpty()) append(" · "); append(it.replaceFirstChar(Char::uppercase)) }
            }
            if (meta.isNotEmpty()) card.addView(TextView(ctx).apply {
                text = meta; textSize = 11f; setTextColor(Color.parseColor("#98A2B3")); setPadding(0, dp(4), 0, 0)
            })
            c.addView(card)
        }
    }

    // ── DPR: recipients + history across projects ──

    private fun loadDpr() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { dprApi.getMyDpr(session.bearerToken) }.getOrNull()
            if (_binding == null) return@launch
            renderRecipients(resp?.recipients ?: emptyList())
            renderReports(resp?.reports ?: emptyList())
        }
    }

    private fun renderRecipients(list: List<DprRecipient>) {
        val c = binding.recipientsContainer
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

    // ── Inline DPR "Add Recipient" (mirrors the web modal) ──

    private fun pickDprProject() {
        if (projects.isEmpty()) {
            if (projectsLoading) {
                pendingInlineProjectPick = true
                binding.tvDprProjectValueInline.text = "Loading projects…"
            } else {
                toast("No projects available")
                loadProjects()
            }
            return
        }
        val options = projects.map { SearchableOption(it, it.name ?: "Untitled project", it.status) }
        SearchableSelectionDialog.show(requireContext(), "Select project", options) { p ->
            if (_binding == null) return@show
            dprProjectId = p.id
            binding.tvDprProjectValueInline.text = p.name ?: "Project"
        }
    }

    private fun pickDprStaff() {
        // Cached → open instantly; in-flight → ignore extra taps (no dupes).
        staffCache?.let { showDprStaffDialog(it); return }
        if (staffLoading) return
        staffLoading = true
        val label = binding.tvDprStaffValueInline
        val prev = label.text
        label.text = "Loading staff…"
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getStaff(session.bearerToken) }.getOrNull()
            staffLoading = false
            if (_binding == null) return@launch
            label.text = prev
            val staff = resp?.staff ?: emptyList()
            if (staff.isEmpty()) { toast("Couldn't load staff"); return@launch }
            staffCache = staff
            showDprStaffDialog(staff)
        }
    }

    private fun showDprStaffDialog(staff: List<StaffData>) {
        val options = staff.map {
            SearchableOption(it, it.name ?: "Staff", listOfNotNull(it.designation, it.phone).joinToString(" · "))
        }
        SearchableSelectionDialog.show(requireContext(), "Select staff", options) { s ->
            if (_binding == null) return@show
            dprStaffId = s.id
            binding.tvDprStaffValueInline.text = s.name ?: "Staff"
            if (!s.phone.isNullOrBlank()) binding.etDprPhoneInline.setText(s.phone)
        }
    }

    private fun addRecipientInline() {
        val phone = binding.etDprPhoneInline.text?.toString()?.trim().orEmpty()
        val name = binding.tvDprStaffValueInline.text?.toString()
            ?.takeUnless { it == "Select staff…" }.orEmpty()
        if (dprProjectId.isBlank()) { toast("Select a project"); return }
        if (phone.isEmpty()) { binding.etDprPhoneInline.error = "Enter a number"; return }
        binding.btnAddRecipientInline.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                dprApi.addDprRecipient(
                    session.bearerToken,
                    AddDprRecipientRequest(dprProjectId, dprStaffId, name.ifBlank { phone }, phone),
                )
            }.getOrNull()
            if (_binding == null) return@launch
            binding.btnAddRecipientInline.isEnabled = true
            if (resp?.success == true) {
                binding.etDprPhoneInline.setText("")
                binding.tvDprStaffValueInline.text = "Select staff…"
                dprStaffId = null
                toast("Recipient added")
                loadDpr()
            } else {
                toast(resp?.error ?: "Couldn't add recipient")
            }
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
        super.onDestroyView()
        _binding = null
    }
}
