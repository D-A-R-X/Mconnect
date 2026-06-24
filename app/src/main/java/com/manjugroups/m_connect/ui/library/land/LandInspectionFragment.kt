package com.manjugroups.m_connect.ui.library.land

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLandInspectionBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.InspectionAcceptRequest
import com.manjugroups.m_connect.network.InspectionListItem
import com.manjugroups.m_connect.network.InspectionRescheduleRequest
import androidx.fragment.app.setFragmentResultListener
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Land Procurement > Inspection screen.
 *
 * Top of page: sticky blue header with hero illustration on the right,
 * then a search bar + calendar button, then a horizontally-scrollable
 * status filter chip row, then the card list.
 *
 * Items are fetched live from `/api/land/inspections/my`, which returns
 * every land property where the calling staff is in `inspectionAssignedTo`
 * or `inspectionAssignedToList`. Each row arrives with a server-derived
 * status (not_started/in_progress/completed) so the chips can filter
 * locally without an extra round trip per card.
 */
class LandInspectionFragment : Fragment() {

    private var _binding: FragmentLandInspectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api by lazy { ApiService.create() }

    companion object {
        // Fragment-result keys for the two calendar bottom-sheet flows
        // (date filter + reschedule). Per-fragment scoped so the two
        // listeners can't collide.
        private const val RESULT_KEY_FILTER = "land_inspection_filter_calendar"
        private const val RESULT_KEY_RESCHEDULE = "land_inspection_reschedule_calendar"

        // Local persistence of "user has already rescheduled this
        // property". Mobile-only — the web flow doesn't track this; once
        // the inspector has used their one-shot reschedule on a given
        // property, the Reschedule button stays hidden from then on.
        private const val PREFS_NAME = "inspection_rescheduled"
        private const val PREFS_KEY_IDS = "property_ids"
    }

    private fun rescheduledPrefs(): android.content.SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private fun isAlreadyRescheduled(propertyId: String): Boolean {
        val phone = session.userPhone.orEmpty()
        val key = "${phone}_${propertyId}"
        return rescheduledPrefs().getStringSet(PREFS_KEY_IDS, emptySet())?.contains(key) == true
    }

    private fun markRescheduled(propertyId: String) {
        val prefs = rescheduledPrefs()
        val current = prefs.getStringSet(PREFS_KEY_IDS, emptySet()) ?: emptySet()
        val phone = session.userPhone.orEmpty()
        val key = "${phone}_${propertyId}"
        if (key in current) return
        // Build a fresh set — Android's StringSet pref returns the same
        // instance on next read if you mutate-in-place + commit.
        prefs.edit().putStringSet(PREFS_KEY_IDS, current + key).apply()
    }

    private enum class Status(val label: String, val key: String) {
        IN_PROGRESS("In Progress", "in_progress"),
        COMPLETED("Completed", "completed"),
        NOT_STARTED("Not Started", "not_started");

        companion object {
            fun fromKey(key: String?): Status =
                values().firstOrNull { it.key == (key ?: "") } ?: NOT_STARTED
        }
    }

    private data class InspectionRow(
        val propertyId: String,
        val title: String,
        val phone: String,
        val areaLabel: String,
        val date: String,
        val rawInspectionDate: String?,  // yyyy-MM-dd for date filter compare
        val place: String,
        val status: Status,
        val acceptanceStatus: String?,   // pending / accepted / date_change_* / null
    ) {
        // The form is openable when the inspector has accepted, OR there's
        // no acceptance workflow on the record (legacy null), OR work already
        // exists (in_progress / completed). Only a brand-new, not-yet-accepted
        // assignment is gated behind the Accept / Reschedule controls — that's
        // the "after accepting he can fill the form" rule, without locking the
        // inspector out of reports they've already started.
        val canOpenForm: Boolean
            get() = acceptanceStatus == "accepted" ||
                acceptanceStatus == "approved" ||
                acceptanceStatus == "date_change_approved" ||
                acceptanceStatus.isNullOrBlank() ||
                status != Status.NOT_STARTED
    }

