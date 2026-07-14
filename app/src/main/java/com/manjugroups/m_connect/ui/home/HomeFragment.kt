package com.manjugroups.m_connect.ui.home

import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHomeBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AssignedPlace
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val geoApi = com.manjugroups.m_connect.network.GeoTrackApi.create()
    // Overview / Management dashboard (replaces Today's Trip for holders of the
    // vpDashboard.view IAM key — the key predates the rename; not VP-only).
    private var overviewDashboardData: com.manjugroups.m_connect.network.OverviewDashboardResponse? = null
    private var overviewDashboardLoading = false
    private var lastDashSignature: String? = null
    // Dashboard date filter: null = today. Every tile re-fetches for this day.
    private var dashSelectedDate: String? = null

    private companion object {
        const val DASH_DATE_RESULT_KEY = "home_dash_date_result"
    }
    private val visitEmptySubtitle =
        "It looks like you don’t have any meetings scheduled at the moment. " +
            "This space will be updated as new meetings are added!"

    private var pendingEntryAnimation = true

    // True once today's visits have resolved at least once. Gates the
    // visit skeleton so a refresh / attendance update never blanks the
    // trip card back to a skeleton or the empty state.
    private var homeVisitsResolved = false
    // True once a real visits load cycle has begun (isVisitsLoading flipped
    // true). The flow starts at `false`, so without this guard the
    // collector's "load finished" branch fires on that initial replayed
    // false and renders the empty card before any load even starts — the
    // empty-flash-before-data the user kept seeing.
    private var homeLoadStarted = false
    // Deferred visit skeleton — only paints if the load is still running
    // after the grace period, so fast/cached loads never flash it.
    private var pendingVisitSkeleton: Runnable? = null
    private val visitSkeletonDelayMs = 200L

    private var tooltipAnimator: android.animation.Animator? = null
    private var handleAnimator: android.animation.Animator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.homeHeader.setup(this, onDesignationChanged = {
            setupRoleAdaptiveView()
            (viewModel.uiState.value as? HomeUiState.Loaded)?.let { state ->
                renderVisitCard(state)
            }
        })
        setupPullToRefresh()
        if (session.hasPermission("vpDashboard.view")) {
            binding.btnDashDateFilter.setOnClickListener { showDashDatePicker() }
            parentFragmentManager.setFragmentResultListener(
                DASH_DATE_RESULT_KEY, viewLifecycleOwner
            ) { _, bundle ->
                val picked = bundle.getString(
                    com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_FROM
                ).orEmpty()
                if (picked.isBlank()) return@setFragmentResultListener
                dashSelectedDate = if (picked == indiaToday()) null else picked
                // Counts for another day may coincidentally match — clear the
                // cached signature so the header swap is never skipped.
                lastDashSignature = null
                applyDashHeader()
                loadOverviewDashboard(force = true)
            }
            loadOverviewDashboard()
        }
        setupHomeScrollAnimation()
        collectState()
        collectEvents()
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        loadUnreadNotifications()
        startBannerAnimation()

        setupRoleAdaptiveView()
        setupDriverTabs()

        setFragmentResultListener(DriverStartTripBottomSheet.RESULT_KEY) { _, bundle ->
            val success = bundle.getBoolean("success")
            if (success) {
                val visitId = bundle.getString("visitId").orEmpty()
                viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
                val state = viewModel.uiState.value
                if (state is HomeUiState.Loaded) {
                    state.todayVisits.firstOrNull { it.id == visitId }?.let { visit ->
                        val startedVisit = visit.copy(status = "in-progress")
                        openTripNavigationForVisit(startedVisit)
                    }
                }
            }
        }

        // When the CP/SV outcome sheet (opened straight from the home
        // card) saves an outcome, refresh today's trips so the card flips
        // to its Completed state without the user re-entering the screen.
        setFragmentResultListener(CompleteCpVisitBottomSheet.RESULT_KEY) { _, _ ->
            viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        }
        // The edge-drag QR panel opens the Front-Desk check-in scanner —
        // only wire it for staff who hold a frontdesk permission (web
        // parity: /frontdesk is gated the same way). Everyone else keeps
        // an artifact-free home edge.
        if (listOf("frontdesk.view", "frontdesk.checkin", "frontdesk.invite")
                .any { session.hasPermission(it) }
        ) {
            setupEdgeDragQr()
        }
    }

    private fun setupPullToRefresh() {
        // Fire the same loads the screen does on first open so a pull
        // refreshes attendance, today's visits and notifications in
        // one gesture. The spinner is dismissed in collectState() when
        // the next "loaded" state lands.
        binding.homeRefresh.setupPullToRefresh {
            viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
            loadUnreadNotifications()
        }
    }

    /**
     * Collapsing-header effect — same pattern as App Library / Attendance:
     *  - the "Plan, Visit & Achieve" banner (cardWorkSummary) fades and
     *    its layout height shrinks to 0 so the header collapses to just
     *    the profile row (avatar + name + bell) when scrolled.
     *  - the profile row stays at full opacity and full Y position so it
     *    never crosses into the status bar.
     * Effect saturates within ~140dp of scroll.
     */
    /**
     * "Panel slides up over fixed header" scroll effect.
     * Home's blue header (with profile row + banner) stays anchored at
     * full size and full opacity; the SwipeRefresh panel translates up
     * over it as the user scrolls, eventually fully overlaying the
     * blue. Header's bottom corners straighten 24dp → 0dp in step with
     * the slide.
     */
    private fun setupHomeScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxCornerRadiusPx = 24f * density
        val headerBg = binding.homeHeader.getHeaderBinding().homeHeaderContainer
            .applyShrinkableBlueHeaderBackground()
        headerBg.setBottomCornerRadius(maxCornerRadiusPx)

        // The white card with rounded TOP corners is the
        // `whiteContentArea` — which lives INSIDE the NestedScrollView,
        // so it scrolls up at exactly 1× rate as the user scrolls (no
        // parallax, no shrinking — single scroll source). The panel
        // (SwipeRefreshLayout) itself is transparent.
        //
        // The ancestors all set clipChildren=false in XML so the
        // rounded top edge can draw OUTSIDE the panel's bounds, into
        // the blue header's area — that's how the white visually
        // "overlays" the blue as it scrolls up.
        val whiteCardBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            // Light grey (page-bg tone) so the pure-white trip cards
            // inside read as floating on a softer surface, matching the
            // reference design.
            setColor(android.graphics.Color.parseColor("#F1F3F8"))
            cornerRadii = floatArrayOf(
                maxCornerRadiusPx, maxCornerRadiusPx, // top-left
                maxCornerRadiusPx, maxCornerRadiusPx, // top-right
                0f, 0f,                                // bottom-right
                0f, 0f,                                // bottom-left
            )
        }
        binding.whiteContentArea.background = whiteCardBg

        binding.homeContent.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(false)
            } else if (scrollY <= 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(true)
            }
        })
    }

    private fun startBannerAnimation() {
        val anim = binding.homeHeader.getHeaderBinding().ivBannerAnimation.drawable as? android.graphics.drawable.AnimationDrawable
        anim?.start()
    }

    override fun onResume() {
        super.onResume()
        // Defensive: restore tab bar in case a child fragment hid it, unless onboarding or QR panel is visible.
        val showTabBar = session.hasSeenEdgeQrTooltip && 
                (_binding == null || binding.edgeQrPanel.visibility != android.view.View.VISIBLE)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(showTabBar)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            Color.parseColor("#0B61CA"),
            false,
            fullBleed = true
        )
        loadUnreadNotifications()
        // Refresh attendance and visits — covers biometric punches and returning from trips.
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        // Pull the staff record so a profile photo updated from web/iOS
        // appears here too. ProfilePhotos.resolve rebuilds the serve URL
        // from the current BASE_URL on every render, so cached photos
        // never stick to an old domain.
        binding.homeHeader.setup(this, onDesignationChanged = {
            setupRoleAdaptiveView()
            (viewModel.uiState.value as? HomeUiState.Loaded)?.let { state ->
                renderVisitCard(state)
            }
        })
        // Replay the stagger when returning to the Home tab (either from a child
        // fragment via back, or after pop-back from another tab via show/hide).
        if (_binding != null && binding.homeStickyColumn.visibility == View.VISIBLE) {
            // Posted lambdas run a frame later — guard against view teardown.
            binding.homeContent.post { _binding?.homeHeader?.playEntryAnimation() }
        }
        startBannerAnimation()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            _binding?.homeHeader?.stopFloatingAnimation()
        } else if (_binding != null &&
            binding.homeStickyColumn.visibility == View.VISIBLE) {
            binding.homeContent.post { _binding?.homeHeader?.playEntryAnimation() }
        }
    }



    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HomeUiState.Loading -> {
                            // Don't paint over the skeleton while a pull-refresh
                            // is in flight — the swipe spinner already signals
                            // "loading" so the full-screen skeleton would
                            // double up. The skeleton is only useful for the
                            // initial open.
                            if (!binding.homeRefresh.isRefreshing) {
                                SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
                                // The sticky header + scroll content are now siblings
                                // under homeStickyColumn — hide the WHOLE column so the
                                // skeleton overlay doesn't paint over a half-loaded
                                // header.
                                binding.homeStickyColumn.visibility = View.GONE
                            }
                        }

                        is HomeUiState.Loaded -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.homeRefresh.dismissRefresh()
                            binding.homeStickyColumn.visibility = View.VISIBLE
                            renderSummary()
                            // Paint the trip card only once visits have
                            // resolved (or we already have some to show) AND
                            // we're not mid visits-fetch. Otherwise a
                            // Loaded-with-empty-trips state during the
                            // initial load would flash the empty view before
                            // the visit skeleton appears.
                            val hasVisits = state.todayVisits.any { it.status != "cancelled" }
                            // Dashboard users get the static Overview grid,
                            // which doesn't depend on the visits fetch — render
                            // it immediately instead of waiting on that call.
                            if (session.hasPermission("vpDashboard.view") ||
                                ((homeVisitsResolved || hasVisits) &&
                                    !viewModel.isVisitsLoading.value)
                            ) {
                                renderVisitCard(state)
                            }
                            if (pendingEntryAnimation) {
                                pendingEntryAnimation = false
                                // The posted lambda runs a frame later — the view can
                                // be destroyed by then (fast tab switch), so guard.
                                binding.homeContent.post { _binding?.homeHeader?.playEntryAnimation() }
                            }
                        }

                        is HomeUiState.Error -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.homeRefresh.dismissRefresh()
                            binding.homeStickyColumn.visibility = View.VISIBLE
                            binding.homeHeader.setBannerSubtitle("Today task & presence activity")
                            binding.tvVisitCountBadge.visibility = View.GONE
                            binding.visitListContent.visibility = View.GONE
                            binding.visitEmptyContent.visibility = View.VISIBLE
                            binding.tvVisitEmptyTitle.text = "No Trips Available"
                            binding.tvVisitEmptySubtitle.text = visitEmptySubtitle
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isVisitsLoading.collect { loading ->
                    val hasVisits = (viewModel.uiState.value as? HomeUiState.Loaded)
                        ?.todayVisits?.any { it.status != "cancelled" } == true
                    if (loading) {
                        homeLoadStarted = true
                        // Arm the deferred skeleton — first load, nothing
                        // to show yet. If the load resolves within the
                        // grace period the timer is cancelled and no
                        // skeleton flashes.
                        if (!homeVisitsResolved && !hasVisits) {
                            scheduleVisitSkeleton()
                        }
                    } else if (homeLoadStarted) {
                        // A REAL load cycle finished — now it's safe to
                        // resolve to the real cards (or the empty state).
                        homeVisitsResolved = true
                        (viewModel.uiState.value as? HomeUiState.Loaded)?.let(::renderVisitCard)
                    } else if (hasVisits) {
                        // Initial replayed `false` but we already have
                        // cached trips — show them now. Don't mark resolved
                        // or render the empty state: a real load is still
                        // coming, and an empty cache here must wait for it
                        // rather than flashing "No Trips".
                        (viewModel.uiState.value as? HomeUiState.Loaded)?.let(::renderVisitCard)
                    }
                }
            }
        }
    }

    private var visitSkeletonAnimating = false

    /**
     * Arms the deferred visit skeleton. No-op if already showing/armed or
     * already resolved. It only paints if [visitSkeletonDelayMs] elapses
     * with the load still running and nothing to show — so quick loads
     * never flash it.
     */
    private fun scheduleVisitSkeleton() {
        if (_binding == null || homeVisitsResolved) return
        if (binding.visitSkeletonContainer.visibility == View.VISIBLE) return
        if (pendingVisitSkeleton != null) return
        val r = Runnable {
            pendingVisitSkeleton = null
            val hasVisits = (viewModel.uiState.value as? HomeUiState.Loaded)
                ?.todayVisits?.any { it.status != "cancelled" } == true
            if (_binding != null && !homeVisitsResolved && !hasVisits &&
                viewModel.isVisitsLoading.value
            ) {
                setVisitSkeletonVisible(true)
            }
        }
        pendingVisitSkeleton = r
        binding.root.postDelayed(r, visitSkeletonDelayMs)
    }

    private fun cancelPendingVisitSkeleton() {
        pendingVisitSkeleton?.let { _binding?.root?.removeCallbacks(it) }
        pendingVisitSkeleton = null
    }

    private fun setVisitSkeletonVisible(visible: Boolean) {
        val skeleton = binding.visitSkeletonContainer
        if (visible) {
            binding.visitListContent.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.GONE
            skeleton.visibility = View.VISIBLE
            if (!visitSkeletonAnimating) {
                val pulse = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(), R.anim.skeleton_pulse
                )
                forEachLeafBlock(skeleton) { it.startAnimation(pulse) }
                visitSkeletonAnimating = true
            }
        } else {
            if (visitSkeletonAnimating) {
                forEachLeafBlock(skeleton) { it.clearAnimation() }
                visitSkeletonAnimating = false
            }
            skeleton.visibility = View.GONE
        }
    }

    private fun forEachLeafBlock(group: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) forEachLeafBlock(child, action)
            else action(child)
        }
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.punchEvent.collect { event ->
                    // Success is already confirmed by the "Clockout/Clock-in
                    // Successful" sheet — only surface errors as a toast.
                    if (event is PunchEvent.Error) {
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderSummary() {
        // As per the provided frames, the banner text is static: "Plan, Visit & Achieve"
        // But we can update the subtitle or other elements if needed.
        // For now, keeping it consistent with the image.
    }

    // Signature of the last-rendered visit list. renderVisitCard is invoked
    // from ~7 different state collectors, and each call re-inflates EVERY visit
    // card on the main thread. Re-inflating unchanged cards repeatedly pegged
    // the UI thread and ANR'd the Home screen ("crashing while loading"). We
    // skip the re-inflation when nothing visible changed.
    private var lastVisitRenderSignature: String? = null

    private fun renderVisitCard(state: HomeUiState.Loaded) {
        // We have a definitive answer — drop any armed/showing skeleton.
        cancelPendingVisitSkeleton()
        setVisitSkeletonVisible(false)
        // VP / Management dashboard replaces the Today's Trip list for anyone
        // with vpDashboard.view (super-admins included). Its numbers come from
        // the dashboard endpoint, not the visits flow, so short-circuit here
        // before any trip rendering / empty-state logic.
        if (session.hasPermission("vpDashboard.view")) {
            applyDashHeader()
            // The globe icon belongs to the "Today's Trip" view, not the KPI
            // dashboard — hide it so the header reads cleanly, and surface
            // the date filter in its place.
            binding.ivVisitTitleGlobe.visibility = View.GONE
            binding.tvVisitCountBadge.visibility = View.GONE
            binding.btnDashDateFilter.visibility = View.VISIBLE
            binding.visitListContent.visibility = View.VISIBLE
            binding.visitEmptyContent.visibility = View.GONE
            renderOverviewDashboard()
            return
        }
        // Home shows today's visits only.
        val unfilteredVisits = state.todayVisits.filter { it.status != "cancelled" }
        val visits = if (session.isDriverMode) {
            when (selectedTab) {
                "upcoming" -> unfilteredVisits.filter {
                    val s = it.status.lowercase(Locale.getDefault())
                    s !in setOf("completed", "complete", "done", "closed")
                }
                "completed" -> unfilteredVisits.filter {
                    val s = it.status.lowercase(Locale.getDefault())
                    s in setOf("completed", "complete", "done", "closed")
                }
                else -> unfilteredVisits
            }
        } else {
            unfilteredVisits
        }
        val displayCount = visits.size

        if (displayCount > 0) {
            binding.tvVisitCountBadge.visibility = View.VISIBLE
            binding.tvVisitCountBadge.text = displayCount.toString()
        } else {
            binding.tvVisitCountBadge.visibility = View.GONE
        }

        if (displayCount == 0) {
            binding.visitListContent.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.VISIBLE
            // Match Frame 4 text exactly
            binding.tvVisitEmptyTitle.text = "No Trips Available"
            binding.tvVisitEmptySubtitle.text = "It looks like you don't have any meetings scheduled at the moment.\nThis space will be updated as new meetings are added!"
            return
        }

        binding.visitListContent.visibility = View.VISIBLE
        binding.visitEmptyContent.visibility = View.GONE

        // Super admins see the WHOLE company's trips (100s). Inflating a card
        // per trip ANRs Home and lags/crashes on every return to it, so show
        // compact CP / SV / Fleet counts instead — no per-visit inflation.
        if (session.isAdmin) {
            renderAdminTripSummary(visits)
            return
        }

        // Only re-inflate when the visible list actually changed. The childCount
        // check also forces a rebuild after view recreation (fresh empty
        // container) even if the signature happens to match.
        val signature = buildString {
            append(state.hasOpenSession).append('|').append(selectedTab).append('|')
            visits.forEach { append(it.id).append(':').append(it.status).append(';') }
        }
        if (signature == lastVisitRenderSignature &&
            binding.visitListContent.childCount == displayCount
        ) {
            return
        }
        lastVisitRenderSignature = signature

        binding.visitListContent.removeAllViews()

        visits.forEachIndexed { index, visit ->
            val itemView = createVisitItem(visit, index, displayCount, state.hasOpenSession)
            binding.visitListContent.addView(itemView)
        }
    }

    /**
     * Admin/super-admin view of Today's Trip: three compact CP / SV / Fleet
     * count cards instead of a card per (100s of) trips. Only the counting
     * loop runs — no layout inflation — so Home no longer janks or crashes for
     * admins, and returning to it is instant.
     */
    private fun renderAdminTripSummary(visits: List<TodayVisit>) {
        var cp = 0
        var sv = 0
        var fleet = 0
        visits.forEach { v ->
            when {
                v.tripType?.lowercase(Locale.getDefault()) == "fleet" -> fleet++
                v.cpVisit != null || v.visitCategory == "direct_cp" ||
                    v.visitCategory == "sv_cum_cp" || v.tripType == "client_place" -> cp++
                else -> sv++
            }
        }
        // renderVisitCard fires from ~7 flows; rebuild only when a count moves.
        val signature = "admin|$cp|$sv|$fleet"
        if (signature == lastVisitRenderSignature && binding.visitListContent.childCount == 1) return
        lastVisitRenderSignature = signature

        val ctx = requireContext()
        binding.visitListContent.removeAllViews()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(adminCountCard(ctx, "CP Visits", cp, "#0B61CA", 0))
        row.addView(adminCountCard(ctx, "Site Visits", sv, "#7C3AED", dpx(8)))
        row.addView(adminCountCard(ctx, "Fleet", fleet, "#059669", dpx(8)))
        binding.visitListContent.addView(row)
    }

    private fun adminCountCard(
        ctx: android.content.Context, label: String, count: Int, colorHex: String, startMargin: Int,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dpx(10), dpx(18), dpx(10), dpx(18))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = startMargin }
        }
        card.addView(TextView(ctx).apply {
            text = count.toString()
            textSize = 26f
            setTextColor(Color.parseColor(colorHex))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dpx(4), 0, 0)
        })
        return card
    }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── VP / Management Dashboard ───────────────────────────────────────────

    private fun loadOverviewDashboard(force: Boolean = false) {
        if (overviewDashboardLoading && !force) return
        overviewDashboardLoading = true
        val requestedDate = dashSelectedDate
        viewLifecycleOwner.lifecycleScope.launch {
            // Prefer the company-wide aggregate route; when it isn't deployed
            // (404), fall back to counting what the live per-screen endpoints
            // already expose (CP + Site visits for the selected day).
            val resp = runCatching {
                api.getOverviewDashboard(session.bearerToken, requestedDate)
            }.getOrNull()
            val data = if (resp?.success == true) resp
                else runCatching { computeOverviewFallback(requestedDate ?: indiaToday()) }.getOrNull()
            overviewDashboardLoading = false
            if (view == null) return@launch
            // The user picked a different date while this was in flight —
            // that newer load owns the render; drop this stale result.
            if (requestedDate != dashSelectedDate) return@launch
            if (data != null) {
                overviewDashboardData = data
                renderOverviewDashboard()
            }
        }
    }

    /** Date-filter picker for the dashboard: every tile re-fetches for the
     *  picked day; picking today returns to the live "Today's Overview".
     *  Uses the app's own calendar sheet (single-select mode), not the stock
     *  Android dialog. */
    private fun showDashDatePicker() {
        val initial = dashSelectedDate ?: indiaToday()
        com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.newInstance(
            title = "Dashboard Date",
            subtitle = "Pick a day to view its overview",
            initialFrom = initial,
            initialTo = initial,
            resultKey = DASH_DATE_RESULT_KEY,
            singleSelect = true,
        ).show(parentFragmentManager, "dash_date_picker")
    }

    /** Header + date-chip copy for the current dashboard date. */
    private fun applyDashHeader() {
        val day = dashSelectedDate
        binding.tvVisitSectionTitle.text =
            if (day == null) "Today's Overview" else "Overview"
        binding.tvDashDateFilter.text = if (day == null) "Today" else prettyDashDate(day)
    }

    private fun prettyDashDate(d: String): String = runCatching {
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
            .format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!)
    }.getOrDefault(d)

    /** India-local yyyy-MM-dd — matches how a visit's scheduledDate is stored. */
    private fun indiaToday(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date())

    /**
     * Fallback dashboard data when the `/api/dashboard/vp` aggregate isn't
     * deployed. Counts the selected day's metrics from the same live endpoints
     * the individual screens use (admins/viewAll get the company-wide pool):
     *  - CP + Site visits  → geoApi.getMyMarketingCpVisits / getMySiteVisits
     *  - Present           → api.getAllAttendance (approvedAttendance)
     *  - Bookings          → api.listMyBookings (excl draft/cancelled)
     * Incoming/Outbound calls, Collections, and Registrations have no clean
     * live company-wide source (calls + registrations live only behind the
     * un-deployed dashboard routes; the for-accounts collections endpoint is a
     * pending-verification queue, not the approved total) — those stay at -1
     * and render as "–". Returns null only if every call fails.
     */
    private suspend fun computeOverviewFallback(day: String): com.manjugroups.m_connect.network.OverviewDashboardResponse? {
        val cp = runCatching { geoApi.getMyMarketingCpVisits(session.bearerToken, day, day) }.getOrNull()
        val sv = runCatching { geoApi.getMySiteVisits(session.bearerToken, day, day) }.getOrNull()
        val att = runCatching { api.getAllAttendance(session.bearerToken, day, day) }.getOrNull()
        val bk = runCatching { api.listMyBookings(session.bearerToken) }.getOrNull()

        val cpOk = cp?.success == true
        val svOk = sv?.success == true
        val attOk = att?.success == true
        val bkOk = bk?.success == true
        if (!cpOk && !svOk && !attOk && !bkOk) return null

        val cancelled = setOf("cancelled", "canceled", "cancel")
        val completed = setOf("completed", "complete", "done", "closed")
        val present = setOf("present", "half-day", "half day", "halfday")
        fun onToday(s: String?) = s?.startsWith(day) == true
        fun lc(s: String?) = (s ?: "").lowercase(java.util.Locale.US)

        val cpRows = cp?.visits.orEmpty().filter { onToday(it.scheduledDate) }
        val svRows = sv?.visits.orEmpty().filter { onToday(it.scheduledDate) }
        val attRows = att?.records.orEmpty().filter { onToday(it.date) }
        val bkRows = bk?.bookings.orEmpty()
            .filter { onToday(it.bookingDate) && lc(it.status) !in cancelled && lc(it.status) != "draft" }

        return com.manjugroups.m_connect.network.OverviewDashboardResponse(
            success = true,
            date = day,
            present = if (attOk) attRows.count { lc(it.approvedAttendance) in present } else -1,
            absent = if (attOk) attRows.count { lc(it.approvedAttendance) == "absent" } else -1,
            incomingCalls = -1, outboundCalls = -1,
            cpVisitsFixed = if (cpOk) cpRows.count { lc(it.status) !in cancelled } else -1,
            cpVisitsCompleted = if (cpOk) cpRows.count { lc(it.status) in completed } else -1,
            svVisitsFixed = if (svOk) svRows.count { lc(it.status) !in cancelled } else -1,
            svVisitsCompleted = if (svOk) svRows.count { lc(it.status) in completed } else -1,
            collectionTotal = -1.0,
            collectionCount = -1,
            bookingCount = if (bkOk) bkRows.size else -1,
            registrationCount = -1,
        )
    }

    private data class DashTile(
        val iconRes: Int, val label: String, val primary: String, val secondary: String?,
        // colorHex = icon (ramp 600), bgHex = tint (ramp 50), deepHex = chip text (ramp 900)
        val colorHex: String, val bgHex: String, val deepHex: String, val onTap: () -> Unit,
    )

    /** Grid of KPI tiles (2 per row) driven by the dashboard endpoint. Each
     *  tile shows a count and opens its detail screen. Guarded by a value
     *  signature so the ~7 render flows don't rebuild it on every emit. */
    private fun renderOverviewDashboard() {
        val ctx = context ?: return
        val d = overviewDashboardData
        val signature = if (d == null) "loading" else buildString {
            append("vp|").append(dashSelectedDate ?: "today").append('|')
                .append(d.totalStaff).append('|')
                .append(d.present).append('|').append(d.absent).append('|')
                .append(d.cpVisitsFixed).append('|').append(d.cpVisitsCompleted).append('|')
                .append(d.svVisitsFixed).append('|').append(d.svVisitsCompleted).append('|')
                .append(d.incomingCalls).append('|').append(d.outboundCalls).append('|')
                .append(d.collectionTotal).append('|').append(d.bookingCount).append('|')
                .append(d.registrationCount)
        }
        if (signature == lastDashSignature && binding.visitListContent.childCount > 0) return
        lastDashSignature = signature

        val tiles = if (d == null) {
            // No data yet (endpoint pending on prod): still show the full
            // coloured design with "–" placeholders, and keep every tile
            // tappable so the redirects work before the counts land.
            listOf(
                DashTile(R.drawable.ic_chat_users, "Present", "–", null, "#0F6E56", "#E1F5EE", "#04342C") {
                    openScreen(com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment())
                },
                DashTile(R.drawable.ic_cp_staff, "CP Visits", "–", null, "#185FA5", "#E6F1FB", "#042C53") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.CpVisitsFragment())
                },
                DashTile(R.drawable.ic_map_pin, "Site Visits", "–", null, "#534AB7", "#EEEDFE", "#26215C") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment())
                },
                DashTile(R.drawable.ic_chat_phone, "Incoming Calls", "–", null, "#3B6D11", "#EAF3DE", "#173404") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("INBOUND", "Incoming Calls"))
                },
                DashTile(R.drawable.ic_custom_phone, "Outbound Calls", "–", null, "#993C1D", "#FAECE7", "#4A1B0C") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("OUTBOUND", "Outbound Calls"))
                },
                DashTile(R.drawable.ic_cash_bills, "Collections", "–", null, "#854F0B", "#FAEEDA", "#412402") {
                    openScreen(com.manjugroups.m_connect.ui.library.collections.CollectionsFragment())
                },
                DashTile(R.drawable.ic_booking_calendar, "Bookings", "–", null, "#993556", "#FBEAF0", "#4B1528") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.bookings.BookingsFragment())
                },
                DashTile(R.drawable.ic_loan_document, "Registrations", "–", null, "#A32D2D", "#FCEBEB", "#501313") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.RegistrationsFragment.newInstance())
                },
            )
        } else {
            val inr = java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN"))
            // -1 means "not available from a live endpoint yet" → show "–".
            fun nz(v: Int): String = if (v < 0) "–" else v.toString()
            fun sec(v: Int, suffix: String): String? = if (v < 0) null else "$v $suffix"
            val collText = if (d.collectionTotal < 0) "–" else "₹${inr.format(d.collectionTotal.toLong())}"
            // "173 present of 1000 staff" — the aggregate route reports the
            // company-wide active headcount; the fallback path doesn't, so
            // it degrades to the plain absent count.
            val presentSec = when {
                d.present < 0 -> null
                d.totalStaff > 0 -> "of ${d.totalStaff} · ${d.absent} absent"
                else -> sec(d.absent, "absent")
            }
            listOf(
                DashTile(R.drawable.ic_chat_users, "Present", nz(d.present), presentSec, "#0F6E56", "#E1F5EE", "#04342C") {
                    openScreen(com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment())
                },
                DashTile(R.drawable.ic_cp_staff, "CP Visits", nz(d.cpVisitsFixed), sec(d.cpVisitsCompleted, "completed"), "#185FA5", "#E6F1FB", "#042C53") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.CpVisitsFragment())
                },
                DashTile(R.drawable.ic_map_pin, "Site Visits", nz(d.svVisitsFixed), sec(d.svVisitsCompleted, "completed"), "#534AB7", "#EEEDFE", "#26215C") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment())
                },
                DashTile(R.drawable.ic_chat_phone, "Incoming Calls", nz(d.incomingCalls), null, "#3B6D11", "#EAF3DE", "#173404") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("INBOUND", "Incoming Calls"))
                },
                DashTile(R.drawable.ic_custom_phone, "Outbound Calls", nz(d.outboundCalls), null, "#993C1D", "#FAECE7", "#4A1B0C") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("OUTBOUND", "Outbound Calls"))
                },
                DashTile(R.drawable.ic_cash_bills, "Collections", collText, sec(d.collectionCount, "receipts"), "#854F0B", "#FAEEDA", "#412402") {
                    openScreen(com.manjugroups.m_connect.ui.library.collections.CollectionsFragment())
                },
                DashTile(R.drawable.ic_booking_calendar, "Bookings", nz(d.bookingCount), null, "#993556", "#FBEAF0", "#4B1528") {
                    openScreen(com.manjugroups.m_connect.ui.marketing.bookings.BookingsFragment())
                },
                DashTile(R.drawable.ic_loan_document, "Registrations", nz(d.registrationCount), null, "#A32D2D", "#FCEBEB", "#501313") {
                    openScreen(com.manjugroups.m_connect.ui.dashboard.RegistrationsFragment.newInstance())
                },
            )
        }

        // The tile grid above is superseded by the segmented Overview mock
        // (Marketing | HR) — kept compiled (tiles referenced below is unused
        // at runtime) until the new cards are wired to live data.
        if (tiles.isEmpty()) return
        renderOverviewMock(ctx)
    }

    /** One card of the segmented Overview grid (mock data for now). */
    private data class OverviewCard(
        val label: String,
        val value: String,
        val pill: String,
        val variant: String, // blue | green | red | orange | neutral
        val iconRes: Int,
        val big: Boolean,
        // 3D render (extracted from the design sheet) shown as the card art.
        val artRes: Int? = null,
        val onTap: () -> Unit,
    )

    private var overviewHrTab = true

    /** Mock-parity "Overview" section: title + Marketing/HR toggle + cards.
     *  Values are static until the section is wired to live endpoints. */
    private fun renderOverviewMock(ctx: android.content.Context) {
        // Idempotent per view: skip re-inflating on repeated renderVisitCard
        // calls, but re-inflate after the view is recreated (tab return) —
        // a persistent boolean flag used to leave the section blank on
        // return because the fresh visitListContent had nothing in it.
        if (binding.visitListContent.findViewById<View>(R.id.overviewCardsContainer) != null) return

        // The mock's white sheet carries its own big "Overview" title, so the
        // old section header + date pill disappear for dashboard users.
        binding.tvVisitSectionTitle.visibility = View.GONE
        binding.ivVisitTitleGlobe.visibility = View.GONE
        binding.tvVisitCountBadge.visibility = View.GONE
        binding.btnDashDateFilter.visibility = View.GONE

        binding.visitListContent.removeAllViews()
        val section = layoutInflater.inflate(
            R.layout.home_overview_cards, binding.visitListContent, false,
        )
        binding.visitListContent.addView(section)

        val tabMarketing = section.findViewById<TextView>(R.id.btnTabMarketing)
        val tabHr = section.findViewById<TextView>(R.id.btnTabHr)
        val container = section.findViewById<LinearLayout>(R.id.overviewCardsContainer)

        fun applyTab() {
            val thumb = R.drawable.bg_overview_toggle_thumb
            tabHr.background = if (overviewHrTab) requireContext().getDrawable(thumb) else null
            tabHr.setTextColor(Color.parseColor(if (overviewHrTab) "#FFFFFF" else "#344054"))
            tabMarketing.background = if (!overviewHrTab) requireContext().getDrawable(thumb) else null
            tabMarketing.setTextColor(Color.parseColor(if (!overviewHrTab) "#FFFFFF" else "#344054"))
            renderOverviewCards(ctx, container, if (overviewHrTab) hrOverviewCards() else marketingOverviewCards())
        }
        tabHr.setOnClickListener { if (!overviewHrTab) { overviewHrTab = true; applyTab() } }
        tabMarketing.setOnClickListener { if (overviewHrTab) { overviewHrTab = false; applyTab() } }
        applyTab()
    }

    private fun hrOverviewCards(): List<OverviewCard> = listOf(
        OverviewCard("Total Active Staff", "276", "All Departments", "blue", R.drawable.ic_chat_users, true, R.drawable.img_stat_staff) {
            openScreen(com.manjugroups.m_connect.ui.hr.HrStaffFragment())
        },
        OverviewCard("Present", "210", "76% of Total", "green", R.drawable.ic_check_circle, true, R.drawable.img_stat_present) {
            openScreen(com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment())
        },
        OverviewCard("Absent", "51", "High Today", "red", R.drawable.ic_auth_user, true, R.drawable.img_stat_absent) {
            openScreen(com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment())
        },
        OverviewCard("Leave", "15", "Approved", "orange", R.drawable.ic_booking_calendar, true, R.drawable.img_stat_leave) {
            openScreen(com.manjugroups.m_connect.ui.hr.LeavesFragment())
        },
        OverviewCard("Week Off", "0", "No Data", "neutral", R.drawable.ic_calendar_days, false, R.drawable.img_stat_weekoff) {},
        OverviewCard("Permission", "0", "No Requests", "neutral", R.drawable.ic_clock_bold, false, R.drawable.img_stat_permission) {
            openScreen(com.manjugroups.m_connect.ui.hr.PermissionsFragment())
        },
        OverviewCard("WFH Approved", "0", "No Data", "neutral", R.drawable.ic_apps_front_desk, false, R.drawable.img_stat_wfh) {},
    )

    private fun marketingOverviewCards(): List<OverviewCard> = listOf(
        OverviewCard("Total Calls", "118", "Today", "blue", R.drawable.ic_chat_phone, true, R.drawable.img_stat_calls_total) {
            openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance(null, "Total Calls"))
        },
        OverviewCard("Incoming", "71", "60% of Total", "green", R.drawable.ic_chat_phone, true, R.drawable.img_stat_calls_in) {
            openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("INBOUND", "Incoming Calls"))
        },
        OverviewCard("Outgoing", "47", "40% of Total", "orange", R.drawable.ic_custom_phone, true, R.drawable.img_stat_calls_out) {
            openScreen(com.manjugroups.m_connect.ui.dashboard.CallsReportFragment.newInstance("OUTBOUND", "Outbound Calls"))
        },
        OverviewCard("CP / Bookings", "6 / 0", "Today", "blue", R.drawable.ic_cp_staff, true, R.drawable.img_stat_cp_bookings) {
            openScreen(com.manjugroups.m_connect.ui.marketing.CpVisitsFragment())
        },
        OverviewCard("Hot", "0", "No Data", "neutral", R.drawable.ic_home_summary_star, false, R.drawable.img_stat_hot) {},
        OverviewCard("Warm", "12", "Leads", "neutral", R.drawable.ic_clock_custom, false, R.drawable.img_stat_warm) {},
        OverviewCard("Cold", "29", "Leads", "neutral", R.drawable.ic_attendance_search, false, R.drawable.img_stat_cold) {},
        OverviewCard("SV Fixed", "0", "No Data", "neutral", R.drawable.ic_map_pin, false, R.drawable.img_stat_search) {
            openScreen(com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment())
        },
    )

    private fun renderOverviewCards(
        ctx: android.content.Context,
        container: LinearLayout,
        cards: List<OverviewCard>,
    ) {
        container.removeAllViews()
        val big = cards.filter { it.big }
        val small = cards.filter { !it.big }

        var i = 0
        while (i < big.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (i > 0) topMargin = dpx(10) }
            }
            row.addView(bigOverviewCard(ctx, big[i], 0))
            if (i + 1 < big.size) row.addView(bigOverviewCard(ctx, big[i + 1], dpx(10)))
            container.addView(row)
            i += 2
        }

        if (small.isNotEmpty()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dpx(10) }
            }
            small.forEachIndexed { index, card ->
                val view = layoutInflater.inflate(R.layout.item_overview_stat_small, row, false)
                view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { if (index > 0) marginStart = dpx(8) }
                view.findViewById<TextView>(R.id.tvSmallLabel).text = card.label
                view.findViewById<TextView>(R.id.tvSmallValue).text = card.value
                view.findViewById<TextView>(R.id.tvSmallPill).text = card.pill
                view.findViewById<android.widget.ImageView>(R.id.ivSmallIcon).setImageResource(card.iconRes)
                view.findViewById<android.widget.ImageView>(R.id.ivSmallArt).apply {
                    if (card.artRes != null) setImageResource(card.artRes) else visibility = View.GONE
                }
                view.setOnClickListener { card.onTap() }
                row.addView(view)
            }
            container.addView(row)
        }
    }

    private fun bigOverviewCard(ctx: android.content.Context, card: OverviewCard, startMargin: Int): View {
        data class Variant(val cardBg: Int, val iconTint: String, val pillBg: Int, val pillText: String, val artTint: String)
        val v = when (card.variant) {
            "green" -> Variant(R.drawable.bg_stat_card_green, "#067647", R.drawable.bg_stat_pill_green, "#067647", "#34D399")
            "red" -> Variant(R.drawable.bg_stat_card_red, "#D92D20", R.drawable.bg_stat_pill_red, "#D92D20", "#F87171")
            "orange" -> Variant(R.drawable.bg_stat_card_orange, "#B54708", R.drawable.bg_stat_pill_orange, "#B54708", "#FBBF24")
            else -> Variant(R.drawable.bg_stat_card_blue, "#1D4ED8", R.drawable.bg_stat_pill_blue, "#FFFFFF", "#60A5FA")
        }
        val view = layoutInflater.inflate(R.layout.item_overview_stat_card, null, false)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { marginStart = startMargin }
        view.setBackgroundResource(v.cardBg)
        view.findViewById<TextView>(R.id.tvStatLabel).text = card.label
        view.findViewById<TextView>(R.id.tvStatValue).text = card.value
        view.findViewById<TextView>(R.id.tvStatPill).apply {
            text = card.pill
            setBackgroundResource(v.pillBg)
            setTextColor(Color.parseColor(v.pillText))
        }
        view.findViewById<android.widget.ImageView>(R.id.ivStatIcon).apply {
            setImageResource(card.iconRes)
            setColorFilter(Color.parseColor(v.iconTint))
        }
        view.findViewById<android.widget.ImageView>(R.id.ivStatArt).apply {
            if (card.artRes != null) {
                // Real 3D render from the design sheet — no tinting.
                setImageResource(card.artRes)
            } else {
                setImageResource(card.iconRes)
                setColorFilter(Color.parseColor(v.artTint))
                alpha = 0.9f
            }
        }
        view.setOnClickListener { card.onTap() }
        return view
    }

    private fun dashTile(ctx: android.content.Context, t: DashTile, startMargin: Int): View {
        val iconColor = Color.parseColor(t.colorHex)
        val tintBg = Color.parseColor(t.bgHex)

        // Elevated, ripple-backed card. Height = MATCH_PARENT so both tiles in
        // a row equalise to the taller one (even grid); minHeight stops the
        // no-secondary tiles from collapsing.
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dpx(18).toFloat()
            cardElevation = dpx(2).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            minimumHeight = dpx(122)
            isClickable = true
            isFocusable = true
            rippleColor = android.content.res.ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(iconColor, 20)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = startMargin }
            setOnClickListener { t.onTap() }
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(15), dpx(15), dpx(15), dpx(15))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Tinted circular chip (ramp-50 fill) holding a vector icon (ramp-600).
        content.addView(android.widget.FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(tintBg)
            }
            layoutParams = LinearLayout.LayoutParams(dpx(40), dpx(40))
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(t.iconRes)
                setColorFilter(iconColor)
                layoutParams = android.widget.FrameLayout.LayoutParams(dpx(20), dpx(20), android.view.Gravity.CENTER)
            })
        })

        // Value — dark, so the number reads as the hero (not tinted).
        content.addView(TextView(ctx).apply {
            text = t.primary
            textSize = 25f
            setTextColor(Color.parseColor("#0B1728"))
            typeface = interBoldOrDefault()
            maxLines = 1
            setPadding(0, dpx(12), 0, 0)
        })
        content.addView(TextView(ctx).apply {
            text = t.label
            textSize = 13f
            setTextColor(Color.parseColor("#667085"))
            typeface = interFontOrDefault()
            setPadding(0, dpx(3), 0, 0)
        })
        // Secondary count as a soft colour pill (ramp-50 fill + ramp-900 text).
        t.secondary?.let { sec ->
            content.addView(TextView(ctx).apply {
                text = sec
                textSize = 11f
                setTextColor(Color.parseColor(t.deepHex))
                typeface = interFontOrDefault()
                setPadding(dpx(9), dpx(3), dpx(9), dpx(3))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpx(20).toFloat()
                    setColor(tintBg)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dpx(9) }
            })
        }

        card.addView(content)
        return card
    }

    private fun interBoldOrDefault(): android.graphics.Typeface =
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_bold) }
            .getOrNull() ?: android.graphics.Typeface.DEFAULT_BOLD

    private fun interFontOrDefault(): android.graphics.Typeface =
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_medium) }
            .getOrNull() ?: android.graphics.Typeface.DEFAULT

    private fun openScreen(fragment: androidx.fragment.app.Fragment) {
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun createVisitItem(
        visit: TodayVisit,
        index: Int,
        total: Int,
        canStartTrip: Boolean,
    ): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, binding.visitListContent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val actionBtn = itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction)
        val action = itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel)
        val actionIcon = itemView.findViewById<ImageView>(R.id.ivVisitItemActionIcon)
        val lead = itemView.findViewById<TextView>(R.id.tvVisitItemLead)
        val avatar = itemView.findViewById<TextView>(R.id.tvVisitItemAvatar)
        val staffName = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val staffRole = itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole)
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        val distance = itemView.findViewById<TextView>(R.id.tvVisitItemDistance)
        val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)

        val clientName = visit.placeName ?: visit.leadName ?: "Scheduled Visit"
        // Client name lives in the header (avatar + staffName) only — the
        // body's left cell now shows the visit Type ("Direct CP" / "SV
        // confirmation CP" / etc.) instead of repeating the client name.
        bindTripCardHeader(avatar, staffName, staffRole, clientName)
        // "Location" cell — show the visit destination (address, else place name)
        // instead of a time, per the trip-card design.
        time.text = visit.placeAddress?.takeIf { it.isNotBlank() }
            ?: visit.placeName?.takeIf { it.isNotBlank() }
            ?: "Location not set"
        distance.text = if (visit.placeLat != null && visit.placeLng != null) "Open route" else "Not mapped"

        val isCpVisit = visit.clientPlaceVisitId != null
        // Surface the visit category so the field staff can tell at a
        // glance which lane this row belongs to before tapping in.
        // "sv_cum_cp" rows open into the locked Reject/Confirm sheet on
        // the trip nav; "direct_cp" rows open the full outcome flow.
        val categoryLabel = com.manjugroups.m_connect.ui.marketing.formatCpVisitTypeLabel(
            visitCategory = visit.visitCategory,
            cpType = visit.cpVisit?.cpType,
            hasCpRow = isCpVisit,
        )
        // Bind category into the body's Type cell. The standalone
        // tvVisitItemLead badge below the grid is no longer needed.
        title.text = categoryLabel
        lead.visibility = View.GONE

        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val needsCpDetails = isCpVisit && status == "arrived" && visit.cpVisit?.outcome.isNullOrBlank()
        val isInProgress = status in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived", "on_site", "on-site"
        )

        when {
            needsCpDetails -> {
                statusText.text = "Reaching"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_progress)
                statusText.setTextColor(android.graphics.Color.parseColor("#B54708"))
                // The trip has reached the client (status == arrived);
                // the only thing left is to capture the visit outcome.
                // Per-cpType label mirrors the trip-detail screen's
                // CTA so the home card and the detail screen agree on
                // what tapping it does. SV-cum-CP keeps the locked-SV
                // label; the three special types name their dedicated
                // form so the user knows what's about to open.
                val cpTypeLabel = visit.cpVisit?.cpType
                    ?.lowercase(Locale.getDefault())
                action.text = when {
                    visit.visitCategory == "sv_cum_cp" -> "Complete SV details"
                    cpTypeLabel == "collection_cp" -> "Submit Payment Entry"
                    cpTypeLabel == "old_client" -> "Add Visit Remarks"
                    cpTypeLabel == "gift_distribution" -> "Confirm Gift Distribution"
                    else -> "Complete CP details"
                }
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
                action.setTextColor(android.graphics.Color.WHITE)
                actionIcon.visibility = View.VISIBLE
                eta.text = "Within ${visit.reachingRadiusMeters ?: 500}m"
            }
            isInProgress -> {
                statusText.text = when (status) {
                    "arrived" -> "Reaching"
                    "on_site", "on-site" -> "On Site"
                    else -> "Enroute"
                }
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_progress)
                statusText.setTextColor(android.graphics.Color.parseColor("#B54708"))
                action.text = when (status) {
                    "arrived" -> "Complete Trip"
                    "on_site", "on-site" -> "End Trip"
                    else -> "Enroute"
                }
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_progress)
                action.setTextColor(android.graphics.Color.parseColor("#B54708"))
                actionIcon.visibility = View.GONE
                eta.text = when (status) {
                    "arrived" -> "At client place"
                    "on_site", "on-site" -> "At site"
                    else -> "Tracking"
                }
            }
            isCompleted -> {
                statusText.text = "Complete"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_done)
                statusText.setTextColor(android.graphics.Color.parseColor("#475467"))
                action.text = "Complete"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#475467"))
                actionIcon.visibility = View.GONE
                eta.text = "Complete"
            }
            !canStartTrip -> {
                statusText.text = "Clock in"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_done)
                statusText.setTextColor(android.graphics.Color.parseColor("#475467"))
                action.text = "Clock In First"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#475467"))
                actionIcon.visibility = View.GONE
                eta.text = "After clock in"
            }
            else -> {
                statusText.text = "Start"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_ready)
                statusText.setTextColor(android.graphics.Color.parseColor("#169B2F"))
                action.text = "Start Trip"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
                action.setTextColor(android.graphics.Color.WHITE)
                actionIcon.visibility = View.VISIBLE
                eta.text = "After start"
            }
        }

        if (session.isDriverMode) {
            if (isCompleted) {
                val openDetail: (View) -> Unit = {
                    DriverTripCompletedBottomSheet.newInstance(visit.id)
                        .show(parentFragmentManager, "driver_trip_completed")
                }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openDetail)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openDetail)
            } else if (!isInProgress && canStartTrip) {
                val startTrip: (View) -> Unit = {
                    DriverStartTripBottomSheet.newInstance(visit.id, visit.scheduledDate)
                        .show(parentFragmentManager, "driver_start_trip")
                }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(startTrip)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(startTrip)
            } else {
                val openNav: (View) -> Unit = { openTripNavigationForVisit(visit) }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openNav)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openNav)
            }
        } else {
            if (isCompleted) {
                // Completed visits open a read-only summary instead of the trip flow.
                val openDetail: (View) -> Unit = { openCompletedVisitDetail(visit) }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openDetail)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openDetail)
            } else if (needsCpDetails) {
                // Arrival is already verified (status == arrived) — the
                // outcome form is the only remaining step. Open it right
                // from the home card so the user doesn't have to open the
                // trip detail and tap "Complete CP details" there. The
                // whole card and the button both trigger it.
                val openOutcome: (View) -> Unit = { openCpOutcomeSheetForVisit(visit) }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openOutcome)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openOutcome)
            } else {
                val openNav: (View) -> Unit = { openTripNavigationForVisit(visit) }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openNav)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openNav)
            }
        }

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun createAssignedPlaceItem(place: AssignedPlace, index: Int, total: Int): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, binding.visitListContent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val actionBtn = itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction)
        val action = itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel)
        val actionIcon = itemView.findViewById<ImageView>(R.id.ivVisitItemActionIcon)
        val avatar = itemView.findViewById<TextView>(R.id.tvVisitItemAvatar)
        val staffName = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val staffRole = itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole)
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        val distance = itemView.findViewById<TextView>(R.id.tvVisitItemDistance)
        val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)

        bindTripCardHeader(avatar, staffName, staffRole, place.name)
        // Place name is already shown in the header — body Type cell calls
        // out the row kind ("Assigned place") instead of repeating it.
        title.text = "Assigned place"
        // "Location" cell — the assigned place's address (else its name).
        time.text = place.address?.takeIf { it.isNotBlank() }
            ?: place.name.takeIf { it.isNotBlank() }
            ?: "Location not set"
        distance.text = if (place.lat != null && place.lng != null) "Open route" else "Not mapped"
        eta.text = "After start"
        statusText.text = "Ready"
        statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_ready)
        statusText.setTextColor(android.graphics.Color.parseColor("#169B2F"))
        action.text = "Start Trip"
        actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
        action.setTextColor(android.graphics.Color.WHITE)
        actionIcon.visibility = View.VISIBLE

        val openNav: (View) -> Unit = { openTripNavigationForPlace(place) }
        itemView.isClickable = true
        itemView.isFocusable = true
        itemView.setOnClickListener(openNav)
        actionBtn.isClickable = true
        actionBtn.setOnClickListener(openNav)

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun bindTripCardHeader(
        avatar: TextView,
        nameView: TextView,
        roleView: TextView,
        clientName: String,
    ) {
        val name = formatPersonName(clientName.ifBlank { "Client" })
        avatar.text = name.firstOrNull()?.uppercase() ?: "M"
        nameView.text = name
        roleView.visibility = View.GONE
    }

    private fun formatPersonName(rawName: String): String {
        return rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
            .ifBlank { "Client" }
    }

    private fun openTripNavigationForVisit(visit: TodayVisit) {
        val fragment = TripNavigationFragment.forVisit(
            visitId = visit.id,
            placeName = visit.placeName,
            placeAddress = visit.placeAddress,
            destLat = visit.placeLat,
            destLng = visit.placeLng,
            status = visit.status,
            tripType = visit.tripType,
            clientPlaceVisitId = visit.clientPlaceVisitId,
            cpClientMet = visit.cpVisit?.clientMet,
            cpOutcome = visit.cpVisit?.outcome,
            visitCategory = visit.visitCategory,
            cpType = visit.cpVisit?.cpType,
            clientMobile = visit.leadPhone,
        )
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openCompletedVisitDetail(visit: TodayVisit) {
        val fragment = com.manjugroups.m_connect.ui.marketing
            .CompletedVisitDetailFragment.forVisit(visit)
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Opens the right post-arrival flow directly from the home card.
     * Branches per cpType so the three special types
     * (gift_distribution / old_client / collection_cp) go to their
     * dedicated sheets — opening the default booking-outcome sheet
     * here is wrong UI for those flows. For special types we hop into
     * the trip-detail screen where the per-cpType handlers already
     * live; for sv_cum_cp / follow_up / booking_cp we open the
     * outcome sheet inline as before.
     *
     * Only used once the visit is arrival-verified (status == arrived),
     * so no further OTP/photo step is needed.
     */
    private fun openCpOutcomeSheetForVisit(visit: TodayVisit) {
        val cpId = visit.clientPlaceVisitId
        if (cpId.isNullOrBlank()) {
            // No CP row behind it — fall back to the trip detail screen.
            openTripNavigationForVisit(visit)
            return
        }
        val cpType = visit.cpVisit?.cpType?.lowercase(Locale.getDefault())
        if (cpType == "collection_cp" ||
            cpType == "old_client" ||
            cpType == "gift_distribution"
        ) {
            // Trip nav screen's onCompleteCpDetailsClicked() + the
            // belt-and-braces guard in showCpCompletionSheet() will
            // route to promptCollectionPayment / promptOldClientRemarks
            // / completeGiftDistributionMet as appropriate. Reusing
            // that path avoids duplicating the booking-lookup + result-
            // listener wiring here.
            openTripNavigationForVisit(visit)
            return
        }
        CompleteCpVisitBottomSheet
            .newInstance(
                cpVisitId = cpId,
                cpClientMet = visit.cpVisit?.clientMet,
                cpOutcome = visit.cpVisit?.outcome,
                isSvFixedHint = visit.visitCategory == "sv_cum_cp",
            )
            .show(parentFragmentManager, "cp_visit_complete")
    }

    private fun openTripNavigationForPlace(place: AssignedPlace) {
        val fragment = TripNavigationFragment.forPlace(
            placeId = place.id,
            placeName = place.name,
            placeAddress = place.address,
            destLat = place.lat,
            destLng = place.lng
        )
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun applyItemSpacing(itemView: View, index: Int, total: Int) {
        val params = itemView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        params.bottomMargin = if (index == total - 1) 0 else dpToPx(10)
        itemView.layoutParams = params
    }

    private fun formatVisitTimeOrDate(visit: TodayVisit): String {
        val startRaw = visit.scheduledStartTime
        val endRaw = visit.scheduledEndTime
        val start = startRaw?.let { formatTimeValue(it) }
        val end = endRaw?.let { formatTimeValue(it) }

        if (!start.isNullOrBlank() && !end.isNullOrBlank()) return "$start - $end"
        if (!start.isNullOrBlank()) return start
        if (!end.isNullOrBlank()) return end

        // Fallback: if scheduledDate contains a datetime, show time; else show date.
        val embeddedTime = visit.scheduledDate.let { formatTimeValue(it) }
        if (!embeddedTime.isNullOrBlank()) return embeddedTime

        return formatVisitDate(visit.scheduledDate)
    }

    private fun formatVisitDate(scheduledDate: String?): String {
        if (scheduledDate.isNullOrBlank()) return "Today"
        val parsed = runCatching {
            // Try ISO 8601 first, then plain date
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            iso.isLenient = false
            iso.parse(scheduledDate.substringBefore(".").substringBefore("Z"))
        }.getOrNull() ?: runCatching {
            val plain = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            plain.isLenient = false
            plain.parse(scheduledDate.take(10))
        }.getOrNull() ?: return "Today"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed)
    }

    private fun formatTimeValue(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        // ISO 8601 datetime: extract time after the 'T' separator before any regex
        // (word-boundary \b fails between 'T' and a digit, and timezone '+HH:MM' can false-match)
        val isoMatch = Regex("""^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})""").find(value)
        if (isoMatch != null) {
            val hour24 = isoMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = isoMatch.groupValues[2]
            val hour12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            val suffix = if (hour24 < 12) "AM" else "PM"
            return String.format(Locale.getDefault(), "%02d:%s %s", hour12, minute, suffix)
        }

        val amPmMatch = Regex("(?i)\\b(\\d{1,2}:\\d{2})(?::\\d{2})?\\s*(AM|PM)\\b").find(value)
        if (amPmMatch != null) {
            return "${amPmMatch.groupValues[1]} ${amPmMatch.groupValues[2].uppercase(Locale.getDefault())}"
        }

        val h24Match = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?::[0-5]\\d)?\\b").find(value)
        if (h24Match != null) {
            val hour24 = h24Match.groupValues[1].toIntOrNull() ?: return null
            val minute = h24Match.groupValues[2]
            val hour12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            val suffix = if (hour24 < 12) "AM" else "PM"
            return String.format(Locale.getDefault(), "%02d:%s %s", hour12, minute, suffix)
        }

        return null
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun loadUnreadNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getUnreadNotificationCount(session.bearerToken)
            }.onSuccess { response ->
                if (_binding == null) return@onSuccess
                val unreadCount = response.unreadCount
                binding.homeHeader.setBellBadgeCount(unreadCount)
            }
        }
    }

    private var selectedTab = "all"

    private fun setupDriverTabs() {
        val clickListener = View.OnClickListener { v ->
            selectedTab = when (v.id) {
                R.id.tabUpcoming -> "upcoming"
                R.id.tabCompleted -> "completed"
                else -> "all"
            }
            updateTabSelectionVisuals()
            (viewModel.uiState.value as? HomeUiState.Loaded)?.let { renderVisitCard(it) }
        }
        binding.tabAll.setOnClickListener(clickListener)
        binding.tabUpcoming.setOnClickListener(clickListener)
        binding.tabCompleted.setOnClickListener(clickListener)
        updateTabSelectionVisuals()
    }

    private fun updateTabSelectionVisuals() {
        val activeBg = requireContext().getDrawable(R.drawable.bg_cpv_filter_pill_active)
        val inactiveBg = requireContext().getDrawable(R.drawable.bg_cpv_filter_pill_inactive)
        val white = android.graphics.Color.WHITE
        val grayText = android.graphics.Color.parseColor("#344054")

        binding.tabAll.background = if (selectedTab == "all") activeBg else inactiveBg
        binding.tabAll.setTextColor(if (selectedTab == "all") white else grayText)
        binding.tabAll.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            requireContext(),
            if (selectedTab == "all") R.font.inter_semibold else R.font.inter_medium
        )

        binding.tabUpcoming.background = if (selectedTab == "upcoming") activeBg else inactiveBg
        binding.tabUpcoming.setTextColor(if (selectedTab == "upcoming") white else grayText)
        binding.tabUpcoming.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            requireContext(),
            if (selectedTab == "upcoming") R.font.inter_semibold else R.font.inter_medium
        )

        binding.tabCompleted.background = if (selectedTab == "completed") activeBg else inactiveBg
        binding.tabCompleted.setTextColor(if (selectedTab == "completed") white else grayText)
        binding.tabCompleted.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            requireContext(),
            if (selectedTab == "completed") R.font.inter_semibold else R.font.inter_medium
        )
    }

    /**
     * Driver / Executive view is auto-selected from the logged-in
     * staff's designation — see SessionManager.isDriverMode (mirrors
     * the web's `hasDriverDesignation` check). The old Executive /
     * Driver dropdown that let any operator flip this manually is
     * gone; this helper just shows the driver-only filter pills
     * when the current account actually IS a driver.
     */
    private fun setupRoleAdaptiveView() {
        binding.layoutDriverTabs.visibility =
            if (session.isDriverMode) View.VISIBLE else View.GONE
    }

    private fun setupEdgeDragQr() {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        // Initialize the panel content offscreen to the right
        binding.panelContent.translationX = screenWidth

        if (!session.hasSeenEdgeQrTooltip) {
            (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
            binding.edgeQrTourDimBg.alpha = 0f
            binding.edgeQrTourDimBg.visibility = android.view.View.VISIBLE
            binding.edgeQrTourDimBg.animate().alpha(1f).setDuration(400).setStartDelay(800).start()

            binding.edgeQrTooltip.alpha = 0f
            binding.edgeQrTooltip.visibility = android.view.View.VISIBLE
            binding.edgeQrTooltip.animate()
                .alpha(1f)
                .setStartDelay(800)
                .setDuration(400)
                .withEndAction {
                    startFloatingAnimations()
                }
                .start()
        }

        val dismissTooltipAction = {
            if (_binding != null && (binding.edgeQrTooltip.visibility == android.view.View.VISIBLE || binding.edgeQrTourDimBg.visibility == android.view.View.VISIBLE)) {
                session.hasSeenEdgeQrTooltip = true
                stopFloatingAnimations()
                (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
                binding.edgeQrTourDimBg.animate().alpha(0f).setDuration(250).withEndAction {
                    if (_binding != null) binding.edgeQrTourDimBg.visibility = android.view.View.GONE
                }.start()
                binding.edgeQrTooltip.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        if (_binding != null) {
                            binding.edgeQrTooltip.visibility = android.view.View.GONE
                        }
                    }
                    .start()
            }
        }

        binding.edgeQrTooltip.setOnClickListener { dismissTooltipAction() }
        binding.btnDismissTooltip.setOnClickListener { dismissTooltipAction() }
        // Clicking the outer screen (dim background) dismisses the onboarding tooltip.
        binding.edgeQrTourDimBg.setOnClickListener { dismissTooltipAction() }

        var startX = 0f
        var downTime = 0L

        binding.edgeDragHandle.setOnTouchListener { v, event ->
            if (_binding == null) return@setOnTouchListener false
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    downTime = System.currentTimeMillis()
                    binding.edgeQrPanel.visibility = android.view.View.VISIBLE
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dragDist = -dx
                    if (dragDist > 0) {
                        binding.panelBlurBg.alpha = (dragDist / 300f).coerceIn(0f, 1f)
                        binding.panelContent.translationX = (screenWidth - dragDist).coerceAtLeast(0f)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    val dragDist = -dx
                    val duration = System.currentTimeMillis() - downTime
                    
                    if (Math.abs(dragDist) < 15 && duration < 200) {
                        // Click / Tap detected
                        animatePanel(true)
                    } else if (dragDist > 120) {
                        // Dragged past threshold
                        animatePanel(true)
                    } else {
                        // Cancel / Spring back
                        animatePanel(false)
                    }
                    true
                }
                else -> false
            }
        }

        binding.panelBlurBg.setOnClickListener {
            animatePanel(false)
        }

        binding.btnOverlayQr.setOnClickListener {
            animatePanel(open = false, showTabBarOnClose = false)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, com.manjugroups.m_connect.ui.library.frontdesk.QrScannerFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun animatePanel(open: Boolean, showTabBarOnClose: Boolean = true) {
        if (_binding == null) return
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        if (open) {
            if (binding.edgeQrTooltip.visibility == android.view.View.VISIBLE || binding.edgeQrTourDimBg.visibility == android.view.View.VISIBLE) {
                session.hasSeenEdgeQrTooltip = true
                stopFloatingAnimations()
                binding.edgeQrTooltip.visibility = android.view.View.GONE
                binding.edgeQrTourDimBg.visibility = android.view.View.GONE
            }
            (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
            binding.edgeQrPanel.visibility = android.view.View.VISIBLE
            binding.panelBlurBg.animate().alpha(1f).setDuration(250).start()
            binding.panelContent.animate().translationX(0f).setDuration(250).start()
            binding.edgeDragHandle.animate().translationX(binding.edgeDragHandle.width.toFloat()).setDuration(250).start()
        } else {
            if (showTabBarOnClose) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
            }
            binding.panelBlurBg.animate().alpha(0f).setDuration(250).start()
            binding.edgeDragHandle.animate().translationX(0f).setDuration(250).start()
            binding.panelContent.animate().translationX(screenWidth).setDuration(250)
                .withEndAction {
                    if (_binding != null) {
                        binding.edgeQrPanel.visibility = android.view.View.GONE
                    }
                }
                .start()
        }
    }

    private fun startFloatingAnimations() {
        if (_binding == null) return
        
        // 1. Tooltip horizontal float (moves left slightly and returns)
        val tooltipAnim = android.animation.ObjectAnimator.ofFloat(
            binding.edgeQrTooltip,
            "translationX",
            0f, -16f, 0f
        ).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        tooltipAnimator = tooltipAnim
        tooltipAnim.start()

        // 2. Handle horizontal float (moves left slightly and returns to nudge user)
        val handleAnim = android.animation.ObjectAnimator.ofFloat(
            binding.edgeDragHandle,
            "translationX",
            0f, -8f, 0f
        ).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        handleAnimator = handleAnim
        handleAnim.start()
    }

    private fun stopFloatingAnimations() {
        tooltipAnimator?.cancel()
        tooltipAnimator = null
        handleAnimator?.cancel()
        handleAnimator = null
        if (_binding != null) {
            binding.edgeQrTooltip.translationX = 0f
            binding.edgeDragHandle.translationX = 0f
        }
    }

    override fun onDestroyView() {
        stopFloatingAnimations()
        cancelPendingVisitSkeleton()
        SkeletonUtils.stopAll()
        binding.homeHeader.stopFloatingAnimation()
        super.onDestroyView()
        _binding = null
    }
}
