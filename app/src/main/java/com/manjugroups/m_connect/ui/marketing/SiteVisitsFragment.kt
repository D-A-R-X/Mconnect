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
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.AdvancedListFilterSheet
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
    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var rootView: View? = null

    // Mirrors the MMS web /marketing/site-visits pipeline tabs (Fixed →
    // Postponed), plus an app-only "All".
    private enum class Filter {
        ALL, FIXED, SCHEDULED, ENROUTE, ONSITE, RETURNING_HOME,
        COMPLETED, CANCELLED, POSTPONED,
    }

    private var allVisits: List<TodayVisit> = emptyList()
    // Gates the skeleton to the first load so refreshes / re-opens don't
    // flash already-rendered rows back to placeholders.
    private var hasLoadedOnce = false
    private var currentFilter: Filter = Filter.ALL
    private var currentScope: CpVisitListScope = CpVisitListScope.ALL
    private var activeOwnershipScope: CpVisitListScope? = null
    private var directReportIds: Set<String> = emptySet()
    private var searchQuery: String = ""
    private var searchReloadJob: kotlinx.coroutines.Job? = null
    // Active date-range filter (yyyy-MM-dd). Null = default −30/+30 window.
    private var filterFromDate: String? = null
    private var filterToDate: String? = null
    private var filterProject: String? = null
    private var filterLmo: String? = null
    private var filterFieldStaff: String? = null
    private val ADVANCED_FILTER_KEY = "sv_advanced_filter_result"
    private var pendingEntryAnimation = true
    // Infinite scroll: render 20 rows, extend by 20 as the list nears its end.
    private var svWindowCtx: String? = null
    private var svVisibleCount = 0
    private var svNextCursor: String? = null
    private var svHasMore = false
    private var svTotal: Int? = null
    private var svLoadingMore = false
    private var svLoadGeneration = 0
    private var svAutoFillPages = 0
    private var filterOptions: com.manjugroups.m_connect.network.SiteVisitFilterOptionsResponse? = null
    private var activeServerQuery: SiteVisitServerQuery? = null
    private val svPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { rootView?.post { renderList() } },
        onEndReached = { loadMoreSiteVisits() },
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
        val createButton = view.findViewById<View>(R.id.btnCreateCpVisit)
        createButton?.visibility = if (session.hasPermission("marketing.siteVisits.create")) {
            View.VISIBLE
        } else {
            View.GONE
        }
        createButton?.contentDescription = "Schedule site visit"
        createButton?.setOnClickListener {
            CreateSiteVisitBottomSheet.newInstance()
                .showOnce(parentFragmentManager, "create_site_visit")
        }
        parentFragmentManager.setFragmentResultListener(
            CreateSiteVisitBottomSheet.RESULT_CREATED,
            viewLifecycleOwner,
        ) { _, _ -> loadVisits() }

        setupSearch(view)
        setupScopeFilter(view)
        setupFilterPills(view)
        setupAdvancedFilter(view)
        loadOwnershipDirectory()
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
        searchReloadJob?.cancel()
        searchReloadJob = null
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
                searchReloadJob?.cancel()
                searchReloadJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(350)
                    loadVisits()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---------- Full-screen filters ----------

    private fun setupAdvancedFilter(root: View) {
        root.findViewById<View>(R.id.btnFilterCalendar)?.setOnClickListener {
            showAdvancedFilters()
        }
        parentFragmentManager.setFragmentResultListener(
            ADVANCED_FILTER_KEY, viewLifecycleOwner,
        ) { _, bundle ->
            val state = AdvancedListFilterSheet.state(bundle) ?: return@setFragmentResultListener
            filterFromDate = state.fromDate
            filterToDate = state.toDate
            currentFilter = state.value(KEY_STATUS)?.let { value ->
                Filter.entries.firstOrNull { it.name == value }
            } ?: Filter.ALL
            activeOwnershipScope = null
            currentScope = CpVisitListScope.ALL
            filterProject = state.value(KEY_PROJECT)
            filterLmo = state.value(KEY_LMO)
            filterFieldStaff = state.value(KEY_FIELD_STAFF)
            applyPillStyles(root)
            updateDateFilterChip()
            loadVisits()
        }
        root.findViewById<TextView>(R.id.tvDateFilterChip)?.setOnClickListener {
            currentFilter = Filter.ALL
            filterFromDate = null
            filterToDate = null
            filterProject = null
            filterLmo = null
            filterFieldStaff = null
            applyPillStyles(root)
            updateDateFilterChip()
            loadVisits()
        }
    }

    private fun showAdvancedFilters() {
        fun options(selector: (TodayVisit) -> Pair<String, String>?): List<AdvancedListFilterSheet.Option> =
            allVisits.mapNotNull(selector).distinctBy { it.first }
                .sortedBy { it.second.lowercase(Locale.US) }
                .map { AdvancedListFilterSheet.Option(it.first, it.second) }

        val projects = options { visit -> facet(visit.projectId, visit.projectName ?: visit.placeName, "project") }
        val lmos = options { visit -> facet(visit.lmoStaffId, visit.lmoName, "lmo") }
        val fieldStaff = options { visit -> facet(visit.bdoStaffId, visit.bdoName, "staff") }
        fun serverOptions(values: List<com.manjugroups.m_connect.network.CpVisitFilterOption>?): List<AdvancedListFilterSheet.Option> =
            values.orEmpty().mapNotNull { option ->
                val id = option.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = option.name?.takeIf(String::isNotBlank)
                    ?: option.label?.takeIf(String::isNotBlank)
                    ?: humanizeFilterValue(id)
                AdvancedListFilterSheet.Option(id, label, option.count?.let { "$it visits" })
            }
        fun mergeOptions(
            authoritative: List<AdvancedListFilterSheet.Option>,
            loaded: List<AdvancedListFilterSheet.Option>,
        ) = (authoritative + loaded).distinctBy { it.value }
        val categories = listOf(
            AdvancedListFilterSheet.Category(KEY_DATE, "Date range", dateRange = true),
            AdvancedListFilterSheet.Category(
                KEY_STATUS,
                "Status",
                Filter.entries.filter { it != Filter.ALL }.map {
                    AdvancedListFilterSheet.Option(it.name, humanizeFilterValue(it.name))
                },
            ),
            AdvancedListFilterSheet.Category(
                KEY_PROJECT, "Project", mergeOptions(serverOptions(filterOptions?.projects), projects), searchable = true,
            ),
            AdvancedListFilterSheet.Category(
                KEY_LMO, "LMO", mergeOptions(serverOptions(filterOptions?.lmos), lmos), searchable = true,
            ),
            AdvancedListFilterSheet.Category(
                KEY_FIELD_STAFF,
                "Field staff",
                mergeOptions(serverOptions(filterOptions?.fieldStaff), fieldStaff),
                searchable = true,
            ),
        )
        val initial = AdvancedListFilterSheet.State(
            selected = buildMap {
                if (currentFilter != Filter.ALL) put(KEY_STATUS, setOf(currentFilter.name))
                filterProject?.let { put(KEY_PROJECT, setOf(it)) }
                filterLmo?.let { put(KEY_LMO, setOf(it)) }
                filterFieldStaff?.let { put(KEY_FIELD_STAFF, setOf(it)) }
            },
            fromDate = filterFromDate,
            toDate = filterToDate,
        )
        AdvancedListFilterSheet.newInstance(categories, initial, ADVANCED_FILTER_KEY).apply {
            countProvider = { state -> allVisits.count { matchesAdvancedState(it, state) } }
        }.showOnce(parentFragmentManager, "sv_advanced_filters")
    }

    private fun updateDateFilterChip() {
        val chip = rootView?.findViewById<TextView>(R.id.tvDateFilterChip) ?: return
        val from = filterFromDate
        val to = filterToDate
        val activeCount = listOfNotNull(
            filterFromDate?.let { "date" },
            currentFilter.takeIf { it != Filter.ALL }?.name,
            filterProject,
            filterLmo,
            filterFieldStaff,
        ).size
        if (activeCount == 0) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        chip.text = if (activeCount == 1 && from != null && to != null) {
            if (from == to) "${prettyDate(from)}  x" else "${prettyDate(from)} - ${prettyDate(to)}  x"
        } else {
            "$activeCount filters active  x"
        }
    }

    private fun facet(id: String?, label: String?, prefix: String): Pair<String, String>? {
        val cleanLabel = label?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return (id?.takeIf { it.isNotBlank() } ?: "$prefix:$cleanLabel") to cleanLabel
    }

    private fun humanizeFilterValue(value: String): String = value
        .lowercase(Locale.US)
        .split('_', '-')
        .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    private fun matchesFacet(selected: String?, id: String?, label: String?, prefix: String): Boolean {
        if (selected == null) return true
        return selected == id || selected == label?.trim()?.let { "$prefix:$it" }
    }

    private fun matchesAdvancedState(visit: TodayVisit, state: AdvancedListFilterSheet.State): Boolean {
        val status = state.value(KEY_STATUS)?.let { value ->
            Filter.entries.firstOrNull { it.name == value }
        } ?: Filter.ALL
        val from = state.fromDate
        val to = state.toDate
        return (from.isNullOrBlank() || visit.scheduledDate >= from) &&
            (to.isNullOrBlank() || visit.scheduledDate <= to) &&
            matchesFilter(visit, status) &&
            matchesFacet(state.value(KEY_PROJECT), visit.projectId, visit.projectName ?: visit.placeName, "project") &&
            matchesFacet(state.value(KEY_LMO), visit.lmoStaffId, visit.lmoName, "lmo") &&
            matchesFacet(state.value(KEY_FIELD_STAFF), visit.bdoStaffId, visit.bdoName, "staff")
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

    private fun setupScopeFilter(root: View) {
        fun selectScope(scope: CpVisitListScope) {
            currentScope = scope
            activeOwnershipScope = scope
            currentFilter = Filter.ALL
            applyPillStyles(root)
            updateDateFilterChip()
            renderList()
        }
        root.findViewById<TextView>(R.id.pillMy).apply {
            visibility = View.VISIBLE
            setOnClickListener { selectScope(CpVisitListScope.MY) }
        }
        root.findViewById<TextView>(R.id.pillTeam).apply {
            visibility = if (session.isAdmin) View.VISIBLE else View.GONE
            setOnClickListener { selectScope(CpVisitListScope.TEAM) }
        }
    }

    private fun loadOwnershipDirectory() {
        viewLifecycleOwner.lifecycleScope.launch {
            val staff = runCatching {
                api.getStaff(session.bearerToken, status = "active")
            }.getOrNull()?.takeIf { it.success }?.staff.orEmpty()
            val viewerIds = setOfNotNull(session.staffId).filter(String::isNotBlank).toSet()
            directReportIds = staff.mapNotNull { person ->
                person.id?.takeIf {
                    person.reportingTo in viewerIds || person.reportingToId in viewerIds
                }
            }.toSet()
            rootView?.findViewById<View>(R.id.pillTeam)?.visibility =
                if (session.isAdmin || directReportIds.isNotEmpty()) View.VISIBLE else View.GONE
            renderList()
        }
    }

    private fun pillsAndFilters(root: View): List<Pair<TextView, Filter>> = listOf(
        root.findViewById<TextView>(R.id.pillAll) to Filter.ALL,
        root.findViewById<TextView>(R.id.pillFixed) to Filter.FIXED,
        root.findViewById<TextView>(R.id.pillScheduled) to Filter.SCHEDULED,
        root.findViewById<TextView>(R.id.pillEnroute) to Filter.ENROUTE,
        root.findViewById<TextView>(R.id.pillOnsite) to Filter.ONSITE,
        root.findViewById<TextView>(R.id.pillReturningHome) to Filter.RETURNING_HOME,
        root.findViewById<TextView>(R.id.pillCompleted) to Filter.COMPLETED,
        root.findViewById<TextView>(R.id.pillCancelled) to Filter.CANCELLED,
        root.findViewById<TextView>(R.id.pillPostponed) to Filter.POSTPONED,
    )

    private fun setupFilterPills(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            pill?.setOnClickListener {
                activeOwnershipScope = null
                currentScope = CpVisitListScope.ALL
                currentFilter = filter
                applyPillStyles(root)
                updateDateFilterChip()
                renderList()
            }
        }
        applyPillStyles(root)
    }

    private fun applyPillStyles(root: View) {
        pillsAndFilters(root).forEach { (pill, filter) ->
            pill ?: return@forEach
            val isActive = activeOwnershipScope == null && filter == currentFilter
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
        listOf(
            root.findViewById<TextView>(R.id.pillMy) to CpVisitListScope.MY,
            root.findViewById<TextView>(R.id.pillTeam) to CpVisitListScope.TEAM,
        ).forEach { (pill, scope) ->
            val isActive = activeOwnershipScope == scope
            pill.background = ContextCompat.getDrawable(
                pill.context,
                if (isActive) R.drawable.bg_cpv_filter_pill_active
                else R.drawable.bg_cpv_filter_pill_inactive,
            )
            pill.setTextColor(Color.parseColor(if (isActive) "#FFFFFF" else "#475467"))
            pill.typeface = ResourcesCompat.getFont(
                pill.context,
                if (isActive) R.font.inter_semibold else R.font.inter_medium,
            )
        }
    }

    // Prefer the backend's granular (timestamp-merged) status so we can
    // separate Started / Picked Up / Completed; fall back to the collapsed
    // `status` bucket on legacy rows that don't carry rawStatus yet.
    private fun effStatus(visit: TodayVisit): String =
        (visit.rawStatus?.takeIf { it.isNotBlank() } ?: visit.status).lowercase(Locale.US)

    // Enroute (web: client_started + picked_up) — the client is on the way /
    // has been picked up from the CP but the vehicle hasn't reached the site.
    // "in-progress" is the collapsed-bucket fallback when rawStatus is absent.
    private fun isEnroute(s: String): Boolean = s in setOf(
        "client_started", "started", "in-progress", "in_progress", "picked_up",
    )

    // Onsite (web: on_site + on_counselling) — reached the plot / counselling.
    // "arrived"/"consulting" are collapsed-bucket fallbacks.
    private fun isOnsite(s: String): Boolean = s in setOf(
        "on_site", "on_counselling", "consulting", "arrived",
    )

    // Returning home (web: picked_from_site + dropped) — the return leg.
    private fun isReturningHome(s: String): Boolean = s in setOf(
        "picked_from_site", "dropped",
    )

    // Completed (web: completed only — "dropped" now lives in Returning home).
    private fun isCompleted(s: String): Boolean = s in setOf(
        "completed", "complete", "done", "closed",
    )

    private fun isCancelled(s: String): Boolean = s in setOf(
        "cancelled", "canceled", "no_show",
    )

    // A cancelled SV whose CP verification was REJECTED carries a
    // "[CP rejected by <name>] <reason>" marker in notes (set server-side).
    // These helpers let the list show a distinct "Rejected" badge + who/why
    // instead of a plain "Cancelled".
    private fun isRejectedSv(visit: TodayVisit): Boolean =
        visit.notes?.contains("[CP rejected") == true

    private fun rejectedBy(visit: TodayVisit): String? {
        val notes = visit.notes ?: return null
        val start = notes.lastIndexOf("[CP rejected by ")
        if (start < 0) return null
        val from = start + "[CP rejected by ".length
        val close = notes.indexOf(']', from)
        if (close < 0) return null
        return notes.substring(from, close).trim().takeIf { it.isNotBlank() }
    }

    private fun rejectionReason(visit: TodayVisit): String? {
        val notes = visit.notes ?: return null
        val idx = notes.lastIndexOf("[CP rejected")
        if (idx < 0) return null
        val close = notes.indexOf(']', idx)
        if (close < 0) return null
        return notes.substring(close + 1).trim().takeIf { it.isNotBlank() }
    }

    private fun isPostponed(s: String): Boolean = s == "postponed"

    // Still awaiting its trip — not enroute, onsite, returning, finished,
    // cancelled or postponed.
    private fun isScheduledState(s: String): Boolean =
        !isEnroute(s) && !isOnsite(s) && !isReturningHome(s) && !isCompleted(s) &&
            !isCancelled(s) && !isPostponed(s)

    // Fixed (web): the SV is fixed but still awaiting CP confirmation
    // (confirmationStatus == "pending"). Backend field is staged — until the
    // mfpl deploy it's null everywhere, so these rows sit under Scheduled.
    private fun isFixed(visit: TodayVisit): Boolean {
        val s = effStatus(visit)
        return isScheduledState(s) &&
            visit.confirmationStatus?.lowercase(Locale.US) == "pending"
    }

    private fun matchesFilter(visit: TodayVisit, filter: Filter): Boolean {
        val s = effStatus(visit)
        return when (filter) {
            Filter.ALL -> true
            Filter.FIXED -> isFixed(visit)
            // Scheduled = genuinely upcoming, minus the fixed-pending ones.
            Filter.SCHEDULED ->
                isScheduledState(s) && !isFixed(visit)
            Filter.ENROUTE -> isEnroute(s)
            Filter.ONSITE -> isOnsite(s)
            Filter.RETURNING_HOME -> isReturningHome(s)
            Filter.COMPLETED -> isCompleted(s)
            Filter.CANCELLED -> isCancelled(s)
            Filter.POSTPONED -> isPostponed(s)
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

        val query = SiteVisitServerQuery(
            fromDate = from,
            toDate = to,
            projectId = filterProject,
            telecallerStaffId = filterLmo,
            assignedStaffId = filterFieldStaff,
            status = currentFilter.takeUnless { it == Filter.ALL }?.name?.lowercase(Locale.US),
            search = searchQuery.ifBlank { null },
        )
        val generation = ++svLoadGeneration
        activeServerQuery = query
        svNextCursor = null
        svHasMore = false
        svTotal = null
        svLoadingMore = false
        svAutoFillPages = 0
        svPager.reset()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                launch {
                    runCatching {
                        geoApi.getSiteVisitFilterOptions(
                            session.bearerToken,
                            fromDate = query.fromDate,
                            toDate = query.toDate,
                        )
                    }.getOrNull()?.takeIf { it.success }?.let { response ->
                        if (generation == svLoadGeneration) filterOptions = response
                    }
                }
                val resp = requestSiteVisitPage(query, cursor = null)
                if (generation != svLoadGeneration) return@launch
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                hasLoadedOnce = true
                if (!resp.success) {
                    showLoadError(resp.error ?: "Failed to load site visits")
                    return@launch
                }
                // Exclude CP trip rows, but retain real siteVisits rows that
                // carry clientPlaceVisitId. A confirmed SV-cum-CP deliberately
                // keeps that back-reference to its verification CP; filtering
                // on the link id made every converted SV disappear here.
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
                    .filter(SiteVisitListRules::belongsInSiteVisits)
                    .sortedWith(
                        compareByDescending<TodayVisit> { it.creationTime ?: 0.0 }
                            .thenByDescending { it.scheduledDate }
                    )
                svNextCursor = resp.nextCursor
                svTotal = resp.total
                svHasMore = SiteVisitListRules.hasUsableNextPage(
                    resp.hasMore, null, svNextCursor, svTotal,
                )
                renderList()
                // The endpoint also carries legacy CP field visits. If those
                // dominate page one, progressively fill the first visible SV
                // window instead of leaving an admin with only a few cards.
                if (allVisits.size < svPager.pageSize && svHasMore) {
                    loadMoreSiteVisits()
                }
            } catch (e: Exception) {
                if (generation != svLoadGeneration) return@launch
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

    private fun loadMoreSiteVisits() {
        val query = activeServerQuery ?: return
        val cursor = svNextCursor?.takeIf { svHasMore && !svLoadingMore } ?: return
        val generation = svLoadGeneration
        svLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = requestSiteVisitPage(query, cursor)
                if (generation != svLoadGeneration || !resp.success) return@launch
                svAutoFillPages += 1
                val merged = LinkedHashMap<String, TodayVisit>()
                allVisits.forEach { merged[it.id] = it }
                resp.visits
                    .filter(SiteVisitListRules::belongsInSiteVisits)
                    .forEach { merged[it.id] = it }
                allVisits = merged.values.sortedWith(
                    compareByDescending<TodayVisit> { it.creationTime ?: 0.0 }
                        .thenByDescending { it.scheduledDate },
                )
                svNextCursor = resp.nextCursor
                svTotal = resp.total ?: svTotal
                svHasMore = SiteVisitListRules.hasUsableNextPage(
                    resp.hasMore, cursor, svNextCursor, svTotal,
                )
                renderList()
            } catch (_: Exception) {
                // Keep the pages already rendered. Pull-to-refresh remains the
                // explicit retry path and never blanks usable SV data.
            } finally {
                if (generation == svLoadGeneration) {
                    svLoadingMore = false
                    if (SiteVisitListRules.shouldAutoFill(
                            visitCount = allVisits.size,
                            pageSize = svPager.pageSize,
                            hasMore = svHasMore,
                            loadedExtraPages = svAutoFillPages,
                            maxExtraPages = MAX_AUTO_FILL_PAGES,
                        )
                    ) {
                        rootView?.post { loadMoreSiteVisits() }
                    }
                }
            }
        }
    }

    private suspend fun requestSiteVisitPage(
        query: SiteVisitServerQuery,
        cursor: String?,
    ) = geoApi.getMySiteVisits(
        token = session.bearerToken,
        fromDate = query.fromDate,
        toDate = query.toDate,
        projectId = query.projectId,
        telecallerStaffId = query.telecallerStaffId,
        assignedStaffId = query.assignedStaffId,
        status = query.status,
        search = query.search,
        cursor = cursor,
        pageSize = 200,
    )

    private data class SiteVisitServerQuery(
        val fromDate: String,
        val toDate: String,
        val projectId: String?,
        val telecallerStaffId: String?,
        val assignedStaffId: String?,
        val status: String?,
        val search: String?,
    )

    private fun renderList() {
        val root = rootView ?: return
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        val empty = root.findViewById<LinearLayout>(R.id.cpvEmptyState)
        val emptyTitle = root.findViewById<TextView>(R.id.tvCpvEmptyTitle)
        val emptySubtitle = root.findViewById<TextView>(R.id.tvCpvEmptySubtitle)
        list.removeAllViews()

        val visible = allVisits
            .filter { visit ->
                when (currentScope) {
                    CpVisitListScope.ALL -> true
                    CpVisitListScope.MY -> SiteVisitListRules.belongsToAny(
                        visit,
                        setOfNotNull(session.staffId).filter(String::isNotBlank).toSet(),
                    )
                    CpVisitListScope.TEAM -> SiteVisitListRules.belongsToAny(visit, directReportIds)
                }
            }
            .filter { matchesFilter(it, currentFilter) }
            .filter { inDateRange(it) }
            .filter { matchesFacet(filterProject, it.projectId, it.projectName ?: it.placeName, "project") }
            .filter { matchesFacet(filterLmo, it.lmoStaffId, it.lmoName, "lmo") }
            .filter { matchesFacet(filterFieldStaff, it.bdoStaffId, it.bdoName, "staff") }
            .filter { com.manjugroups.m_connect.util.VisitSearch.matches(it, searchQuery) }
        svVisibleCount = visible.size

        // Reset the scroll window whenever the filter / search / data changes.
        val windowCtx = "$currentScope|$currentFilter|$filterProject|$filterLmo|$filterFieldStaff|" +
            "$searchQuery|${System.identityHashCode(allVisits)}"
        if (windowCtx != svWindowCtx) {
            svWindowCtx = windowCtx
            svPager.reset()
        }

        if (visible.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            emptyTitle.text = when (currentFilter) {
                Filter.FIXED -> "No Fixed Visits"
                Filter.SCHEDULED -> "No Scheduled Visits"
                Filter.ENROUTE -> "No Enroute Visits"
                Filter.ONSITE -> "No Onsite Visits"
                Filter.RETURNING_HOME -> "No Returning-Home Visits"
                Filter.COMPLETED -> "No Completed Visits"
                Filter.CANCELLED -> "No Cancelled Visits"
                Filter.POSTPONED -> "No Postponed Visits"
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

        // BDO Details — the visit's OWN assigned BDO (visit.bdoName from the
        // backend), NOT the signed-in viewer. The old code hardcoded
        // session.userName, so every row showed the logged-in user as BDO even
        // when the actual BDO was someone else. Em-dash when the backend has
        // no BDO on the row (older rows before the mapper supplied it).
        bdoName.text = visit.bdoName?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "—"
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
            // A CP-rejected SV is cancelled under the hood — surface it as a
            // distinct "Rejected" (with who rejected it, if known) so the team
            // can see it wasn't a plain cancellation.
            isRejectedSv(visit) ->
                paintPill(
                    rejectedBy(visit)?.let { "Rejected · $it" } ?: "Rejected",
                    R.drawable.bg_sv_status_red, "#B42318", "#B42318",
                )
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
            isReturningHome(s) -> {
                val label = when (s) {
                    "picked_from_site" -> "Picked from site"
                    "dropped" -> "Dropped"
                    else -> "Returning home"
                }
                paintPill(label, R.drawable.bg_sv_status_orange, "#B54708", "#B54708")
            }
            isOnsite(s) -> {
                val label = when (s) {
                    "on_site" -> "On site"
                    "consulting", "on_counselling" -> "On counselling"
                    "arrived" -> "Reaching"
                    else -> "Onsite"
                }
                paintPill(label, R.drawable.bg_sv_status_orange, "#B54708", "#B54708")
            }
            isEnroute(s) -> {
                val label = if (s == "picked_up") "Picked up" else "Client started"
                paintPill(label, R.drawable.bg_sv_status_orange, "#B54708", "#B54708")
            }
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

    companion object {
        private const val MAX_AUTO_FILL_PAGES = 10
        private const val KEY_DATE = "date"
        private const val KEY_STATUS = "status"
        private const val KEY_PROJECT = "project"
        private const val KEY_LMO = "lmo"
        private const val KEY_FIELD_STAFF = "field_staff"
    }
}
