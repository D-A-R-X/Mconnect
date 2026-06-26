package com.manjugroups.m_connect.ui.library.loans

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.AssignLoanRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.LegalRejectLoanRequest
import com.manjugroups.m_connect.network.LegalStaffRow
import com.manjugroups.m_connect.network.LoanCaseIdBody
import com.manjugroups.m_connect.network.LoanCaseRow
import com.manjugroups.m_connect.network.SubmitLoanDocument
import com.manjugroups.m_connect.network.SubmitLoanRequest
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Library → Loan Desk. Role dropdown (Sales Team / Legal Team / Legal
 * Manager) picks which server feed to load and which actions render on
 * each card:
 *
 *  Sales Team       — own submissions; rectify the row on a rejection
 *                     by re-uploading the 4 documents.
 *  Legal Manager    — all submitted cases; assign each to a Legal Team
 *                     staffer via the people-picker sheet.
 *  Legal Team       — only the cases assigned to me; Accept or
 *                     Reject-with-remarks via the existing reject sheet.
 *
 * Sales-side submission walks the 4 cached files emitted by the upload
 * sheet through /api/storage/upload (one storageId per file) and then
 * POSTs /api/postsales/loans/submit with the full set; the server
 * advances the case from document_collection → bank_allocation so it
 * lands in the Legal Manager queue.
 */
class LoanDeskFragment : Fragment() {

    private enum class RoleMode { SALES, LEGAL_TEAM, LEGAL_MANAGER }

    private lateinit var rvLoanDesk: RecyclerView
    private lateinit var etSearchLoanDesk: EditText
    private lateinit var adapter: LoanDeskAdapter
    private lateinit var tvSelectedRole: TextView
    private lateinit var session: SessionManager

    private val api by lazy { GeoTrackApi.create() }

    private var currentRole: RoleMode = RoleMode.LEGAL_TEAM
    private var hasAccess: Boolean = true
    // Date-range filter, set when the user taps the calendar pill in
    // the header. YYYY-MM-DD strings; null means "no filter".
    private var dateFromYmd: String? = null
    private var dateToYmd: String? = null
    private val calendarResultKey = "loan_desk_calendar_range"

    // Server-side rows keyed by loan-case id so the action handlers can
    // recover full server context (caseId, applicantType, etc) from the
    // UI item without juggling two maps in callbacks.
    private val rowsByLoanCaseId = mutableMapOf<String, LoanCaseRow>()