    private var allItems: List<InspectionRow> = emptyList()
    private var activeFilter: Status? = null   // null = "All"
    private var searchQuery: String = ""
    private var dateFilter: String? = null  // yyyy-MM-dd, null = no filter
    private var hasLoadedOnce: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLandInspectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        session = SessionManager(requireContext())

        binding.btnInspectionBack.setOnClickListener {
            navigateUp()
        }

        applyStatusBarInset()
        setupInspectionScrollAnimation()

        // Filter chips — null means "All", anything else narrows to that status.
        binding.chipFilterAll.setOnClickListener { setFilter(null) }
        binding.chipFilterProgress.setOnClickListener { setFilter(Status.IN_PROGRESS) }
        binding.chipFilterCompleted.setOnClickListener { setFilter(Status.COMPLETED) }
        binding.chipFilterNotStarted.setOnClickListener { setFilter(Status.NOT_STARTED) }

        binding.etInspectionSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                renderList()
            }
        })

        binding.btnInspectionDateFilter.setOnClickListener { openDatePicker() }
        binding.btnInspectionDateFilterClear.setOnClickListener {
            dateFilter = null
            updateDateFilterChip()
            renderList()
        }

        binding.inspectionRefresh.setupPullToRefresh { loadInspections() }

        loadInspections()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(
                android.graphics.Color.parseColor("#0B61CA"),
                darkStatusIcons = false,
                fullBleed = true,
            )
        }
        // Refresh on return — the user may have come back from saving the
        // inspection form, in which case the row's status flipped server-side.
        if (::session.isInitialized && hasLoadedOnce) loadInspections()
    }

    /**
     * Fetch the current inspector's assigned land properties from
     * `/api/land/inspections/my` and re-render. The spinner is dismissed
     * regardless of success/failure so the user is never stuck on a
     * "loading" state after a network error.
     */
    private fun loadInspections() {
        if (!session.isLoggedIn) {
            allItems = emptyList()
            hasLoadedOnce = true
            renderList()
            binding.inspectionRefresh.dismissRefresh()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listMyInspections(session.bearerToken)
                if (resp.success) {
                    allItems = resp.items.map { it.toRow() }
                } else {
                    showError(resp.error ?: "Failed to load inspections")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            } finally {
                hasLoadedOnce = true
                renderList()
                binding.inspectionRefresh.dismissRefresh()
            }
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Accept the inspection task. On success the server flips
     * inspectionAcceptanceStatus to "accepted"; we reload so the card
     * swaps its Accept/Reschedule controls for the open-form arrow.
     */
    private fun acceptInspection(propertyId: String) {
        if (!session.isLoggedIn) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.acceptInspection(
                    session.bearerToken, InspectionAcceptRequest(propertyId),
                )
                if (resp.success) {
                    android.widget.Toast.makeText(
                        requireContext(), "Inspection accepted",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    loadInspections()
                } else {
                    showError(resp.error ?: "Failed to accept inspection")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            }
        }
    }

    /**
     * Reschedule flow: pick a new date, then send a date-change request to the
     * server (the web property page surfaces it for VP review).
     *
     * Business rule: a reschedule may only move the inspection within 3 days
     * of the originally scheduled date, so the picker is clamped to
     * [scheduledDate, scheduledDate + 3 days]. When no date is on record we
     * fall back to [today, today + 3].
     */
    private fun openRescheduleDatePicker(propertyId: String, scheduledIso: String?) {
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val anchor = Calendar.getInstance()
        scheduledIso?.let { iso ->
            runCatching { ymd.parse(iso) }.getOrNull()?.let { anchor.time = it }
        }
        // Picker shows exactly 3 selectable dates AFTER the scheduled
        // date: scheduled+1, scheduled+2, scheduled+3. The scheduled
        // day itself (and everything beyond the third) renders greyed
        // out — so the inspector can't pick today/the original date
        // and can't push further than a 3-day window. Initial selection
        // lands on scheduled+1 so the first tap-able day is highlighted.
        val firstAllowed = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val lastAllowed = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 3) }
        val minIso = ymd.format(firstAllowed.time)
        val maxIso = ymd.format(lastAllowed.time)

        // Listen for the next reschedule submission. Re-registering on
        // every open is fine — the listener overrides any previous one.
        setFragmentResultListener(RESULT_KEY_RESCHEDULE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            requestReschedule(propertyId, date)
        }
        CalendarRangePickerSheet.newInstance(
            title = "Reschedule Inspection",
            subtitle = "Pick one of the next 3 days",
            initialFrom = minIso,
            initialTo = minIso,
            minDate = minIso,
            maxDate = maxIso,
            resultKey = RESULT_KEY_RESCHEDULE,
            // Reschedule is a single date — no range selection.
            singleSelect = true,
        ).show(parentFragmentManager, "reschedule_calendar")
    }

    private fun requestReschedule(propertyId: String, date: String) {
        if (!session.isLoggedIn) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.rescheduleInspection(
                    session.bearerToken,
                    InspectionRescheduleRequest(propertyId = propertyId, requestedDate = date),
                )
                if (resp.success) {
                    // Mobile-only one-shot: hide the Reschedule button on
                    // every subsequent render for this property. Survives
                    // re-list, fragment recreate, app restart (SharedPrefs).
                    markRescheduled(propertyId)
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Date change requested for ${formatInspectionDate(date)}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    loadInspections()
                } else {
                    showError(resp.error ?: "Failed to request reschedule")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            }
        }
    }

    /**
     * Open the in-house calendar bottom sheet to narrow the visible list
     * to properties whose `inspectionDate` matches exactly (yyyy-MM-dd
     * comparison — the server stores it as a date string). Replaces the
     * stock DatePickerDialog so the look matches the rest of the app's
     * date pickers (sheet with header + month nav + blue circle).
     */
    private fun openDatePicker() {
        val initial = dateFilter ?: run {
            val cal = Calendar.getInstance()
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }
        setFragmentResultListener(RESULT_KEY_FILTER) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            dateFilter = date
            updateDateFilterChip()
            renderList()
        }
        CalendarRangePickerSheet.newInstance(
            title = "Date Filter",
            subtitle = "Select Date Filter",
            initialFrom = initial,
            initialTo = initial,
            resultKey = RESULT_KEY_FILTER,
        ).show(parentFragmentManager, "filter_calendar")
    }

    private fun updateDateFilterChip() {
        val active = dateFilter
        if (active == null) {
            binding.inspectionDateFilterChip.visibility = View.GONE
            // Reset the calendar button to its plain (inactive) state.
            binding.btnInspectionDateFilter.setBackgroundResource(R.drawable.bg_inspection_search)
            binding.ivDateFilterDefault.visibility = View.VISIBLE
            binding.dateFilterActive.visibility = View.GONE
            return
        }
        binding.inspectionDateFilterChip.visibility = View.VISIBLE
        binding.tvInspectionDateFilterLabel.text = "Date · ${formatInspectionDate(active)}"
        // Light up the calendar button with the selected day + month.
        binding.btnInspectionDateFilter.setBackgroundResource(R.drawable.bg_inspection_date_active)
        binding.ivDateFilterDefault.visibility = View.GONE
        binding.dateFilterActive.visibility = View.VISIBLE
        runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(active)!!
            binding.tvDateFilterDay.text = SimpleDateFormat("d", Locale.ENGLISH).format(parsed)
            binding.tvDateFilterMonth.text = SimpleDateFormat("MMM", Locale.ENGLISH).format(parsed)
        }
    }

    private fun InspectionListItem.toRow(): InspectionRow {
        val areaText = totalArea?.let { area ->
            val unit = areaUnit?.replaceFirstChar { c -> c.uppercase() } ?: "Acres"
            "${"%.2f".format(area)} $unit"
        } ?: "—"
        val place = listOfNotNull(
            locality?.takeIf { it.isNotBlank() } ?: city?.takeIf { it.isNotBlank() }
                ?: village?.takeIf { it.isNotBlank() },
            district?.takeIf { it.isNotBlank() } ?: taluk?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { fullAddress.orEmpty() }

        val finalStatus = if (!reportId.isNullOrBlank()) {
            Status.COMPLETED
        } else {
            Status.fromKey(derivedInspectionStatus)
        }

        val finalAcceptanceStatus = if (inspectionAcceptanceStatus == "date_change_requested" &&
            !inspectionDateChangeVpRespondedAt.isNullOrBlank()) {
            "date_change_approved"
        } else {
            inspectionAcceptanceStatus
        }

        return InspectionRow(
            propertyId = propertyId,
            title = referenceNo ?: "LP-${propertyId.take(8)}",
            phone = referrerContact.orEmpty(),
            areaLabel = areaText,
            date = formatInspectionDate(inspectionDate),
            rawInspectionDate = inspectionDate?.takeIf { it.isNotBlank() },
            place = place,
            status = finalStatus,
            acceptanceStatus = finalAcceptanceStatus,
        )
    }

    private fun formatInspectionDate(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatter = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            formatter.format(parser.parse(raw)!!)
        } catch (_: Exception) {
            raw
        }
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Push the blue header content down by the system status-bar height +
     * a small 4dp gap, so the back arrow / title don't collide with the
     * notch on full-bleed devices. Mirrors HomeFragment.applyStatusBarInset.
     */
    private fun applyStatusBarInset() {
        val basePaddingTop = binding.inspectionHeader.paddingTop
        val gapPx = (4 * binding.root.resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.inspectionHeader) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
            ).top
            v.setPadding(
                v.paddingLeft,
                basePaddingTop + topInset + gapPx,
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.inspectionHeader)
    }

    /**
     * "Panel slides up over fixed header" scroll effect.
     *  - The blue header stays anchored at full size — no shrink, no
     *    fade, no movement.
     *  - The rounded-top white panel translates UP as the user scrolls;
     *    once the user has scrolled past the header height, the panel
     *    has fully overlaid the header and is hidden behind nothing.
     *  - The panel's top-corner radius interpolates 24dp → 0dp during
     *    the overlay so the curve flattens into a sharp rectangle once
     *    the panel is fully on top of the header.
     */
    private fun setupInspectionScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxPanelRadiusPx = 30f * density

        // Header stays a flat-bottomed solid blue rectangle.
        val headerBg = binding.inspectionHeader.applyShrinkableBlueHeaderBackground()
        headerBg.setBottomCornerRadius(0f)

        // Grey card — rounded TOP corners + grey bg applied to
        // `inspectionWhitePanel`, which lives INSIDE the NestedScrollView.
        // It IS the scrolling content, so it moves up at exactly 1× rate
        // (no parallax, no shrinking, no separate translation). The
        // ancestors set clipChildren=false in XML so the rounded top
        // edge can draw OUTSIDE the panel and over the blue header.
        val whiteCardBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.parseColor("#F1F3F8"))
            cornerRadii = floatArrayOf(
                maxPanelRadiusPx, maxPanelRadiusPx,
                maxPanelRadiusPx, maxPanelRadiusPx,
                0f, 0f, 0f, 0f,
            )
        }
        binding.inspectionWhitePanel.background = whiteCardBg
    }

    private fun setFilter(status: Status?) {
        activeFilter = status
        styleChip(binding.chipFilterAll, status == null)
        styleChip(binding.chipFilterProgress, status == Status.IN_PROGRESS)
        styleChip(binding.chipFilterCompleted, status == Status.COMPLETED)
        styleChip(binding.chipFilterNotStarted, status == Status.NOT_STARTED)
        renderList()
    }

    private fun styleChip(chip: TextView, active: Boolean) {
        val ctx = context ?: return
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_inspection_chip_active)
            chip.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            chip.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_semibold)
        } else {
            chip.setBackgroundResource(R.drawable.bg_inspection_chip_inactive)
            chip.setTextColor(android.graphics.Color.parseColor("#6B7280"))
            chip.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_medium)
        }
    }

    private fun renderList() {
        val list = binding.inspectionLogList
        list.removeAllViews()
        val dateFilterValue = dateFilter
        val filtered = allItems.filter { item ->
            val matchesStatus = activeFilter == null || item.status == activeFilter
            val matchesSearch = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.place.contains(searchQuery, ignoreCase = true)
            val matchesDate = dateFilterValue == null ||
                item.rawInspectionDate == dateFilterValue
            matchesStatus && matchesSearch && matchesDate
        }
        val inflater = LayoutInflater.from(requireContext())
        filtered.forEach { item ->
            val card = inflater.inflate(R.layout.item_land_log, list, false)
            card.findViewById<TextView>(R.id.tvLandLogTitle).text = item.title
            card.findViewById<TextView>(R.id.tvLandLogPhone).text = item.phone
            card.findViewById<TextView>(R.id.tvLandLogAreaDate).text =
                "${item.areaLabel} • ${item.date}"
            card.findViewById<TextView>(R.id.tvLandLogPlace).text = item.place
            val statusPill = card.findViewById<TextView>(R.id.tvLandLogStatus)
            statusPill.text = item.status.label
            applyStatusStyle(statusPill, item.status)

            val acceptBtn = card.findViewById<View>(R.id.btnInspectionAccept)
            val acceptLabel = card.findViewById<TextView>(R.id.tvInspectionAcceptLabel)
            val acceptIcon = card.findViewById<View>(R.id.ivInspectionAcceptIcon)
            val rescheduleBtn = card.findViewById<View>(R.id.btnInspectionReschedule)
            val arrowBtn = card.findViewById<View>(R.id.btnInspectionArrow)
            val actionsLayout = card.findViewById<View>(R.id.layoutInspectionActions)

            // Mobile-only one-shot reschedule. Once the inspector has
            // requested a date change on this property, the Reschedule
            // button stays hidden on every subsequent render — only
            // Accept remains. The web side doesn't track this; the
            // SharedPrefs flag set in requestReschedule does.
            val rescheduledOnce = isAlreadyRescheduled(item.propertyId)
            // A reschedule request is in flight whenever the server has
            // it marked date_change_requested. While that's true the
            // card should show ONE locked button — never both an active
            // Reschedule AND a passive Re-scheduled side-by-side.
            val rescheduleInFlight = item.acceptanceStatus == "date_change_requested"
            // Drop Accept's left margin whenever it sits alone on the
            // row (either because Reschedule was hidden by the one-shot
            // flag OR because a reschedule is currently awaiting
            // approval). Otherwise the lone button looks offset by the
            // 10dp gap that used to separate it from Reschedule.
            val acceptAlone = rescheduledOnce || rescheduleInFlight
            (acceptBtn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.marginStart = if (acceptAlone) 0
                    else (10 * resources.displayMetrics.density).toInt()
                acceptBtn.layoutParams = lp
            }

            when {
                item.status == Status.COMPLETED -> {
                    // Completed -> show only the arrow button
                    actionsLayout.visibility = View.GONE
                    acceptBtn.visibility = View.GONE
                    rescheduleBtn.visibility = View.GONE
                    arrowBtn.visibility = View.VISIBLE
                    val openForm = View.OnClickListener {
                        SiteInspectionBottomSheet
                            .newInstance(item.propertyId, item.title)
                            .show(parentFragmentManager, "site_inspection")
                    }
                    card.setOnClickListener(openForm)
                    arrowBtn.setOnClickListener(openForm)
                }
                rescheduleInFlight -> {
                    // Reschedule pending VP approval → show a single
                    // full-width locked "Reschedule Requested" pill.
                    actionsLayout.visibility = View.VISIBLE
                    arrowBtn.visibility = View.GONE
                    rescheduleBtn.visibility = View.GONE
                    acceptBtn.visibility = View.VISIBLE
                    acceptIcon.visibility = View.GONE
                    acceptLabel.text = "Reschedule Requested"
                    acceptBtn.alpha = 0.7f
                    acceptBtn.isClickable = false
                    acceptBtn.setOnClickListener(null)
                    card.setOnClickListener {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Date change requested — awaiting approval.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                item.acceptanceStatus == "accepted" || item.acceptanceStatus == "approved" || item.acceptanceStatus == "date_change_approved" -> {
                    // THIS inspector has accepted or reschedule approved → show only a single
                    // "Accepted" or "Approved" button (no Reschedule, no actionable Accept).
                    // Each assignee's acceptance is tracked per-staff on the
                    // server, so one GM accepting never flips another's card.
                    // Tapping opens the inspection form.
                    actionsLayout.visibility = View.VISIBLE
                    arrowBtn.visibility = View.GONE
                    rescheduleBtn.visibility = View.GONE
                    acceptBtn.visibility = View.VISIBLE
                    acceptIcon.visibility = View.VISIBLE
                    acceptLabel.text = if (item.acceptanceStatus == "approved" || item.acceptanceStatus == "date_change_approved") "Approved" else "Accepted"
                    acceptBtn.alpha = 1f
                    acceptBtn.isClickable = true
                    // Grey out the button so the accepted state reads as
                    // done, not a live green CTA.
                    acceptBtn.setBackgroundResource(R.drawable.bg_inspection_accepted_grey)
                    // Sits alone → drop the inter-button left margin.
                    (acceptBtn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.marginStart = 0
                        acceptBtn.layoutParams = lp
                    }
                    val openForm = View.OnClickListener {
                        SiteInspectionBottomSheet
                            .newInstance(item.propertyId, item.title)
                            .show(parentFragmentManager, "site_inspection")
                    }
                    acceptBtn.setOnClickListener(openForm)
                    card.setOnClickListener(openForm)
                }
                else -> {
                    // Pending acceptance (NOT_STARTED) or IN_PROGRESS -> show Reschedule and Accept buttons
                    actionsLayout.visibility = View.VISIBLE
                    arrowBtn.visibility = View.GONE
                    acceptBtn.visibility = View.VISIBLE
                    acceptIcon.visibility = View.VISIBLE
                    rescheduleBtn.visibility = if (rescheduledOnce) View.GONE else View.VISIBLE
                    acceptLabel.text = "Accept"
                    acceptBtn.alpha = 1f
                    acceptBtn.isClickable = true

                    if (item.status == Status.IN_PROGRESS || item.acceptanceStatus == "accepted" || item.acceptanceStatus == "approved" || item.acceptanceStatus == "date_change_approved") {
                        // Already accepted or in progress: clicking accept or card opens the form
                        val openForm = View.OnClickListener {
                            SiteInspectionBottomSheet
                                .newInstance(item.propertyId, item.title)
                                .show(parentFragmentManager, "site_inspection")
                        }
                        acceptBtn.setOnClickListener(openForm)
                        card.setOnClickListener(openForm)
                    } else {
                        // Pending acceptance: click accept accepts, click card shows warning toast
                        acceptBtn.setOnClickListener { acceptInspection(item.propertyId) }
                        card.setOnClickListener {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Accept the inspection before filling the form.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    rescheduleBtn.setOnClickListener { openRescheduleDatePicker(item.propertyId, item.rawInspectionDate) }
                }
            }
            list.addView(card)
        }
        renderEmptyState(filtered.isEmpty())
    }

    /**
     * Decide what (if anything) to show when the card list is empty.
     * Three distinct cases — each needs a different message so the user
     * understands whether to wait, change a filter, or contact an admin.
     */
    private fun renderEmptyState(listEmpty: Boolean) {
        val emptyView = binding.inspectionEmptyState
        if (!listEmpty) {
            emptyView.visibility = View.GONE
            return
        }
        emptyView.visibility = View.VISIBLE
        when {
            !hasLoadedOnce -> {
                binding.tvInspectionEmptyTitle.text = "Loading inspections…"
                binding.tvInspectionEmptySubtitle.text = "Hang on while we fetch your assignments."
            }
            allItems.isEmpty() -> {
                binding.tvInspectionEmptyTitle.text = "No inspections assigned"
                binding.tvInspectionEmptySubtitle.text =
                    "When an admin assigns you to a land property on the web, it will appear here."
            }
            else -> {
                binding.tvInspectionEmptyTitle.text = "No matches"
                binding.tvInspectionEmptySubtitle.text =
                    "Try clearing the date filter, the status chip, or the search box."
            }
        }
    }

    private fun applyStatusStyle(pill: TextView, status: Status) {
        when (status) {
            Status.IN_PROGRESS -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_progress)
                pill.setTextColor(android.graphics.Color.parseColor("#92400E"))
            }
            Status.COMPLETED -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_completed)
                pill.setTextColor(android.graphics.Color.parseColor("#065F46"))
            }
            Status.NOT_STARTED -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_notstarted)
                pill.setTextColor(android.graphics.Color.parseColor("#991B1B"))
            }
        }
    }
}
