package com.manjugroups.m_connect.ui.marketing

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.home.TripNavigationFragment
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

/**
 * Site Visits list — counterpart to [CpVisitsFragment] for client-comes-to-plot
 * trips owned by the site incharge.
 *
 * Reuses the CP Visits layout (top bar / search / filter pills / list / empty
 * state) but filters the same `getMySiteVisits` payload down to rows where the
 * trip type is not `client_place` — i.e. proper site visits routed through
 * the `siteVisits` Convex table. The row layout's "Client" label is swapped
 * to "Site" via the new `tvVisitItemSiteLabel` id.
 *
 * The full CP/SV workflow (vehicle assignment, picked-up → on-site → dropped,
 * checkbox outcome form) lives in a separate plan and is gated behind the
 * user's "start" word. This fragment ships the LIST view today so the row in
 * App Library has a destination to navigate to.
 */
class SiteVisitsFragment : Fragment() {
    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private var rootView: View? = null

    private enum class Filter { ALL, SCHEDULED, STARTED, PICKED_UP, COMPLETED, EXPIRED }

    private var allVisits: List<TodayVisit> = emptyList()
    // Gates the skeleton to the first load so refreshes / re-opens don't
    // flash already-rendered rows back to placeholders.
    private var hasLoadedOnce = false
    private var currentFilter: Filter = Filter.ALL
    private var searchQuery: String = ""
    // Active date-range filter (yyyy-MM-dd). Null = default −30/+30 window.
    private var filterFromDate: String? = null
    private var filterToDate: String? = null
    private val DATE_FILTER_KEY = "sv_date_filter_result"
    private var pendingEntryAnimation = true
    // Infinite scroll: render 20 rows, extend by 20 as the list nears its end.
    private var svWindowCtx: String? = null
    private var svVisibleCount = 0
    private val svPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { renderList() },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_site_visits, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        rootView = view

        // Update the title and search-bar hint to match the SV context.
        view.findViewById<TextView>(R.id.tvCpVisitsTitle)?.text = "Site Visits"
        view.findViewById<EditText>(R.id.etCpvSearch)?.hint = "Search SV"

        view.findViewById<View>(R.id.btnCpVisitsBack).setOnClickListener {
            navigateUp()
        }
        // No create flow yet — hide the + button (CP visits has its own create
        // dialog; site visit creation flows through the conversion path).
        view.findViewById<View>(R.id.btnCreateCpVisit)?.visibility = View.GONE

        setupSearch(view)
        setupFilterPills(view)
        setupDateFilter(view)
        // Infinite scroll: render the next 20 rows as the user nears the end.
        view.findViewById<androidx.core.widget.NestedScrollView>(R.id.cpvScroll)?.let { scroll ->
            svPager.bindNestedScroll(scroll, totalCount = { svVisibleCount })
        }