    private var allItems: List<LoanDeskItem> = emptyList()
    private var filteredItems: List<LoanDeskItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_loan_desk, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btnCalendar).setOnClickListener {
            CalendarRangePickerSheet.newInstance(
                title = "Filter Loan Desk",
                subtitle = "Pick a date range",
                initialFrom = dateFromYmd,
                initialTo = dateToYmd,
                resultKey = calendarResultKey,
            ).show(parentFragmentManager, "LoanDeskCalendarRange")
        }
        parentFragmentManager.setFragmentResultListener(
            calendarResultKey,
            viewLifecycleOwner,
        ) { _, bundle ->
            dateFromYmd = bundle.getString(CalendarRangePickerSheet.KEY_FROM)?.takeIf { it.isNotBlank() }
            dateToYmd = bundle.getString(CalendarRangePickerSheet.KEY_TO)?.takeIf { it.isNotBlank() }
            filterList(etSearchLoanDesk.text?.toString().orEmpty())
            if (dateFromYmd != null && dateToYmd != null) {
                toast("Showing $dateFromYmd to $dateToYmd")
            }
        }

        etSearchLoanDesk = view.findViewById(R.id.etSearchLoanDesk)
        etSearchLoanDesk.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Role is pinned to the signed-in user's designation — no
        // selector UI at all. The pill that used to sit in the header
        // is hidden so there's no chevron suggesting it can be tapped.
        val btnRoleSelector = view.findViewById<View>(R.id.btnRoleSelector)
        tvSelectedRole = view.findViewById(R.id.tvSelectedRole)
        btnRoleSelector.visibility = View.GONE

        val resolved = resolveRoleFromDesignation(session.designation, session.userName)
        if (resolved != null) {
            currentRole = resolved
            hasAccess = true
        } else {
            hasAccess = false
            toast("Loan Desk is only available for Sales / Legal Team / Legal Manager")
        }

        rvLoanDesk = view.findViewById(R.id.rvLoanDesk)
        rvLoanDesk.layoutManager = LinearLayoutManager(requireContext())

        adapter = LoanDeskAdapter(
            items = filteredItems,
            onItemClick = { item ->
                // Sales keeps edit access (preview + re-upload) until a
                // Legal Officer is assigned — once `assignedTo` is set
                // the case is in legal review and the docs are locked
                // to view-only, both for Sales and for the other roles.
                val locked = when (currentRole) {
                    RoleMode.SALES -> !item.assignedTo.isNullOrBlank()
                    RoleMode.LEGAL_MANAGER, RoleMode.LEGAL_TEAM -> true
                }
                openUploadSheet(item, isViewMode = locked)
            },
            onAcceptClick = { item -> acceptCase(item) },
            onRejectClick = { item ->
                LoanDeskRejectBottomSheet.newInstance(item.id)
                    .show(parentFragmentManager, "LoanDeskRejectBottomSheet")
            },
            onRectifyClick = { item -> openUploadSheet(item, isViewMode = false) },
            onAssignClick = { item -> showAssignSheet(item) },
        )
        adapter.setRoleMode(
            when (currentRole) {
                RoleMode.SALES -> LoanDeskAdapter.ROLE_SALES_TEAM
                RoleMode.LEGAL_TEAM -> LoanDeskAdapter.ROLE_LEGAL_TEAM
                RoleMode.LEGAL_MANAGER -> LoanDeskAdapter.ROLE_LEGAL_MANAGER
            }
        )
        rvLoanDesk.adapter = adapter

        parentFragmentManager.setFragmentResultListener(
            LoanDeskRejectBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val itemId = bundle.getString("itemId").orEmpty()
            val remarks = bundle.getString("remarks").orEmpty().trim()
            if (itemId.isBlank() || remarks.isBlank()) {
                toast("Rejection requires remarks")
                return@setFragmentResultListener
            }
            rejectCase(itemId, remarks)
        }

        refreshForRole()
    }

    // ── Data load ─────────────────────────────────────────────────────

    private fun refreshForRole() {
        if (!hasAccess) {
            // Designation didn't map to any of the 3 Loan Desk roles —
            // leave the empty state in place, don't burn an API call.
            allItems = emptyList()
            filterList("")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    when (currentRole) {
                        RoleMode.SALES -> api.listLoanDeskForSales(session.bearerToken, null)
                        RoleMode.LEGAL_MANAGER -> api.listLoanDeskForLegalManager(session.bearerToken)
                        RoleMode.LEGAL_TEAM -> api.listLoanDeskForLegalTeam(session.bearerToken)
                    }
                }
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load loan desk")
                    return@launch
                }
                rowsByLoanCaseId.clear()
                resp.cases.forEach { rowsByLoanCaseId[it.id] = it }
                allItems = resp.cases.map(::mapRowToItem)
                filterList(etSearchLoanDesk.text?.toString().orEmpty())
            } catch (e: Exception) {
                toast(e.message ?: "Failed to load loan desk")
            }
        }
    }

    private fun mapRowToItem(row: LoanCaseRow): LoanDeskItem {
        val docs = row.documentsChecklist.filter { !it.fileName.isNullOrBlank() || !it.storageId.isNullOrBlank() }
        val firstTwoLabels = row.documentLabels.take(2)
        val extraCount = (row.documentLabels.size - firstTwoLabels.size).coerceAtLeast(0)
        val pills = buildList {
            addAll(firstTwoLabels)
            if (extraCount > 0) add("+$extraCount")
        }
        return LoanDeskItem(
            id = row.id,
            caseId = row.caseId,
            applicantType = row.applicantType,
            name = row.name.ifBlank { row.bookingRefNo ?: "Client" },
            phone = row.phone,
            amount = formatRupees(row.amount),
            location = row.location,
            date = formatDate(row.date),
            status = row.statusLabel.ifBlank { "Docs Pending" },
            pills = pills,
            rejectionRemarks = row.legalRejectionRemarks,
            doc1Name = docs.getOrNull(0)?.fileName,
            doc2Name = docs.getOrNull(1)?.fileName,
            doc3Name = docs.getOrNull(2)?.fileName,
            doc4Name = docs.getOrNull(3)?.fileName,
            assignedTo = row.legalAssignedName,
        )
    }

    private fun filterList(query: String) {
        val q = query.trim().lowercase(Locale.US)
        val from = dateFromYmd
        val to = dateToYmd
        filteredItems = allItems.filter { item ->
            val matchesSearch = q.isEmpty() ||
                item.name.lowercase(Locale.US).contains(q) ||
                item.phone.contains(q) ||
                item.location.lowercase(Locale.US).contains(q)
            val matchesDate = if (from == null || to == null) {
                true
            } else {
                // Look up the original server row and compare the
                // date portion of its ISO submittedAt / createdAt to
                // the picked YYYY-MM-DD range. Lex string compare is
                // fine because the ISO prefix is sortable.
                val ymd = rowsByLoanCaseId[item.id]?.date?.take(10).orEmpty()
                ymd.isNotEmpty() && ymd >= from && ymd <= to
            }
            matchesSearch && matchesDate
        }
        adapter.updateList(filteredItems)
    }

    // ── Action handlers ───────────────────────────────────────────────

    private fun acceptCase(item: LoanDeskItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.legalAcceptLoan(session.bearerToken, LoanCaseIdBody(item.id))
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not approve loan")
                    return@launch
                }
                toast("Loan application approved")
                refreshForRole()
            } catch (e: Exception) {
                toast(e.message ?: "Approval failed")
            }
        }
    }

    private fun rejectCase(loanCaseId: String, remarks: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.legalRejectLoan(
                        session.bearerToken,
                        LegalRejectLoanRequest(loanCaseId = loanCaseId, remarks = remarks),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not reject loan")
                    return@launch
                }
                toast("Loan rejected — Sales will rectify")
                refreshForRole()
            } catch (e: Exception) {
                toast(e.message ?: "Rejection failed")
            }
        }
    }

    private fun openUploadSheet(item: LoanDeskItem, isViewMode: Boolean) {
        // Pull the real per-case checklist from the cached server row.
        // Each `documentsChecklist` entry carries:
        //   - label   (the slot title — comes from Post-sales Settings)
        //   - storageId / fileName (set when the doc has been uploaded)
        // Render one row per checklist entry; pre-existing uploads pre-
        // fill the row so the staff doesn't re-upload an approved doc.
        val row = rowsByLoanCaseId[item.id]
        val checklist = row?.documentsChecklist.orEmpty()
        val labels = checklist.map { it.label }
        val preExistingNames = checklist.map { it.fileName }
        val preExistingStorageIds = checklist.map { it.storageId }

        val sheet = LoanDeskUploadBottomSheet.newInstance(
            caseId = item.caseId.orEmpty(),
            requiredLabels = labels,
            preExistingFileNames = preExistingNames,
            preExistingStorageIds = preExistingStorageIds,
            isViewMode = isViewMode,
        ) { uploads ->
            // Sales-side submit only — view-mode opens never invoke
            // this callback so Legal Team / Legal Manager taps stay
            // read-only.
            if (currentRole != RoleMode.SALES) return@newInstance
            submitLoanFromSales(item, uploads)
        }
        sheet.show(parentFragmentManager, "LoanDeskUploadBottomSheet")
    }

    private fun submitLoanFromSales(
        item: LoanDeskItem,
        uploads: List<LoanDeskUploadBottomSheet.SubmittedDoc>,
    ) {
        val caseId = item.caseId
        if (caseId.isNullOrBlank()) {
            toast("This row has no booking case linked")
            return
        }
        if (uploads.isEmpty()) {
            toast("Pick at least one document to upload")
            return
        }
        // The sheet has already finished the cloud uploads — every
        // entry in `uploads` carries a valid storageId — so submit is
        // a single round trip.
        val payload = uploads.map { u ->
            SubmitLoanDocument(label = u.label, storageId = u.storageId, fileName = u.fileName)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.submitLoanRequest(
                        session.bearerToken,
                        SubmitLoanRequest(
                            caseId = caseId,
                            applicantType = item.applicantType ?: "salaried",
                            requestedAmount = null,
                            documents = payload,
                        ),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not submit loan request")
                    return@launch
                }
                toast("Loan request submitted")
                refreshForRole()
            } catch (e: Exception) {
                toast(e.message ?: "Submission failed")
            }
        }
    }

    // ── Assign sheet (Legal Manager) ─────────────────────────────────

    private fun showAssignSheet(item: LoanDeskItem) {
        if (!isAdded) return
        val context = requireContext()
        val content = layoutInflater.inflate(R.layout.bottom_sheet_multi_people_picker, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            val params = sheet.layoutParams
            params.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
            sheet.layoutParams = params
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet)
            androidx.core.view.ViewCompat.setElevation(sheet, 0f)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = true
            }
        }

        val titleView = content.findViewById<TextView>(R.id.tvSheetTitle)
        val closeBtn = content.findViewById<View>(R.id.btnSheetClose)
        val searchField = content.findViewById<EditText>(R.id.etSearchPeople)
        val peopleCard = content.findViewById<LinearLayout>(R.id.peopleCard)
        val emptyView = content.findViewById<TextView>(R.id.tvEmptyPeople)
        val doneBtn = content.findViewById<FrameLayout>(R.id.btnDone)
        val doneLabel = content.findViewById<TextView>(R.id.tvDoneLabel)
        val countView = content.findViewById<TextView>(R.id.tvSelectedCount)

        titleView.text = "Assign Verification"
        doneLabel.text = "Assign"
        countView.text = "0 selected"

        var selectedStaff: LegalStaffRow? = null
        var allLegal: List<LegalStaffRow> = emptyList()

        closeBtn.setOnClickListener { dialog.dismiss() }

        fun updateDoneButton() {
            val enabled = selectedStaff != null
            countView.text = if (enabled) "1 selected" else "0 selected"
            doneBtn.isClickable = enabled
            doneBtn.isFocusable = enabled
            doneBtn.setBackgroundResource(
                if (enabled) R.drawable.bg_sheet_start_button
                else R.drawable.bg_sheet_start_button_disabled
            )
        }

        fun bindPeople(list: List<LegalStaffRow>) {
            peopleCard.removeAllViews()
            if (list.isEmpty()) {
                emptyView.text = "No legal-team staff configured"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
                return
            }
            emptyView.visibility = View.GONE
            peopleCard.visibility = View.VISIBLE
            peopleCard.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            val dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
                setSize(0, (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1))
                setColor(Color.parseColor("#E4E7EC"))
            }
            peopleCard.dividerDrawable = dividerDrawable

            list.forEachIndexed { index, member ->
                val row = layoutInflater.inflate(R.layout.item_chat_sheet_person, peopleCard, false)
                row.tag = member
                val tvName = row.findViewById<TextView>(R.id.tvName)
                val tvSubtitle = row.findViewById<TextView>(R.id.tvSubtitle)
                val radio = row.findViewById<View>(R.id.radioButton)
                val avatarCheck = row.findViewById<View>(R.id.avatarCheck)
                val onlineDot = row.findViewById<View>(R.id.onlineDot)
                tvName.text = member.name
                tvSubtitle.text = listOfNotNull(member.designation, member.department)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" • ")
                    ?: member.employeeId.orEmpty()
                val initials = initialsFor(member.name)
                bindAvatar(
                    row.findViewById(R.id.avatarContainer),
                    row.findViewById(R.id.tvAvatar),
                    row.findViewById(R.id.ivAvatarPhoto),
                    null,
                    initials,
                    index + member.name.length,
                )
                val isSel = selectedStaff?.id == member.id
                radio.setBackgroundResource(
                    if (isSel) R.drawable.bg_sheet_radio_on else R.drawable.bg_sheet_radio_off
                )
                avatarCheck.visibility = if (isSel) View.VISIBLE else View.GONE
                onlineDot.visibility = View.GONE
                row.setOnClickListener {
                    selectedStaff = if (selectedStaff?.id == member.id) null else member
                    for (i in 0 until peopleCard.childCount) {
                        val child = peopleCard.getChildAt(i)
                        val childMember = child.tag as? LegalStaffRow ?: continue
                        val childRadio = child.findViewById<View>(R.id.radioButton)
                        val childAvatarCheck = child.findViewById<View>(R.id.avatarCheck)
                        val childIsSel = selectedStaff?.id == childMember.id
                        childRadio.setBackgroundResource(
                            if (childIsSel) R.drawable.bg_sheet_radio_on else R.drawable.bg_sheet_radio_off
                        )
                        childAvatarCheck.visibility = if (childIsSel) View.VISIBLE else View.GONE
                    }
                    updateDoneButton()
                }
                peopleCard.addView(row)
            }
        }

        fun filterPeople(query: String) {
            val trimmed = query.trim()
            var visibleCount = 0
            for (i in 0 until peopleCard.childCount) {
                val child = peopleCard.getChildAt(i)
                val member = child.tag as? LegalStaffRow ?: continue
                val matches = trimmed.isEmpty() ||
                    member.name.contains(trimmed, ignoreCase = true) ||
                    member.designation?.contains(trimmed, ignoreCase = true) == true ||
                    member.department?.contains(trimmed, ignoreCase = true) == true
                child.visibility = if (matches) {
                    visibleCount++
                    View.VISIBLE
                } else View.GONE
            }
            if (visibleCount == 0) {
                emptyView.text = "No matching legal staff"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                peopleCard.visibility = View.VISIBLE
            }
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterPeople(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        doneBtn.setOnClickListener {
            val staff = selectedStaff ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        api.assignLoanToLegalStaff(
                            session.bearerToken,
                            AssignLoanRequest(
                                loanCaseId = item.id,
                                legalStaffId = staff.id,
                                legalStaffName = staff.name,
                            ),
                        )
                    }
                    if (!resp.success) {
                        toast(resp.error ?: "Could not assign loan")
                        return@launch
                    }
                    toast("Assigned to ${staff.name}")
                    dialog.dismiss()
                    refreshForRole()
                } catch (e: Exception) {
                    toast(e.message ?: "Assignment failed")
                }
            }
        }

        // Async load of legal staff from the server-side filter (the
        // friend's mobile code filtered client-side; this is the same
        // filter moved to the backend so the assign sheet doesn't need
        // a pre-warmed staff cache).
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.listLegalStaffForLoanDesk(session.bearerToken)
                }
                if (resp.success) {
                    allLegal = resp.staff
                    if (dialog.isShowing) bindPeople(allLegal)
                }
            } catch (_: Exception) {
                // Sheet shows the empty state until the user dismisses;
                // the toast on dialog dismiss would be noise.
            }
        }

        dialog.show()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun initialsFor(name: String): String =
        name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { name.take(1).uppercase(Locale.getDefault()) }

    private fun bindAvatar(
        container: View,
        label: TextView,
        ivPhoto: ImageView,
        photoUrl: String?,
        text: String,
        seed: Int,
    ) {
        val resolved = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(photoUrl)
        if (!resolved.isNullOrBlank()) {
            ivPhoto.visibility = View.VISIBLE
            label.visibility = View.GONE
            ivPhoto.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            ivPhoto.visibility = View.GONE
            label.visibility = View.VISIBLE
            val palette = when (seed.mod(4)) {
                0 -> "#E0F2FE" to "#0284C7"
                1 -> "#F0FDF4" to "#16A34A"
                2 -> "#FEF3C7" to "#D97706"
                else -> "#FEE2E2" to "#DC2626"
            }
            container.background?.mutate()?.setTint(Color.parseColor(palette.first))
            label.setTextColor(Color.parseColor(palette.second))
            label.text = text
        }
    }

    private fun formatRupees(amount: Double): String {
        if (amount <= 0) return ""
        val fmt = NumberFormat.getInstance(Locale("en", "IN"))
        return "₹${fmt.format(amount.toLong())}"
    }

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parsers = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd",
        )
        val display = SimpleDateFormat("dd MMM ''yy", Locale.US)
        for (p in parsers) {
            try {
                val d = SimpleDateFormat(p, Locale.US).parse(raw) ?: continue
                return display.format(d)
            } catch (_: Exception) { /* try next */ }
        }
        return raw
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Picks the Loan Desk view from the signed-in staff's designation.
     * Order matters — "Legal Manager" is matched first so a designation
     * like "Senior Legal Manager" doesn't fall into the broader
     * "contains Legal" Team bucket. Returns null when none of the
     * three known roles applies.
     */
    private fun resolveRoleFromDesignation(designation: String?, userName: String? = null): RoleMode? {
        val d = designation?.trim()?.lowercase(Locale.US).orEmpty()
        val n = userName?.trim()?.lowercase(Locale.US).orEmpty()
        // Composite haystack: many real staff records carry the
        // Loan-Desk role signal in the *name* rather than the
        // designation (e.g. name="Legal Manager" but designation just
        // "Manager"). Falling back to the name field keeps those rows
        // out of the no-access bucket without needing a server change.
        val h = (d + " " + n).trim()
        if (h.isBlank()) return null
        if (h.contains("legal manager") || h.contains("legal head")) return RoleMode.LEGAL_MANAGER
        if (h.contains("legal")) return RoleMode.LEGAL_TEAM
        // Sales bucket — explicit BDO / Sales / Marketing executive
        // titles all funnel to the Sales submission queue. Anyone else
        // (Driver, HR, Accountant, …) falls through to null, which the
        // fragment treats as "no access" so unrelated roles can't see
        // the queue.
        if (h.contains("sales") || h.contains("bdo") || h.contains("marketing")) return RoleMode.SALES
        return null
    }

    private fun roleLabel(role: RoleMode): String = when (role) {
        RoleMode.SALES -> "Sales Team"
        RoleMode.LEGAL_TEAM -> "Legal Team"
        RoleMode.LEGAL_MANAGER -> "Legal Manager"
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.parseColor("#FFFFFF"), true, fullBleed = false)
        }
    }
}
