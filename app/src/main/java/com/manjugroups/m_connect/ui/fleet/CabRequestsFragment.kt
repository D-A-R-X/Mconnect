package com.manjugroups.m_connect.ui.fleet

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentCabRequestsBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AssignCabRequestBody
import com.manjugroups.m_connect.network.CabRequest
import com.manjugroups.m_connect.network.CabRequestActionResponse
import com.manjugroups.m_connect.network.CabRequestIdBody
import com.manjugroups.m_connect.network.RejectCabRequestBody
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskVehicle
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fleet > Cab Requests, as on the web: request a company vehicle for official
 * travel; the reporting officer approves; the fleet desk assigns a vehicle.
 *
 * Tabs and actions follow the same IAM keys as the web and the server:
 *  - marketing.fleet.cabRequest.create  → My Requests, New request, cancel own
 *  - marketing.fleet.cabRequest.approve → Approvals (approve / reject)
 *  - marketing.fleet.cabRequest.viewAll → All Requests
 *  - marketing.fleet.cabRequest.assign  → assign a vehicle, mark completed
 */
class CabRequestsFragment : Fragment() {

    private enum class Tab(val label: String) { MY("My Requests"), APPROVALS("Approvals"), ALL("All Requests") }

    private var _binding: FragmentCabRequestsBinding? = null
    private val binding get() = _binding!!

    private val session by lazy { SessionManager(requireContext()) }
    private val api by lazy { ApiService.create() }

    private val canCreate get() = session.hasPermission("marketing.fleet.cabRequest.create")
    private val canApprove get() = session.hasPermission("marketing.fleet.cabRequest.approve")
    private val canViewAll get() = session.hasPermission("marketing.fleet.cabRequest.viewAll")
    private val canAssign get() = session.hasPermission("marketing.fleet.cabRequest.assign")

    private var tabs: List<Tab> = emptyList()
    private var currentTab: Tab = Tab.MY
    private var statusFilter: String? = null
    private var query: String = ""

    private var myRequests: List<CabRequest> = emptyList()
    private var approvals: List<CabRequest> = emptyList()
    private var allRequests: List<CabRequest> = emptyList()
    private var hasLoadedOnce = false
    /** Why the last load failed, shown instead of "No cab requests yet". */
    private var loadError: String? = null
    private var skeleton: ObjectAnimator? = null
    private var actionInFlight = false

    private val adapter = CabRequestAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCabRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        tabs = buildList {
            if (canCreate) add(Tab.MY)
            if (canApprove) add(Tab.APPROVALS)
            if (canViewAll) add(Tab.ALL)
        }
        // Same default as the web: All, else Approvals, else My Requests.
        val requested = arguments?.getString(ARG_TAB)
        currentTab = when {
            requested == TAB_APPROVALS && Tab.APPROVALS in tabs -> Tab.APPROVALS
            requested == TAB_MY && Tab.MY in tabs -> Tab.MY
            Tab.ALL in tabs -> Tab.ALL
            Tab.APPROVALS in tabs -> Tab.APPROVALS
            else -> Tab.MY
        }
        renderTabs()