        // NOTE: this fragment INFLATES fragment_cp_visits.xml (see
        // onCreateView), so the swipe-refresh container id is
        // `cpvRefresh` — not `svRefresh` (that id lives in the unused
        // fragment_site_visits_list.xml). Calling findViewById with
        // the wrong id returned null and the chained
        // setupPullToRefresh{} NPE'd → crash on first open of the
        // Site Visits screen.
        view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
            R.id.cpvRefresh
        )?.setupPullToRefresh { loadVisits() }

        loadVisits()

        primeEntryAnimation(view)
        view.post { playEntryAnimation(view) }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        pendingEntryAnimation = true
        rootView = null
        super.onDestroyView()
    }

    // ---------- Search ----------

    private fun setupSearch(root: View) {
        root.findViewById<EditText>(R.id.etCpvSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---------- Date-range filter ----------

    private fun setupDateFilter(root: View) {
        root.findViewById<View>(R.id.btnFilterCalendar)?.setOnClickListener {
            com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.newInstance(
                title = "Filter by date",
                subtitle = "Pick a day or a date range",
                initialFrom = filterFromDate,
                initialTo = filterToDate,
                resultKey = DATE_FILTER_KEY,
            ).show(childFragmentManager, "sv_date_filter")
        }
        childFragmentManager.setFragmentResultListener(
            DATE_FILTER_KEY, viewLifecycleOwner,
        ) { _, bundle ->
            filterFromDate = bundle.getString(
                com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_FROM,
            )
            filterToDate = bundle.getString(
                com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_TO,
            )
            updateDateFilterChip()
            loadVisits()
        }
        root.findViewById<TextView>(R.id.tvDateFilterChip)?.setOnClickListener {
            // Tapping the chip clears the filter and reloads the default window.
            filterFromDate = null
            filterToDate = null
            updateDateFilterChip()
            loadVisits()
        }
    }

    private fun updateDateFilterChip() {
        val chip = rootView?.findViewById<TextView>(R.id.tvDateFilterChip) ?: return
        val from = filterFromDate
        val to = filterToDate
        if (from == null || to == null) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        chip.text = if (from == to) {
            "${prettyDate(from)}  ✕"
        } else {
            "${prettyDate(from)} – ${prettyDate(to)}  ✕"
        }
    }

    private fun prettyDate(iso: String): String {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        }.getOrNull() ?: return iso
        return SimpleDateFormat("dd MMM", Locale.getDefault()).format(parsed)
    }

    /** Keep only visits whose scheduledDate falls in the active range. */
    private fun inDateRange(v: TodayVisit): Boolean {
        val from = filterFromDate ?: return true
        val to = filterToDate ?: return true
        val d = v.scheduledDate.takeIf { it.isNotBlank() } ?: return true
        return d in from..to
    }

    // ---------- Filter pills ----------

    private fun pillsAndFilters(root: View): List<Pair<TextView, Filter>> = listOf(
        root.findViewById<TextView>(R.id.pillAll) to Filter.ALL,
        root.findViewById<TextView>(R.id.pillScheduled) to Filter.SCHEDULED,
        root.findViewById<TextView>(R.id.pillStarted) to Filter.STARTED,
        root.findViewById<TextView>(R.id.pillPickedUp) to Filter.PICKED_UP,
        root.findViewById<TextView>(R.id.pillCompleted) to Filter.COMPLETED,
        root.findViewById<TextView>(R.id.pillExpired) to Filter.EXPIRED,
    )

    private fun setupFilterPills(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            pill?.setOnClickListener {
                if (currentFilter != filter) {
                    currentFilter = filter
                    applyPillStyles(root)
                    renderList()
                }
            }
        }
        applyPillStyles(root)
    }

    private fun applyPillStyles(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            pill ?: return@forEach
            val isActive = filter == currentFilter
            pill.background = ContextCompat.getDrawable(
                pill.context,
                if (isActive) R.drawable.bg_cpv_filter_pill_active
                else R.drawable.bg_cpv_filter_pill_inactive
            )
            pill.setTextColor(Color.parseColor(if (isActive) "#FFFFFF" else "#475467"))
            pill.typeface = ResourcesCompat.getFont(
                pill.context,
                if (isActive) R.font.inter_semibold else R.font.inter_medium
            )
        }
    }

    // Prefer the backend's granular (timestamp-merged) status so we can
    // separate Started / Picked Up / Completed; fall back to the collapsed
    // `status` bucket on legacy rows that don't carry rawStatus yet.
    private fun effStatus(visit: TodayVisit): String =
        (visit.rawStatus?.takeIf { it.isNotBlank() } ?: visit.status).lowercase(Locale.US)

    // "Client Started" — the trip has begun but the client hasn't been
    // picked up from the CP yet. "in-progress" is the collapsed-bucket
    // fallback (covers client_started + picked_up when rawStatus is absent).
    private fun isStarted(s: String): Boolean = s in setOf(
        "client_started", "started", "in-progress", "in_progress",
    )

    // "Picked Up" — client collected from the CP and the trip is in transit
    // through to the site and back. "arrived" is the collapsed-bucket fallback.
    private fun isPickedUp(s: String): Boolean = s in setOf(
        "picked_up", "on_site", "consulting", "on_counselling", "picked_from_site", "arrived",
    )

    private fun isCompleted(s: String): Boolean = s in setOf(
        "dropped", "completed", "complete", "done", "closed",
    )

    private fun isCancelled(s: String): Boolean = s in setOf(
        "cancelled", "canceled", "no_show",
    )

    private fun isPostponed(s: String): Boolean = s == "postponed"

    // Still awaiting its trip — not started, finished, cancelled or postponed.
    private fun isScheduledState(s: String): Boolean =
        !isStarted(s) && !isPickedUp(s) && !isCompleted(s) &&
            !isCancelled(s) && !isPostponed(s)

    // Expired = a still-scheduled visit whose slot has already passed. A
    // trip that has actually progressed (started/picked up/completed) is
    // never "expired", even if its scheduled time is in the past.
    private fun isExpiredVisit(visit: TodayVisit): Boolean {
        if (!isScheduledState(effStatus(visit))) return false
        return com.manjugroups.m_connect.util.VisitExpiry.isExpired(
            visit.scheduledDate, visit.scheduledStartTime, isDone = false,
            createdAtMillis = visit.creationTime?.toLong(),
        )
    }

    private fun matchesFilter(visit: TodayVisit, filter: Filter): Boolean {
        val s = effStatus(visit)
        return when (filter) {
            Filter.ALL -> true
            // Scheduled = genuinely upcoming (not the expired ones).
            Filter.SCHEDULED -> isScheduledState(s) && !isExpiredVisit(visit)
            Filter.STARTED -> isStarted(s)
            Filter.PICKED_UP -> isPickedUp(s)
            Filter.COMPLETED -> isCompleted(s)
            Filter.EXPIRED -> isExpiredVisit(visit)
        }
    }

    // ---------- Load ----------

    private fun loadVisits() {
        val root = rootView ?: return
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<View>(R.id.cpvEmptyState)
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        // Skeleton only on the first load — refresh / return keeps rows.
        if (!hasLoadedOnce) {
            SkeletonUtils.startSkeletonPulse(skeletonContainer)
            empty.visibility = View.GONE
            list.removeAllViews()
        }

        // Use the active date-range filter when set, otherwise the default
        // −30/+30 day window.
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from: String
        val to: String
        if (filterFromDate != null && filterToDate != null) {
            from = filterFromDate!!
            to = filterToDate!!
        } else {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -30)
            from = ymd.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 60)
            to = ymd.format(cal.time)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getMySiteVisits(session.bearerToken, from, to)
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                hasLoadedOnce = true
                if (!resp.success) {
                    showLoadError(resp.error ?: "Failed to load site visits")
                    return@launch
                }
                // Exclude CP visits (which live in CpVisitsFragment) — keep only
                // proper site visits where tripType is null/"site_visit"/etc.
                //
                // Sort by creationTime descending so the most recently
                // CREATED SV shows up at the top (matches the server's
                // own ordering and what the user reads as "latest").
                // The previous sortedByDescending { it.scheduledDate }
                // ordered by FUTURE-most planned visit instead, which
                // pushed brand-new conversions below older-but-later-
                // scheduled entries. Fall back to scheduledDate when
                // creationTime is missing (legacy rows).
                allVisits = resp.visits
                    .filter { it.tripType != "client_place" && it.clientPlaceVisitId == null }
                    .sortedWith(
                        compareByDescending<TodayVisit> { it.creationTime ?: 0.0 }
                            .thenByDescending { it.scheduledDate }
                    )
                renderList()
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                showLoadError("Network error: ${e.message ?: "unknown"}")
            } finally {
                // Same id correction as the setup site — fragment_cp_visits's
                // refresh container is `cpvRefresh`, not `svRefresh`. Without
                // this the spinner spun forever after a load completed.
                root.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
                    R.id.cpvRefresh
                )?.dismissRefresh()
            }
        }
    }

    private fun renderList() {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)
        list.removeAllViews()

        val visible = allVisits
            .filter { matchesFilter(it, currentFilter) }
            .filter { inDateRange(it) }
            .filter { com.manjugroups.m_connect.util.VisitSearch.matches(it, searchQuery) }
        svVisibleCount = visible.size

        // Reset the scroll window whenever the filter / search / data changes.
        val windowCtx = "$currentFilter|$searchQuery|${System.identityHashCode(allVisits)}"
        if (windowCtx != svWindowCtx) {
            svWindowCtx = windowCtx
            svPager.reset()
        }

        if (visible.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            emptyTitle.text = when (currentFilter) {
                Filter.SCHEDULED -> "No Scheduled Visits"
                Filter.STARTED -> "No Started Visits"
                Filter.PICKED_UP -> "No Picked-Up Visits"
                Filter.COMPLETED -> "No Completed Visits"
                Filter.EXPIRED -> "No Expired Visits"
                Filter.ALL -> if (searchQuery.isBlank()) "No Site Visits Yet" else "No Matches Found"
            }
            emptySubtitle.text = if (searchQuery.isNotBlank()) {
                "Try a different search term or switch filters to see other site visits."
            } else {
                "Site visits scheduled for you will appear here once they're booked from the web."
            }
            return
        }

        empty.visibility = View.GONE
        list.visibility = View.VISIBLE
        visible.take(svPager.limit).forEach { list.addView(createRow(it, list)) }
    }

    private fun showLoadError(message: String) {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)
        list.visibility = View.GONE
        empty.visibility = View.VISIBLE
        emptyTitle.text = "Couldn’t Load"
        emptySubtitle.text = message
    }

    // ---------- Row ----------

    private fun createRow(visit: TodayVisit, parent: ViewGroup): View {
        val itemView = layoutInflater.inflate(R.layout.item_site_visit, parent, false)
        val name = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val phone = itemView.findViewById<TextView>(R.id.tvVisitItemPhone)
        val dayView = itemView.findViewById<TextView>(R.id.tvVisitItemDay)
        val dateView = itemView.findViewById<TextView>(R.id.tvVisitItemDate)
        val monthView = itemView.findViewById<TextView>(R.id.tvVisitItemMonth)
        val timeView = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusDot = itemView.findViewById<View>(R.id.visitItemStatusDot)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        
        val vehiclePill = itemView.findViewById<LinearLayout>(R.id.visitItemVehiclePill)
        val vehicleIcon = itemView.findViewById<android.widget.ImageView>(R.id.ivVisitItemVehicleIcon)
        val vehicleStatus = itemView.findViewById<TextView>(R.id.tvVisitItemVehicleStatus)
        
        val bdoName = itemView.findViewById<TextView>(R.id.tvVisitItemBdoName)
        val bdoRole = itemView.findViewById<TextView>(R.id.tvVisitItemBdoRole)
        val destination = itemView.findViewById<TextView>(R.id.tvVisitItemDestination)
        val destinationLabel = itemView.findViewById<TextView>(R.id.tvVisitItemDestinationLabel)

        // LMO (telecaller/creator) — shown only when the backend supplies it.
        val lmoRow = itemView.findViewById<View>(R.id.rowVisitItemLmo)
        val lmoName = itemView.findViewById<TextView>(R.id.tvVisitItemLmoName)
        val lmo = visit.lmoName?.takeIf { it.isNotBlank() }
        if (lmo != null) {
            lmoName.text = lmo
            lmoRow.visibility = View.VISIBLE
        } else {
            lmoRow.visibility = View.GONE
        }

        // Customer Name — backend now falls back through
        // lead → CP.client → CP.clientPlace, so a real name almost
        // always lands here. Render the em-dash placeholder only when
        // the backend genuinely has nothing.
        val displayName = visit.leadName?.takeIf { it.isNotBlank() } ?: "—"
        name.text = displayName

        // Phone — show em-dash when missing instead of a fake
        // hardcoded number (the previous "916379556429" looked like
        // real data and confused users into thinking every SV had it).
        phone.text = visit.leadPhone?.takeIf { it.isNotBlank() } ?: "—"

        // Date — leave the date chip blank when scheduledDate is
        // unparseable. The fake "THU 07 MAY" fallback masked malformed
        // dates as if they were real ones.
        try {
            val dateObj = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(visit.scheduledDate)
            if (dateObj != null) {
                dayView.text = SimpleDateFormat("EEE", Locale.US).format(dateObj).uppercase(Locale.US)
                dateView.text = SimpleDateFormat("dd", Locale.US).format(dateObj)
                monthView.text = SimpleDateFormat("MMM", Locale.US).format(dateObj).uppercase(Locale.US)
            } else {
                dayView.text = ""
                dateView.text = "—"
                monthView.text = ""
            }
        } catch (e: Exception) {
            dayView.text = ""
            dateView.text = "—"
            monthView.text = ""
        }

        // Time — blank when not set rather than a synthetic "11:00 AM".
        timeView.text = visit.scheduledStartTime?.takeIf { it.isNotBlank() } ?: ""

        // BDO Details — the logged-in staff name is the right value to
        // show on rows assigned to the viewer; the prior "AKASH.B"
        // fallback was leftover seed data. Em-dash when the session
        // hasn't yet hydrated a user name.
        bdoName.text = session.userName?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "—"
        bdoRole.text = "BDO"

        // Destination details — prefer the project/place name, fall
        // back to the pickup address, and finally an em-dash. "Client
        // Place Visit" was a generic label that was indistinguishable
        // from real titles.
        destination.text = visit.placeName?.takeIf { it.isNotBlank() }
            ?: visit.placeAddress?.takeIf { it.isNotBlank() }
            ?: "—"
        destinationLabel.text = "ORIGIN"

        // Status pill binding — driven by the same granular status the
        // filters use so the pill and the category the card lives under
        // always agree (an agency trip that progressed via travelDesk*
        // timestamps no longer reads "Expired" here).
        val s = effStatus(visit)
        fun paintPill(label: String, bg: Int, text: String, dot: String) {
            statusText.text = label
            statusPill.background = ContextCompat.getDrawable(requireContext(), bg)
            statusText.setTextColor(Color.parseColor(text))
            statusDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(dot))
        }
        when {
            isCancelled(s) ->
                paintPill("Cancelled", R.drawable.bg_sv_status_red, "#B42318", "#B42318")
            isPostponed(s) ->
                paintPill("Postponed", R.drawable.bg_sv_status_orange, "#B54708", "#F79009")
            // Fleet "completed offline" — the admin marked this SV as done
            // without a live trip; the site incharge must record the outcome.
            visit.completedOffline == true && visit.outcome.isNullOrBlank() ->
                paintPill("Outcome Pending", R.drawable.bg_home_trip_status_progress, "#B54708", "#B54708")
            isCompleted(s) ->
                paintPill("Completed", R.drawable.bg_sv_status_green, "#027A48", "#027A48")
            isStarted(s) ->
                paintPill("Client Started", R.drawable.bg_sv_status_orange, "#B54708", "#B54708")
            isPickedUp(s) -> {
                val label = when (s) {
                    "on_site" -> "On site"
                    "consulting", "on_counselling" -> "On counselling"
                    "picked_from_site" -> "Picked from site"
                    "arrived" -> "Reaching"
                    else -> "Picked up"
                }
                paintPill(label, R.drawable.bg_sv_status_orange, "#B54708", "#B54708")
            }
            // A still-scheduled visit whose slot has already passed is expired,
            // not "Scheduled" — that stale "Scheduled/Start" was the bug.
            isExpiredVisit(visit) ->
                paintPill("Expired", R.drawable.bg_sv_status_red, "#B42318", "#B42318")
            else ->
                paintPill("Scheduled", R.drawable.bg_sv_status_orange, "#B54708", "#F79009")
        }

        // Vehicle Assignment Pill binding.
        //
        // Precedence — matches what the web SV list shows in the same
        // slot, with the travelMode field driving the read:
        //   1. travelMode == "own_vehicle" → "Own Vehicle" pill (info
        //      blue). The customer is driving themselves; there's
        //      nothing for the office to allocate.
        //   2. vehicleAssigned == true (siteVisits.vehicleId set, OR
        //      the legacy visitCategory heuristic for older /today-
        //      visits rows) → "Vehicle Assigned" pill (green).
        //   3. Otherwise → "No Vehicle Assigned" pill (red) — cab SV
        //      awaiting fleet allocation.
        //
        // The legacy heuristic on visitCategory stays as a fallback
        // because /api/sitevisits/my merges legacy fieldVisits rows
        // that don't carry travelMode/vehicleAssigned fields.
        val isOwnVehicle = visit.travelMode == "own_vehicle" ||
            visit.vehiclePreference == "own_vehicle"
        val vehicleAssigned = visit.vehicleAssigned == true ||
            (visit.vehicleAssigned == null &&
                !visit.visitCategory.isNullOrBlank() &&
                visit.visitCategory != "direct_cp" &&
                visit.visitCategory != "site_visit")
        when {
            isOwnVehicle -> {
                vehicleStatus.text = "Own Vehicle"
                vehiclePill.background = ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_sv_status_blue,
                )
                vehicleStatus.setTextColor(Color.parseColor("#175CD3"))
                vehicleIcon?.setColorFilter(Color.parseColor("#175CD3"))
            }
            vehicleAssigned -> {
                vehicleStatus.text = "Vehicle Assigned"
                vehiclePill.background = ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_sv_status_green,
                )
                vehicleStatus.setTextColor(Color.parseColor("#027A48"))
                vehicleIcon?.setColorFilter(Color.parseColor("#027A48"))
            }
            else -> {
                vehicleStatus.text = "No Vehicle Assigned"
                vehiclePill.background = ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_sv_status_red,
                )
                vehicleStatus.setTextColor(Color.parseColor("#F04438"))
                vehicleIcon?.setColorFilter(Color.parseColor("#F04438"))
            }
        }

        // Click actions: Open details on card tap
        itemView.setOnClickListener { openVisit(visit) }

        val params = itemView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = (10 * resources.displayMetrics.density).toInt()
        itemView.layoutParams = params
        return itemView
    }

    private fun openVisit(visit: TodayVisit) {
        SiteVisitOverviewFragment.forVisit(visit).showOnce(parentFragmentManager, "site_visit_overview")
    }

    // ---------- Entry animation (mirrors CpVisitsFragment cadence) ----------

    private fun primeEntryAnimation(root: View) {
        val density = resources.displayMetrics.density
        listOf(R.id.btnCpVisitsBack, R.id.tvCpVisitsTitle, R.id.btnCreateCpVisit).forEach { id ->
            root.findViewById<View>(id)?.apply {
                alpha = 0f
                translationY = -8f * density
            }
        }
        root.findViewById<View>(R.id.cpvSearchContainer)?.apply {
            alpha = 0f
            translationY = 16f * density
        }
        root.findViewById<View>(R.id.cpvFilterScroll)?.apply {
            alpha = 0f
            translationY = 16f * density
        }
        root.findViewById<View>(R.id.cpvScroll)?.apply {
            alpha = 0f
            translationY = 24f * density
        }
    }

    private fun playEntryAnimation(root: View) {
        if (!pendingEntryAnimation) return
        pendingEntryAnimation = false
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val expoOut = android.view.animation.PathInterpolator(0.19f, 1f, 0.22f, 1f)
        fun animateIn(id: Int, delay: Long, duration: Long, interp: android.view.animation.Interpolator) {
            root.findViewById<View>(id)?.animate()
                ?.alpha(1f)?.translationY(0f)
                ?.setStartDelay(delay)?.setDuration(duration)?.setInterpolator(interp)?.start()
        }
        animateIn(R.id.btnCpVisitsBack, 40L, 320L, emphasized)
        animateIn(R.id.tvCpVisitsTitle, 80L, 360L, emphasized)
        animateIn(R.id.btnCreateCpVisit, 120L, 360L, emphasized)
        animateIn(R.id.cpvSearchContainer, 180L, 420L, expoOut)
        animateIn(R.id.cpvFilterScroll, 260L, 420L, expoOut)
        animateIn(R.id.cpvScroll, 340L, 460L, expoOut)
    }
}