        binding.btnNewRequest.visibility = if (canCreate) View.VISIBLE else View.GONE
        binding.btnNewRequest.setOnClickListener { openNewRequest() }
        parentFragmentManager.setFragmentResultListener(NewCabRequestBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, _ ->
            load()
        }

        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { load() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        binding.btnStatusFilter.setOnClickListener { pickStatus() }
        binding.btnClearFilters.setOnClickListener {
            statusFilter = null
            binding.etSearch.setText("")
            render()
        }

        if (tabs.isEmpty()) {
            binding.emptyState.setTitle("No access")
            binding.emptyState.setDescription("You don't have permission to use cab requests.")
            binding.emptyState.visibility = View.VISIBLE
            return
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // A detail screen with its own white header: draw it below the status
        // bar (the Library behind it is full-bleed) and hide the tab bar.
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.WHITE, darkStatusIcons = true, fullBleed = false)
        }
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        skeleton?.cancel()
        skeleton = null
        _binding = null
        super.onDestroyView()
    }

    private fun renderTabs() {
        // Like the web, the tab bar only shows when there is more than My Requests.
        val showBar = canApprove || canViewAll
        binding.segmentedTabs.visibility = if (showBar && tabs.size > 1) View.VISIBLE else View.GONE
        if (!showBar || tabs.size <= 1) return
        val pending = approvals.count { it.status == "pending" }
        val labels = tabs.map { tab ->
            if (tab == Tab.APPROVALS && pending > 0) "${tab.label} ($pending)" else tab.label
        }
        binding.segmentedTabs.setTabs(labels, tabs.indexOf(currentTab).coerceAtLeast(0)) { index ->
            currentTab = tabs[index]
            render()
        }
    }

    private fun load() {
        if (!hasLoadedOnce) {
            binding.skeletonContainer.visibility = View.VISIBLE
            skeleton = SkeletonLoader.show(binding.skeletonContainer, count = 4)
        }
        val token = session.bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            val mine = if (canCreate) async { runCatching { api.listMyCabRequests(token) } } else null
            val forMe = if (canApprove) async { runCatching { api.listCabRequestApprovals(token) } } else null
            val all = if (canViewAll) async { runCatching { api.listAllCabRequests(token) } } else null
            var firstError: String? = null
            mine?.await()?.let { r ->
                r.getOrNull()?.takeIf { it.success }?.let { myRequests = it.requests.orEmpty() }
                    ?: run { firstError = firstError ?: errorOf(r) }
            }
            forMe?.await()?.let { r ->
                r.getOrNull()?.takeIf { it.success }?.let { approvals = it.requests.orEmpty() }
                    ?: run { firstError = firstError ?: errorOf(r) }
            }
            all?.await()?.let { r ->
                r.getOrNull()?.takeIf { it.success }?.let { allRequests = it.requests.orEmpty() }
                    ?: run { firstError = firstError ?: errorOf(r) }
            }
            if (_binding == null) return@launch
            hasLoadedOnce = true
            skeleton?.cancel()
            skeleton = null
            binding.skeletonContainer.removeAllViews()
            binding.skeletonContainer.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            loadError = firstError
            firstError?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
            renderTabs()
            render()
        }
    }

    private fun errorOf(result: Result<com.manjugroups.m_connect.network.CabRequestListResponse>): String {
        result.getOrNull()?.error?.let { return it }
        val e = result.exceptionOrNull()
        serverMessage(e)?.let { return it }
        // The routes are missing on a server that hasn't been updated yet.
        if ((e as? retrofit2.HttpException)?.code() == 404) return "Cab requests aren't available on the server yet."
        return "Couldn't load cab requests"
    }

    private fun rowsForTab(): List<CabRequest> = when (currentTab) {
        Tab.MY -> myRequests
        Tab.APPROVALS -> approvals
        Tab.ALL -> allRequests
    }

    private fun render() {
        val b = _binding ?: return
        val source = rowsForTab()
        val q = query.trim().lowercase(Locale.US)
        val rows = source.filter { r ->
            (statusFilter == null || r.status == statusFilter) &&
                (q.isEmpty() || listOf(
                    r.purpose, r.staffName, r.fromLocation, r.toLocation, r.requestedDate, r.vehicleNumber,
                ).any { it?.lowercase(Locale.US)?.contains(q) == true })
        }
        b.btnStatusFilter.text = (statusFilter?.let { STATUS_LABELS[it] } ?: "All Status") + " ▾"
        b.btnClearFilters.visibility = if (statusFilter != null || q.isNotEmpty()) View.VISIBLE else View.GONE
        adapter.submit(rows, showStaff = currentTab != Tab.MY)
        if (!hasLoadedOnce) return
        when {
            source.isEmpty() && loadError != null -> {
                b.emptyState.setTitle("Couldn't load cab requests")
                b.emptyState.setDescription("$loadError Pull down to try again.")
                b.emptyState.visibility = View.VISIBLE
            }
            source.isEmpty() -> {
                b.emptyState.setTitle("No cab requests yet")
                b.emptyState.setDescription(
                    if (canCreate) "Tap New to raise a cab request." else "No cab requests found.",
                )
                b.emptyState.visibility = View.VISIBLE
            }
            rows.isEmpty() -> {
                b.emptyState.setTitle("No requests match your filters")
                b.emptyState.setDescription("Try adjusting your search or status filter.")
                b.emptyState.visibility = View.VISIBLE
            }
            else -> b.emptyState.visibility = View.GONE
        }
    }

    private fun pickStatus() {
        val keys = listOf<String?>(null) + STATUS_LABELS.keys
        val labels = keys.map { it?.let { k -> STATUS_LABELS[k] } ?: "All Status" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Status")
            .setItems(labels) { _, which ->
                statusFilter = keys[which]
                render()
            }
            .show()
    }

    private fun openNewRequest() {
        NewCabRequestBottomSheet().show(parentFragmentManager, "new_cab_request")
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun runAction(successMessage: String, call: suspend () -> CabRequestActionResponse) {
        if (actionInFlight) return
        actionInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            val message = try {
                val resp = call()
                if (resp.success) successMessage else resp.error ?: "Something went wrong"
            } catch (e: Exception) {
                serverMessage(e) ?: e.message ?: "Something went wrong"
            }
            actionInFlight = false
            if (_binding == null) return@launch
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun approve(r: CabRequest) {
        AlertDialog.Builder(requireContext())
            .setTitle("Approve cab request?")
            .setMessage("${r.staffName ?: "Staff"} · ${formatDate(r.requestedDate)}\n${r.purpose.orEmpty()}")
            .setPositiveButton("Approve") { _, _ ->
                runAction("Cab request approved") {
                    api.approveCabRequest(session.bearerToken, CabRequestIdBody(r.id))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reject(r: CabRequest) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejection"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val wrap = LinearLayout(requireContext()).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Reject cab request")
            .setView(wrap)
            .setPositiveButton("Reject", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val reason = input.text?.toString()?.trim().orEmpty()
            if (reason.isEmpty()) {
                Toast.makeText(requireContext(), "Please provide a rejection reason", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            runAction("Cab request rejected") {
                api.rejectCabRequest(session.bearerToken, RejectCabRequestBody(r.id, reason))
            }
        }
    }

    private fun cancel(r: CabRequest) {
        AlertDialog.Builder(requireContext())
            .setTitle("Cancel this cab request?")
            .setMessage(r.purpose.orEmpty())
            .setPositiveButton("Cancel request") { _, _ ->
                runAction("Cab request cancelled") {
                    api.cancelCabRequest(session.bearerToken, CabRequestIdBody(r.id))
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun complete(r: CabRequest) {
        AlertDialog.Builder(requireContext())
            .setTitle("Mark this trip completed?")
            .setMessage("${r.vehicleNumber.orEmpty()} · ${formatDate(r.requestedDate)}")
            .setPositiveButton("Mark completed") { _, _ ->
                runAction("Marked completed") {
                    api.completeCabRequest(session.bearerToken, CabRequestIdBody(r.id))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick an active vehicle (driver prefilled from it), as on the web. */
    private fun assign(r: CabRequest) {
        viewLifecycleOwner.lifecycleScope.launch {
            val vehicles = try {
                val resp = TravelDeskApi.create().listMmsVehicles(session.bearerToken)
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Couldn't load vehicles", Toast.LENGTH_LONG).show()
                    return@launch
                }
                resp.rows.filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), serverMessage(e) ?: "Couldn't load vehicles", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (_binding == null) return@launch
            if (vehicles.isEmpty()) {
                Toast.makeText(requireContext(), "No active vehicles", Toast.LENGTH_LONG).show()
                return@launch
            }
            SearchableSelectionDialog.show(
                context = requireContext(),
                title = "Assign vehicle",
                options = vehicles.map {
                    SearchableOption(
                        item = it,
                        title = it.vehicleNumber ?: "Vehicle",
                        subtitle = it.defaultDriverName,
                        keywords = listOfNotNull(it.make, it.model, it.defaultDriverPhone).joinToString(" "),
                    )
                },
            ) { vehicle -> confirmAssign(r, vehicle) }
        }
    }

    private fun confirmAssign(r: CabRequest, vehicle: TravelDeskVehicle) {
        val ctx = requireContext()
        val name = EditText(ctx).apply {
            hint = "Driver name (optional)"
            setText(vehicle.defaultDriverName.orEmpty())
        }
        val phone = EditText(ctx).apply {
            hint = "Driver phone (optional)"
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            setText(vehicle.defaultDriverPhone.orEmpty().filter { it.isDigit() }.takeLast(10))
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(phone)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Assign ${vehicle.vehicleNumber ?: "vehicle"}")
            .setView(wrap)
            .setPositiveButton("Assign") { _, _ ->
                runAction("Vehicle assigned successfully") {
                    api.assignCabRequest(
                        session.bearerToken,
                        AssignCabRequestBody(
                            id = r.id,
                            vehicleId = vehicle.id,
                            driverName = name.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                            driverPhone = phone.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── List ────────────────────────────────────────────────────────────────

    private inner class CabRequestAdapter : RecyclerView.Adapter<CabRequestAdapter.Holder>() {
        private var rows: List<CabRequest> = emptyList()
        private var showStaff = true

        fun submit(next: List<CabRequest>, showStaff: Boolean) {
            rows = next
            this.showStaff = showStaff
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(val card: LinearLayout) : RecyclerView.ViewHolder(card)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#EAECF0"))
                }
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }
            return Holder(card)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            bindCard(holder.card, rows[position], showStaff)
        }
    }

    private fun bindCard(card: LinearLayout, r: CabRequest, showStaff: Boolean) {
        val ctx = card.context
        card.removeAllViews()

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = r.purpose?.ifBlank { null } ?: "Cab request"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#101828"))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(statusBadge(r.status))
        card.addView(top)

        if (showStaff) {
            line(card, listOfNotNull(r.staffName, r.employeeId).joinToString(" · "), "#344054")
        }
        line(card, "📅  " + listOfNotNull(formatDate(r.requestedDate), r.requestedTime).joinToString(" · "))
        line(card, "📍  ${r.fromLocation.orEmpty()}\n→  ${r.toLocation.orEmpty()}")
        r.passengerCount?.toInt()?.takeIf { it > 0 }?.let {
            line(card, "👥  $it passenger" + if (it == 1) "" else "s")
        }
        if (!r.vehicleNumber.isNullOrBlank()) {
            line(
                card,
                "🚗  " + listOfNotNull(r.vehicleNumber, r.driverName, r.driverPhone)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                "#101828",
            )
        }
        if (r.status == "rejected" && !r.rejectionReason.isNullOrBlank()) {
            line(card, "Reason: ${r.rejectionReason}", "#B91C1C")
        }
        if (!r.notes.isNullOrBlank()) line(card, r.notes)

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val mine = r.staffId != null && r.staffId == session.staffId
        val status = r.status.orEmpty()
        if (status == "pending" && canApprove &&
            (currentTab == Tab.APPROVALS || r.reportingToId == session.staffId)
        ) {
            actions.addView(actionButton("Reject", "#B91C1C") { reject(r) })
            actions.addView(actionButton("Approve", "#15803D") { approve(r) })
        }
        if (status == "approved" && canAssign) {
            actions.addView(actionButton("Assign vehicle", "#7E22CE") { assign(r) })
        }
        if (status == "assigned" && canAssign) {
            actions.addView(actionButton("Mark completed", "#15803D") { complete(r) })
        }
        if ((status == "pending" || status == "approved") && canCreate && (mine || canViewAll)) {
            actions.addView(actionButton("Cancel", "#667085") { cancel(r) })
        }
        if (actions.childCount > 0) card.addView(actions)
    }

    private fun line(card: LinearLayout, value: String?, color: String = "#475467") {
        if (value.isNullOrBlank()) return
        card.addView(TextView(card.context).apply {
            text = value
            textSize = 13f
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun actionButton(label: String, color: String, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(color))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor(color))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** The web's outline badge colours per status. */
    private fun statusBadge(status: String?) = TextView(requireContext()).apply {
        val (bg, fg, border) = when (status) {
            "pending" -> Triple("#FFFBEB", "#B45309", "#FDE68A")
            "approved" -> Triple("#EFF6FF", "#1D4ED8", "#BFDBFE")
            "rejected" -> Triple("#FEF2F2", "#B91C1C", "#FECACA")
            "assigned" -> Triple("#FAF5FF", "#7E22CE", "#E9D5FF")
            "completed" -> Triple("#F0FDF4", "#15803D", "#BBF7D0")
            else -> Triple("#F3F4F6", "#6B7280", "#E5E7EB")
        }
        text = status?.replaceFirstChar { it.uppercase() } ?: "—"
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(fg))
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor(border))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAB_MY = "my"
        const val TAB_APPROVALS = "approvals"
        private const val ARG_TAB = "tab"

        private val STATUS_LABELS = linkedMapOf(
            "pending" to "Pending",
            "approved" to "Approved",
            "rejected" to "Rejected",
            "assigned" to "Assigned",
            "completed" to "Completed",
            "cancelled" to "Cancelled",
        )

        fun newInstance(tab: String? = null) = CabRequestsFragment().apply {
            arguments = Bundle().apply { tab?.let { putString(ARG_TAB, it) } }
        }

        /** The `requestedDate` as "dd MMM yyyy" (en-IN), as the web shows it. */
        fun formatDate(ymd: String?): String? {
            if (ymd.isNullOrBlank()) return null
            return runCatching {
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd) ?: return ymd
                SimpleDateFormat("dd MMM yyyy", Locale("en", "IN")).format(d)
            }.getOrDefault(ymd)
        }

        /** The `error` of a JSON error body (Retrofit HttpException), or null. */
        fun serverMessage(e: Throwable?): String? {
            val http = e as? retrofit2.HttpException ?: return null
            val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull() ?: return null
            return runCatching {
                val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
                (obj.get("error") ?: obj.get("message"))?.asString?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }
}
