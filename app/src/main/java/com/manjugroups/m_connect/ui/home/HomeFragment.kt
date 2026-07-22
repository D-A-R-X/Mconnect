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
import com.manjugroups.m_connect.ui.common.LocalCache
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
import kotlin.math.abs
import kotlin.math.roundToInt
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.common.commitOnce

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val geoApi = com.manjugroups.m_connect.network.GeoTrackApi.create()
    // VP / Management dashboard (replaces Today's Trip for vpDashboard.view holders).
    private var vpDashboardData: com.manjugroups.m_connect.network.MobileDashboardResponse? = null
    private var vpDashboardLoading = false
    private var lastDashSignature: String? = null
    // Dashboard date filter: null = today. Every tile re-fetches for this day.
    private var vpSelectedDate: String? = null

    private companion object {
        const val DASH_DATE_RESULT_KEY = "home_dash_date_result"
        // Today's Trip infinite-scroll window: first page size + how many rows'
        // worth of scroll from the bottom triggers the next page.
        const val HOME_TRIP_PAGE = 15
        const val HOME_TRIP_NEAR_END_ROWS = 4
    }
    private val visitEmptySubtitle =
        "It looks like you don’t have any meetings scheduled at the moment. " +
            "This space will be updated as new meetings are added!"

    private var pendingEntryAnimation = true

    // True once today's visits have resolved at least once. Gates the
    // visit skeleton so a refresh / attendance update never blanks the
    // trip card back to a skeleton or the empty state.
    private var homeVisitsResolved = false
    // True once Home has painted real content at least once; gates the
    // full-screen skeleton so later refreshes don't re-cover the Overview tabs.
    private var hasRenderedHomeOnce = false
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
        if (session.canViewVpDashboard()) {
            binding.btnDashDateFilter.setOnClickListener { showDashDatePicker() }
            parentFragmentManager.setFragmentResultListener(
                DASH_DATE_RESULT_KEY, viewLifecycleOwner
            ) { _, bundle ->
                val picked = bundle.getString(
                    com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.KEY_FROM
                ).orEmpty()
                if (picked.isBlank()) return@setFragmentResultListener
                vpSelectedDate = if (picked == indiaToday()) null else picked
                // Counts for another day may coincidentally match — clear the
                // cached signature so the header swap is never skipped.
                lastDashSignature = null
                applyDashHeader()
                loadVpDashboard(force = true)
            }
            loadVpDashboard()
        }
        setupHomeScrollAnimation()
        armHomeSettle()
        collectState()
        collectEvents()
        observeIamUpdates()
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        loadUnreadNotifications()
        startBannerAnimation()

        setupRoleAdaptiveView()
        setupOverviewTabs()
        setupDriverTabs()
        // Fleet administrators see the allocation queue in this same
        // Today's Trip surface, so load it alongside the normal home data.
        loadFleetDispatch()
        // Overview (dashboard) vs Today's Trip from the start, so a normal user
        // never flashes the dashboard before data loads.
        applyDashboardVisibility()

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
        } else {
            // No frontdesk access — hide the edge-QR handle/panel so it isn't a
            // dead, non-functional tab on the home edge (it had no touch
            // listener, so tapping it did nothing).
            binding.edgeDragHandle.visibility = View.GONE
            binding.edgeQrPanel.visibility = View.GONE
        }
    }

    /**
     * Belt-and-suspenders for the "banner stuck at the top" race: on some
     * devices the header/content measure in an order that leaves
     * homeHeaderSpacer at 0 (so the Overview/Trip drawer sits OVER the blue
     * banner) or leaves an early scroll offset. On every layout pass for the
     * first ~1.2s after the view is created, force the rest state — spacer =
     * header height, scrollY 0, translations cleared — then stop so normal
     * scrolling / the two-stage dashboard scroll aren't fought.
     */
    /** Force the Home to its rest state: header spacer = header height (so the
     *  drawer sits BELOW the blue banner, not over it), scroll at 0, and the
     *  two-stage translations cleared. Called directly (resume) and from the
     *  layout-pass settler below. */
    private fun settleHomeToRest() {
        val b = _binding ?: return
        val h = b.homeHeader.height
        // Sync the spacer to the header height. Force it even when layoutParams
        // already equals h — the VIEW can be laid out at a stale (smaller)
        // height while layoutParams reads the right value, which left the drawer
        // covering the banner. Re-request layout on the container so the scroll
        // view actually re-measures the spacer.
        if (h > 0 && (b.homeHeaderSpacer.layoutParams.height != h || b.homeHeaderSpacer.height != h)) {
            b.homeHeaderSpacer.layoutParams =
                b.homeHeaderSpacer.layoutParams.apply { height = h }
            b.homeHeaderSpacer.requestLayout()
            b.homeScrollContent.requestLayout()
        }
        if (b.homeContent.scrollY != 0) b.homeContent.scrollTo(0, 0)
        if (b.whiteContentArea.translationY != 0f) b.whiteContentArea.translationY = 0f
        // Clear any leftover two-stage card translation (Overview or Trip) so the
        // section sits at its natural rest position under the header.
        b.root.findViewById<View>(R.id.overviewCardsArea)?.translationY = 0f
        b.root.findViewById<View>(R.id.tripCardsArea)?.translationY = 0f
    }

    // While elapsedRealtime() is below this, keep forcing the rest state. It
    // covers the async loadHomeData → render → applyDashboardVisibility
    // re-layout that lands AFTER a short one-shot window and would otherwise
    // leave the Overview/Trip drawer stuck over the banner (esp. on resume from
    // Notifications). collectState() also re-settles on Loaded while armed.
    private var homeSettleDeadline = 0L

    private fun armHomeSettle(durationMs: Long = 3000L) {
        homeSettleDeadline = android.os.SystemClock.elapsedRealtime() + durationMs
        settleHomeToRest()
        val b = _binding ?: return
        b.root.post { settleHomeToRest() }
        val observer = b.homeContent.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (android.os.SystemClock.elapsedRealtime() <= homeSettleDeadline) settleHomeToRest()
        }
        observer.addOnGlobalLayoutListener(listener)
        b.homeContent.postDelayed({
            _binding?.homeContent?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }, durationMs)
    }

    private fun setupPullToRefresh() {
        // Fire the same loads the screen does on first open so a pull
        // refreshes attendance, today's visits and notifications in
        // one gesture. The spinner is dismissed in collectState() when
        // the next "loaded" state lands.
        binding.homeRefresh.setupPullToRefresh {
            // Recover the rest state on every pull. The banner can get wedged
            // under the white drawer (spacer laid out at ~0 while scrollY is
            // already 0, so there's no scroll room to reveal it) and the user
            // reported refresh couldn't fix it — because refresh only reloaded
            // data. Reset the one-shot, snap to top, and re-arm the settler so
            // the reloaded content re-pins to rest too.
            homeRestInitialized = false
            _binding?.homeContent?.scrollTo(0, 0)
            armHomeSettle()
            // Dashboard users see the KPI overview, so a pull must refresh THAT
            // (a single fast call) and the spinner is dismissed the moment it
            // lands — instead of spinning for the slow visits/attendance chain
            // they never even see.
            if (session.canViewVpDashboard()) loadVpDashboard(force = true)
            viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
            loadUnreadNotifications()
            // Safety net: the spinner is normally dismissed when the next
            // "Loaded" state lands, but a refresh that produces IDENTICAL data
            // may not re-emit (StateFlow de-dups) and a stalled/slow request
            // could otherwise spin forever. Force-dismiss after a hard cap —
            // kept short so the loader never appears to "load too much".
            binding.homeRefresh.postDelayed({ _binding?.homeRefresh?.dismissRefresh() }, 3500)
        }
        // setupPullToRefresh() installs a generic inset listener that anchors
        // the spinner near the top — on the full-bleed Home header that lands
        // it OVER the blue banner. syncSpacer owns the Home spinner position
        // (just below the banner), so remove the generic one to stop the two
        // fighting (the cause of the spinner sometimes appearing mid-screen).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.homeRefresh, null)
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
    // The Overview drawer's rounded-top background; its corner radius is
    // animated 24dp → 0dp as the drawer reaches the scroll limit so it sits
    // flush under the blue profile row, then re-rounds on scroll-back.
    private var drawerBg: android.graphics.drawable.GradientDrawable? = null
    private var drawerMaxRadiusPx = 0f
    // One-shot: force the scroll to its rest state after the header/content
    // first measure, so an early focus-driven auto-scroll can't leave the
    // Overview drawer wedged over the banner (the "stuck banner" bug).
    private var homeRestInitialized = false

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
        drawerBg = whiteCardBg
        drawerMaxRadiusPx = maxCornerRadiusPx

        // Touch routing: the header is pinned behind the full-height scroll
        // panel. Route touches above the visible-header line to the header (so
        // its buttons work) and the rest to the scroll panel (so the tabs stay
        // tappable and scrollable once the content covers the header).
        binding.homeStickyColumn.headerView = binding.homeHeader
        binding.homeStickyColumn.scrollContainer = binding.homeRefresh
        binding.homeStickyColumn.coverLineProvider = {
            val b = _binding
            if (b == null) 0 else (b.homeHeader.height - b.homeContent.scrollY).coerceAtLeast(0)
        }
        // Keep the transparent spacer exactly the header's height so the white
        // Overview panel sits just below the pinned header at rest.
        val syncSpacer = {
            val b = _binding
            if (b != null) {
                val h = b.homeHeader.height
                if (h > 0) {
                    // Self-heal the spacer on EVERY layout pass (this runs from
                    // the header/content/whiteArea layout listeners), not just
                    // when layoutParams differ. The spacer VIEW can be laid out
                    // at a stale ~0 height while its layoutParams already read
                    // the header height — that mismatch is what wedges the white
                    // drawer over the banner at rest. Force both to h and
                    // re-measure the scroll content so the fix actually takes.
                    if (b.homeHeaderSpacer.layoutParams.height != h ||
                        b.homeHeaderSpacer.height != h
                    ) {
                        b.homeHeaderSpacer.layoutParams =
                            b.homeHeaderSpacer.layoutParams.apply { height = h }
                        b.homeHeaderSpacer.requestLayout()
                        b.homeScrollContent.requestLayout()
                    }
                    // homeRefresh is full-height, so the default pull spinner
                    // starts at the very top and sweeps DOWN over the blue
                    // banner. Anchor it to emerge from just BELOW the banner
                    // instead: it appears at the banner's bottom edge and pulls
                    // a little further into the white area — never crossing the
                    // banner.
                    val d = resources.displayMetrics.density
                    b.homeRefresh.setProgressViewOffset(false, (h - 8 * d).toInt(), (h + 32 * d).toInt())
                    // Scroll limit: lift the drawer just enough to cover the
                    // BANNER and sit flush under the profile row (avatar/name/
                    // bell) — NOT past it. So the range = bannerHeight + the
                    // 12dp drawer margin = (headerHeight + 12) − profileRowBottom.
                    // The NestedScroll's paddingBottom(120dp) already adds range.
                    val vp = b.homeContent.height
                    val profileBottom = runCatching {
                        b.homeHeader.getHeaderBinding().homeProfileRow.bottom
                    }.getOrDefault(0)
                    if (vp > 0 && profileBottom > 0) {
                        val sLimit = h + (12 * d).toInt() - profileBottom
                        // Both the dashboard Overview AND a normal user's Today's
                        // Trip use the same two-stage scroll: the drawer lifts to
                        // cover the banner (stage 1), then its sticky header pins
                        // under the profile row and only the CARD area scrolls
                        // under it (stage 2). Pick whichever section is live.
                        val dashboard = session.canViewVpDashboard()
                        val stickyHeaderId = if (dashboard) R.id.overviewHeader else R.id.tripHeader
                        val cardsAreaId = if (dashboard) R.id.overviewCardsArea else R.id.tripCardsArea
                        // overflow = how far the cards extend past the pinned
                        // header's bottom (space between it and the nav bar). If
                        // they fit, overflow is 0 and the scroll stops at sLimit —
                        // so the drawer NEVER rises above the profile/notify row.
                        val headerH = b.root.findViewById<View>(stickyHeaderId)?.height ?: 0
                        val cardsH = b.root.findViewById<View>(cardsAreaId)?.height ?: 0
                        val navHeight = (110 * d).toInt()
                        val availableCards = vp - navHeight - profileBottom - headerH
                        val overflow = (cardsH - availableCards).coerceAtLeast(0)
                        b.homeContent.maxScrollY = sLimit + overflow
                        // Give the scroll view enough range to actually reach the
                        // limit. Dashboard always reserves it; a normal user only
                        // needs it when the trip list overflows the fold — a short
                        // list keeps its natural height (no empty gap, no forced
                        // banner-lift).
                        val target = when {
                            dashboard -> (vp + sLimit - (120 * d).toInt()).coerceAtLeast(0)
                            overflow > 0 -> (vp + sLimit + overflow).coerceAtLeast(0)
                            else -> 0
                        }
                        if (b.homeScrollContent.minimumHeight != target) {
                            b.homeScrollContent.minimumHeight = target
                        }
                        // Establish the rest state ONCE, now that measurement is
                        // valid: undo any early auto-scroll (focus-driven) and
                        // clear the two-stage translations so the banner shows.
                        // One-shot — syncSpacer also fires on tab toggle/relayout,
                        // and yanking scrollTo(0,0) there would break sticky-tabs.
                        if (!homeRestInitialized) {
                            homeRestInitialized = true
                            b.homeContent.scrollTo(0, 0)
                            b.whiteContentArea.translationY = 0f
                            b.root.findViewById<View>(R.id.overviewCardsArea)?.translationY = 0f
                            b.root.findViewById<View>(R.id.overviewHeader)?.translationZ = 0f
                            b.root.findViewById<View>(R.id.tripCardsArea)?.translationY = 0f
                            b.root.findViewById<View>(R.id.tripHeader)?.translationZ = 0f
                        }
                    }
                }
            }
        }
        binding.homeHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncSpacer() }
        binding.homeContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncSpacer() }
        // The grid's height changes when the Marketing/HR tab toggles, so recompute
        // the scroll range then too.
        binding.whiteContentArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncSpacer() }
        binding.homeHeader.post { syncSpacer() }

        binding.homeContent.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(false)
            } else if (scrollY <= 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(true)
            }
            // Straighten the drawer's top corners as it nears the scroll limit
            // (so it sits flush under the blue profile row), then re-round the
            // moment it scrolls back down.
            val b = _binding
            val bg = drawerBg
            if (b != null && bg != null) {
                val den = resources.displayMetrics.density
                val profileBottom = runCatching {
                    b.homeHeader.getHeaderBinding().homeProfileRow.bottom
                }.getOrDefault(0)
                if (profileBottom > 0) {
                    val sLimit = b.homeHeader.height + 12f * den - profileBottom
                    val fade = 36f * den // straighten over the last 36dp of travel
                    val frac = ((sLimit - scrollY) / fade).coerceIn(0f, 1f) // 1 far → 0 at limit
                    val r = drawerMaxRadiusPx * frac
                    bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

                    // Two-stage scroll: once past the limit, pin the drawer top +
                    // sticky header at the profile row and let the cards scroll
                    // UNDER them, so cards hidden behind the bottom nav stay
                    // reachable AND the profile/notify header is never covered.
                    // Applies to the dashboard Overview and the normal Today's Trip
                    // list alike (whichever section is live).
                    run {
                        val dashboard = session.canViewVpDashboard()
                        val cardsArea = b.root.findViewById<View>(
                            if (dashboard) R.id.overviewCardsArea else R.id.tripCardsArea
                        )
                        val stickyHeader = b.root.findViewById<View>(
                            if (dashboard) R.id.overviewHeader else R.id.tripHeader
                        )
                        val overshoot = (scrollY - sLimit).coerceAtLeast(0f)
                        b.whiteContentArea.translationY = overshoot
                        cardsArea?.translationY = -overshoot
                        stickyHeader?.translationZ = if (overshoot > 0f) 20f * den else 0f
                        b.whiteContentArea.clipChildren = overshoot > 0f
                    }
                }
            }
            // Self-heal: any time the scroll reaches the very top, force the
            // rest state. If the drawer was wedged over the banner (stale spacer
            // / leftover translation), scrolling back up — or the refresh snap —
            // always restores it. settleHomeToRest is a cheap no-op when already
            // correct (it only re-lays out when the spacer height is wrong).
            if (scrollY <= 0) settleHomeToRest()

            // Infinite-scroll: grow the Today's Trip window as the user nears
            // the bottom (integrated here instead of the pager's own listener,
            // which would clobber this scroll-animation listener).
            maybeExtendTripWindow(scrollY)
        })
    }

    private fun startBannerAnimation() {
        val anim = binding.homeHeader.getHeaderBinding().ivBannerAnimation.drawable as? android.graphics.drawable.AnimationDrawable
        anim?.start()
    }

    override fun onResume() {
        super.onResume()
        loadFleetDispatch()
        // Clear any pull-refresh spinner left spinning from before (e.g. the
        // user opened Notifications mid-refresh and came back to a stuck loader).
        _binding?.homeRefresh?.dismissRefresh()
        // Returning from a pushed screen (Notifications, Task Manager, …) KEEPS
        // this fragment's view, so onViewCreated's rest-settler never re-runs —
        // and the content came back scrolled up with the banner covered (spacer
        // reset to 0). A layout pass may not fire on resume, so settle DIRECTLY
        // now, again on the next frame, and once more after a beat (covers the
        // case where the header re-measures late).
        armHomeSettle()
        // Restore the tab bar unless the edge-QR panel or its onboarding
        // tooltip is ACTIVELY on screen. The old `hasSeenEdgeQrTooltip && …`
        // gate hid the nav FOREVER for any user who never sees that tooltip —
        // e.g. anyone without frontdesk permissions, where the edge-QR flow
        // never runs — so their bottom navigation disappeared entirely.
        val edgeQrActive = _binding != null && (
            binding.edgeQrPanel.visibility == android.view.View.VISIBLE ||
                binding.edgeQrTooltip.visibility == android.view.View.VISIBLE ||
                binding.edgeQrTourDimBg.visibility == android.view.View.VISIBLE
            )
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(!edgeQrActive)
        // Home = full-bleed blue status bar with LIGHT (white) icons. Apply it
        // now AND re-post it, so a screen we just returned from (e.g. the white
        // Notifications header) can't win the race and leave a white status-bar
        // strip with dark icons over the blue header.
        val applyHomeTopBar = {
            (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
                Color.parseColor("#0B61CA"),
                false,
                fullBleed = true,
            )
        }
        applyHomeTopBar()
        _binding?.root?.post { if (isResumed) applyHomeTopBar() }
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



    /** Re-evaluate the dashboard-vs-trip gate when IAM data changes — notably
     *  when the normalized `role` first populates for a session that logged in
     *  before the app persisted it, so a super-admin's dashboard reappears
     *  without a manual refresh. Designation-based (VP/GM) users don't depend
     *  on this — their designation is already stored. */
    private fun observeIamUpdates() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.manjugroups.m_connect.auth.IamUpdateBus.updates.collect {
                    if (_binding == null) return@collect
                    applyDashboardVisibility()
                    if (session.canViewVpDashboard()) loadVpDashboard()
                    (viewModel.uiState.value as? HomeUiState.Loaded)?.let { renderVisitCard(it) }
                }
            }
        }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HomeUiState.Loading -> {
                            // The full-screen skeleton is ONLY for the very first
                            // open (nothing on screen yet). On any later refresh —
                            // returning to the Home tab, a background reload — we
                            // keep the already-rendered content up; the opaque
                            // skeleton would otherwise re-cover the Overview and
                            // its Marketing/HR tabs, making them feel "unclickable"
                            // until the reload finished. Pull-refresh has its own
                            // spinner, so skip the skeleton there too.
                            if (!hasRenderedHomeOnce && !binding.homeRefresh.isRefreshing) {
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
                            // Content is on screen now — future Loading emits keep
                            // it visible instead of flashing the full-screen skeleton.
                            hasRenderedHomeOnce = true
                            renderSummary()
                            // Paint the trip card only once visits have
                            // resolved (or we already have some to show) AND
                            // we're not mid visits-fetch. Otherwise a
                            // Loaded-with-empty-trips state during the
                            // initial load would flash the empty view before
                            // the visit skeleton appears.
                            val hasVisits = state.todayVisits.any { it.status != "cancelled" }
                            // Dashboard users get the KPI overview, which comes
                            // from the dashboard endpoint and not the visits
                            // fetch — render it immediately instead of waiting
                            // on that call (keeps Home Overview loading reliably).
                            if (session.canViewVpDashboard() ||
                                ((homeVisitsResolved || hasVisits) &&
                                    !viewModel.isVisitsLoading.value)
                            ) {
                                renderVisitCard(state)
                                // The render (+ applyDashboardVisibility) re-lays
                                // the drawer out and can leave the banner wedged.
                                // Re-pin to rest if we're still in the post-resume
                                // settle window OR the user is simply at the top
                                // (scrollY 0) — the latter covers a SLOW load that
                                // lands after the 3s window, which was the main
                                // "stuck most times" trigger. Guarded on at-rest so
                                // we never yank a user who has scrolled down.
                                val atRest = (_binding?.homeContent?.scrollY ?: 0) <= 0
                                if (atRest ||
                                    android.os.SystemClock.elapsedRealtime() <= homeSettleDeadline
                                ) {
                                    settleHomeToRest()
                                    _binding?.root?.post { settleHomeToRest() }
                                }
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

    // Infinite-scroll window for the Today's Trip list. The visits endpoint
    // returns the whole day in one shot; rendering a card per trip up-front is
    // slow for staff with many stops, so we render only a growing window and
    // extend it as the user nears the bottom (see the scroll listener). Reset
    // to the first page whenever the underlying list/tab changes.
    private var homeTripLimit = HOME_TRIP_PAGE
    private var homeTripTotal = 0
    private var lastVisitBaseSignature: String? = null

    /**
     * Show the company Overview ONLY for dashboard users; everyone else sees
     * the "Today's Trip" section. The Overview include (`homeOverviewInclude`)
     * is otherwise ALWAYS in the layout and `cardTodayVisit` is always gone —
     * so without this toggle a normal user saw the dashboard and never saw
     * their trips. Gated on [SessionManager.canViewVpDashboard].
     */
    private fun applyDashboardVisibility() {
        if (_binding == null) return
        val dash = session.canViewVpDashboard()
        binding.root.findViewById<View>(R.id.homeOverviewInclude)?.visibility =
            if (dash) View.VISIBLE else View.GONE
        binding.root.findViewById<View>(R.id.cardTodayVisit)?.visibility =
            if (dash) View.GONE else View.VISIBLE
    }

    private fun renderVisitCard(state: HomeUiState.Loaded) {
        // We have a definitive answer — drop any armed/showing skeleton.
        cancelPendingVisitSkeleton()
        setVisitSkeletonVisible(false)
        // Fleet administrators own this surface: renderFleetDispatch fills it
        // with the allocation queue. Letting the normal path run too would
        // clobber those cards with the admin's own (usually empty) trip list
        // on every state emission.
        if (session.isFleetAdminDriver) {
            applyDashboardVisibility()
            renderFleetDispatch()
            return
        }
        // Overview (dashboard) vs Today's Trip — one or the other, never both.
        applyDashboardVisibility()
        // VP / Management dashboard replaces the Today's Trip list for anyone
        // with vpDashboard.view (super-admins included). Its numbers come from
        // the dashboard endpoint, not the visits flow, so short-circuit here
        // before any trip rendering / empty-state logic.
        if (session.canViewVpDashboard()) {
            applyDashHeader()
            // The globe icon belongs to the "Today's Trip" view, not the KPI
            // dashboard — hide it so the header reads cleanly, and surface
            // the date filter in its place. Visibility is set unconditionally
            // (even before the numbers land) so the overview + its tabs show
            // immediately; bindDashboardData() fills the counts when ready.
            binding.ivVisitTitleGlobe.visibility = View.GONE
            binding.tvVisitCountBadge.visibility = View.GONE
            binding.btnDashDateFilter.visibility = View.VISIBLE
            binding.visitListContent.visibility = View.VISIBLE
            binding.visitEmptyContent.visibility = View.GONE
            bindDashboardData()
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
            // Drivers get the reason, not the generic copy. My Trips already
            // surfaced this; Home didn't, so a driver whose fleet fetch failed
            // (most often the dispatcher assigned the vehicle to a different
            // phone number than the one they log in with) saw a bare "No Trips
            // Available" with nothing to act on.
            val driverErr = viewModel.driverTripsError.value
            if (session.isDriverMode && !driverErr.isNullOrBlank()) {
                binding.tvVisitEmptyTitle.text = "No Driver Trips"
                // Name the number we actually searched with. "Check the phone
                // number" is useless without it — this way the driver can hold
                // the screen next to the dispatcher's Fleet row and see the
                // mismatch immediately, instead of it being guesswork.
                val phone = session.userPhone?.trim().orEmpty()
                binding.tvVisitEmptySubtitle.text = if (phone.isNotEmpty()) {
                    "$driverErr\n\nSearched for: $phone"
                } else {
                    driverErr
                }
            } else {
                // Match Frame 4 text exactly
                binding.tvVisitEmptyTitle.text = "No Trips Available"
                binding.tvVisitEmptySubtitle.text = "It looks like you don't have any meetings scheduled at the moment.\nThis space will be updated as new meetings are added!"
            }
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

        // Reset the infinite-scroll window whenever the underlying list or tab
        // changes (a new fetch / tab switch) so a fresh view starts at page 1.
        val baseSignature = buildString {
            append(state.hasOpenSession).append('|').append(selectedTab).append('|')
            visits.forEach { append(it.id).append(':').append(it.status).append(';') }
        }
        if (baseSignature != lastVisitBaseSignature) {
            lastVisitBaseSignature = baseSignature
            homeTripLimit = HOME_TRIP_PAGE
        }
        homeTripTotal = displayCount
        val shown = minOf(homeTripLimit, displayCount)

        // Only re-inflate when the visible window actually changed. The
        // childCount check forces a rebuild after view recreation (fresh empty
        // container) and after the window grows on scroll (same list, more rows).
        val signature = "$baseSignature#$shown"
        if (signature == lastVisitRenderSignature &&
            binding.visitListContent.childCount == shown
        ) {
            return
        }
        lastVisitRenderSignature = signature

        binding.visitListContent.removeAllViews()

        visits.take(shown).forEachIndexed { index, visit ->
            val itemView = createVisitItem(visit, index, displayCount, state.hasOpenSession)
            binding.visitListContent.addView(itemView)
        }
    }

    /** Grow the Today's Trip window when the user nears the bottom, then
     *  re-render the (now larger) window. No-op for dashboard/admin views and
     *  when the whole list is already shown. Called from the scroll listener. */
    private fun maybeExtendTripWindow(scrollY: Int) {
        if (session.canViewVpDashboard() || session.isAdmin) return
        if (homeTripLimit >= homeTripTotal) return
        val b = _binding ?: return
        val child = b.homeContent.getChildAt(0) ?: return
        val rowH = (84 * resources.displayMetrics.density).toInt()
        val distanceToBottom = child.measuredHeight - (scrollY + b.homeContent.measuredHeight)
        if (distanceToBottom <= HOME_TRIP_NEAR_END_ROWS * rowH) {
            homeTripLimit += HOME_TRIP_PAGE
            (viewModel.uiState.value as? HomeUiState.Loaded)?.let { renderVisitCard(it) }
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

    private fun dashCacheKey(date: String?): String =
        "dash:${session.staffId.orEmpty()}:${date ?: "today"}"

    private fun loadVpDashboard(force: Boolean = false) {
        if (vpDashboardLoading && !force) return
        vpDashboardLoading = true
        val requestedDate = vpSelectedDate
        // Cache-first: paint the last-known numbers immediately so the overview
        // never sits blank while the network round-trips.
        if (vpDashboardData == null) {
            LocalCache.get<com.manjugroups.m_connect.network.MobileDashboardResponse>(
                requireContext(), dashCacheKey(requestedDate),
            )?.let { cached ->
                vpDashboardData = cached
                bindDashboardData()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Prefer the company-wide aggregate route; when it isn't deployed
            // (404), fall back to counting what the live per-screen endpoints
            // already expose (CP + Site visits for the selected day).
            val resp = runCatching {
                api.getMobileDashboard(session.bearerToken, requestedDate)
            }.getOrNull()
            // Drop the pull-to-refresh spinner the moment the primary (fast)
            // aggregate call resolves — do NOT hold it through the slow
            // client-side computeVpFallback below, which is what left the
            // spinner spinning for a long time on slower networks.
            if (view != null) _binding?.homeRefresh?.dismissRefresh()
            val data = if (resp?.success == true) resp
                else runCatching { computeVpFallback(requestedDate ?: indiaToday()) }.getOrNull()
            vpDashboardLoading = false
            if (view == null) return@launch
            // The user picked a different date while this was in flight —
            // that newer load owns the render; drop this stale result.
            if (requestedDate != vpSelectedDate) return@launch
            if (data != null) {
                vpDashboardData = data
                // Only the deployed aggregate route is worth caching; the
                // thin CP/SV fallback would poison the next instant paint.
                if (resp?.success == true) {
                    LocalCache.put(
                        requireContext(), dashCacheKey(requestedDate),
                        data, System.currentTimeMillis(),
                    )
                }
                bindDashboardData()
            }
        }
    }

    /** Date-filter picker for the dashboard: every tile re-fetches for the
     *  picked day; picking today returns to the live "Today's Overview".
     *  Uses the app's own calendar sheet (single-select mode), not the stock
     *  Android dialog. */
    private fun showDashDatePicker() {
        val initial = vpSelectedDate ?: indiaToday()
        com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet.newInstance(
            title = "Dashboard Date",
            subtitle = "Pick a day to view its overview",
            initialFrom = initial,
            initialTo = initial,
            resultKey = DASH_DATE_RESULT_KEY,
            singleSelect = true,
        ).showOnce(parentFragmentManager, "dash_date_picker")
    }

    /** Header + date-chip copy for the current dashboard date. */
    private fun applyDashHeader() {
        val day = vpSelectedDate
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
    private suspend fun computeVpFallback(date: String): com.manjugroups.m_connect.network.MobileDashboardResponse {
        val cp = try { geoApi.getMyMarketingCpVisits(session.bearerToken, date, date) } catch (_: Exception) { null }
        val sv = try { geoApi.getMySiteVisits(session.bearerToken, date, date) } catch (_: Exception) { null }
        return com.manjugroups.m_connect.network.MobileDashboardResponse(
            success = true,
            totalStaff = 0,
            present = 0,
            absent = 0,
            cpVisitsFixed = cp?.visits?.size ?: 0,
            svVisitsFixed = sv?.visits?.size ?: 0
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
    private fun renderVpDashboard() {
        // The VP dashboard is rendered statically via layoutHr/layoutMarketing;
        // bindDashboardData() writes the live counts into those views.
        bindDashboardData()
    }

    /** Writes the current [vpDashboardData] counts into the static HR +
     *  Marketing overview layouts. Safe to call any time (no-op until data
     *  lands) so a late network result still repaints without waiting for the
     *  next visits emit. */
    private fun bindDashboardData() {
        val root = _binding?.root ?: return
        val d = vpDashboardData ?: return
        fun set(id: Int, value: Int) {
            root.findViewById<android.widget.TextView>(id)?.text = value.toString()
        }
        set(R.id.numStaff, d.totalStaff)
        set(R.id.numPresent, d.present)
        set(R.id.numAbsent, d.absent)
        set(R.id.numLeave, d.leave)
        set(R.id.numCalls, d.totalCalls)
        set(R.id.numIncoming, d.incomingCalls)
        set(R.id.numOutgoing, d.outboundCalls)
        set(R.id.numHot, d.hot)
        set(R.id.numWarm, d.warm)
        set(R.id.numCold, d.cold)
        set(R.id.numSv, d.svVisitsFixed)
        set(R.id.numCp, d.cpVisitsFixed)

        // Trend pills. These were hardcoded strings with no ids ("↗ 12% vs
        // last week" sat under a value of 0), so they were decoration that
        // read as data. Now driven off the same-weekday-last-week baseline,
        // and hidden outright when there is nothing true to say.
        bindTrend(root, R.id.trendCalls, d.totalCalls, d.prevTotalCalls)
        bindTrend(root, R.id.trendIncoming, d.incomingCalls, d.prevIncomingCalls)
        bindTrend(root, R.id.trendOutgoing, d.outboundCalls, d.prevOutboundCalls)

        // Present-vs-headcount needs no backend support — both numbers are
        // already on this response.
        root.findViewById<android.widget.TextView>(R.id.trendPresent)?.let { pill ->
            if (d.totalStaff <= 0) {
                pill.visibility = View.GONE
            } else {
                pill.visibility = View.VISIBLE
                val pct = (d.present * 100.0 / d.totalStaff).roundToInt()
                pill.text = "$pct% of Total"
            }
        }
    }

    /**
     * Render a "vs last week" pill, or hide it. [previous] is null on a backend
     * that predates the comparison fields — in that case showing any delta
     * would be a guess, so the pill goes away.
     */
    private fun bindTrend(root: View, pillId: Int, current: Int, previous: Int?) {
        val pill = root.findViewById<android.widget.TextView>(pillId) ?: return
        if (previous == null || (previous == 0 && current == 0)) {
            pill.visibility = View.GONE
            return
        }
        pill.visibility = View.VISIBLE
        pill.text = when {
            previous == 0 -> "↗ new vs last week"
            else -> {
                val delta = (current - previous) * 100.0 / previous
                val arrow = when {
                    delta > 0 -> "↗"
                    delta < 0 -> "↘"
                    else -> "→"
                }
                "$arrow ${abs(delta).roundToInt()}% vs last week"
            }
        }
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
            .commitOnce()
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
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived",
            "on_site", "on-site", "picked_from_site"
        )
        // A visit that was never started and whose slot has passed is expired —
        // the source of the "still shows Start after the date is lost" bug.
        val isExpired = !isCompleted && !isInProgress &&
            com.manjugroups.m_connect.util.VisitExpiry.isExpired(
                visit.scheduledDate, visit.scheduledStartTime, isDone = false,
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
                    "picked_from_site" -> "Picked from Site"
                    else -> "Enroute"
                }
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_progress)
                statusText.setTextColor(android.graphics.Color.parseColor("#B54708"))
                action.text = when (status) {
                    "arrived" -> "Complete Trip"
                    "on_site", "on-site" -> "Picked from Site"
                    "picked_from_site" -> "End Trip"
                    else -> "Enroute"
                }
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_progress)
                action.setTextColor(android.graphics.Color.parseColor("#B54708"))
                actionIcon.visibility = View.GONE
                eta.text = when (status) {
                    "arrived" -> "At client place"
                    "on_site", "on-site" -> "At site"
                    "picked_from_site" -> "Returning"
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
            isExpired -> {
                statusText.text = "Expired"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_done)
                statusText.setTextColor(android.graphics.Color.parseColor("#B42318"))
                action.text = "Expired"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#B42318"))
                actionIcon.visibility = View.GONE
                eta.text = "Date passed"
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

        if (isExpired) {
            // The slot has passed — the backend rejects any trip action on a
            // stale date, so keep the card inert rather than opening a flow
            // that dead-ends. A tap just explains why.
            val explain: (View) -> Unit = {
                android.widget.Toast.makeText(
                    requireContext(),
                    "This visit's scheduled date has passed.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            itemView.isClickable = true
            itemView.setOnClickListener(explain)
            actionBtn.isClickable = true
            actionBtn.setOnClickListener(explain)
        } else if (session.isDriverMode) {
            if (isCompleted) {
                val openDetail: (View) -> Unit = {
                    DriverTripCompletedBottomSheet.newInstance(visit.id)
                        .showOnce(parentFragmentManager, "driver_trip_completed")
                }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openDetail)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openDetail)
            } else if (!isInProgress && canStartTrip) {
                // Card and button both open the trip detail first — address,
                // stage progress, then Start Trip — rather than dropping the
                // driver straight into the km/photo sheet.
                val openTripDetail: (View) -> Unit = { openDriverTripDetail(visit) }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(openTripDetail)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openTripDetail)
            } else if (!isInProgress && !canStartTrip) {
                // The card says "Clock In First" — so take them there instead of
                // opening trip navigation they can't act on yet. Same redirect
                // CP Visits already uses for this state.
                val goClockIn: (View) -> Unit = { openClockInForTrip() }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.setOnClickListener(goClockIn)
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(goClockIn)
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

    private fun openDriverTripDetail(visit: TodayVisit) {
        val whenText = listOfNotNull(
            visit.scheduledDate.takeIf { it.isNotBlank() },
            visit.scheduledStartTime?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        // The detail screen can't rebuild the navigation args, so it asks us
        // to do it when the driver taps Continue.
        parentFragmentManager.setFragmentResultListener(
            DriverTripDetailFragment.RESULT_OPEN_NAVIGATION,
            viewLifecycleOwner,
        ) { _, bundle ->
            val id = bundle.getString("visitId")
            if (id == visit.id) openTripNavigationForVisit(visit)
        }

        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                R.id.fragmentContainer,
                DriverTripDetailFragment.newInstance(
                    visitId = visit.id,
                    title = visit.placeName ?: "Site visit",
                    whenText = whenText,
                    address = visit.placeAddress.orEmpty(),
                    status = visit.status,
                    scheduledDate = visit.scheduledDate,
                    lat = visit.placeLat,
                    lng = visit.placeLng,
                ),
            )
            .addToBackStack(null)
            .commit()
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
            lmoName = visit.lmoName,
            deadline = com.manjugroups.m_connect.util.VisitDeadline.format(
                visit.scheduledDate,
                visit.scheduledEndTime ?: visit.scheduledStartTime,
            ),
        )
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commitOnce()
    }

    private fun openCompletedVisitDetail(visit: TodayVisit) {
        val fragment = com.manjugroups.m_connect.ui.marketing
            .CompletedVisitDetailFragment.forVisit(visit)
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commitOnce()
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
            .showOnce(parentFragmentManager, "cp_visit_complete")
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
            .commitOnce()
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

    private fun setupOverviewTabs() {
        val tabHr = _binding?.root?.findViewById<TextView>(R.id.tabHr) ?: return
        val tabMarketing = _binding?.root?.findViewById<TextView>(R.id.tabMarketing) ?: return
        val layoutHr = _binding?.root?.findViewById<View>(R.id.layoutHr) ?: return
        val layoutMarketing = _binding?.root?.findViewById<View>(R.id.layoutMarketing) ?: return

        tabHr.setOnClickListener {
            layoutHr.visibility = View.VISIBLE
            layoutMarketing.visibility = View.GONE
            
            tabHr.setBackgroundResource(R.drawable.bg_loans_segment_active)
            tabHr.setTextColor(Color.parseColor("#FFFFFF"))
            tabHr.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_semibold)
            
            tabMarketing.setBackgroundResource(0)
            tabMarketing.setTextColor(Color.parseColor("#475467"))
            tabMarketing.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_medium)
        }

        tabMarketing.setOnClickListener {
            layoutHr.visibility = View.GONE
            layoutMarketing.visibility = View.VISIBLE
            
            tabMarketing.setBackgroundResource(R.drawable.bg_loans_segment_active)
            tabMarketing.setTextColor(Color.parseColor("#FFFFFF"))
            tabMarketing.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_semibold)
            
            tabHr.setBackgroundResource(0)
            tabHr.setTextColor(Color.parseColor("#475467"))
            tabHr.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.inter_medium)
        }
    }

    /**
     * Send a driver whose trip is blocked on attendance to the clock-in screen.
     * Mirrors CpVisitsFragment's handling of the same state.
     */
    private fun openClockInForTrip() {
        android.widget.Toast.makeText(
            requireContext(),
            "Clock in to start your trip",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(
                R.id.fragmentContainer,
                com.manjugroups.m_connect.ui.hr.ClockInAreaFragment(),
            )
            .addToBackStack(null)
            .commitOnce()
    }

    // ── Fleet administrator dispatch queue ──────────────────────────────
    //
    // "Driver • Administration" gets the same Today's Trip surface as a
    // Transport driver, but filled with the allocation queue instead of their
    // own journeys: Pending / Assigned / Completed, each card carrying the
    // Allocate action. Rendered inline here rather than behind a separate
    // screen, so the main screen IS the dispatch view.
    private val fleetApi by lazy {
        com.manjugroups.m_connect.network.TravelDeskApi.create()
    }
    private var fleetPending: List<com.manjugroups.m_connect.network.TravelDeskTrip> = emptyList()
    private var fleetAssigned: List<com.manjugroups.m_connect.network.TravelDeskTrip> = emptyList()
    private var fleetCompleted: List<com.manjugroups.m_connect.network.TravelDeskTrip> = emptyList()
    private var fleetExpired: List<com.manjugroups.m_connect.network.TravelDeskTrip> = emptyList()
    private var fleetVehicles: List<com.manjugroups.m_connect.network.TravelDeskVehicle> = emptyList()
    private var fleetLoadJob: kotlinx.coroutines.Job? = null
    private var fleetError: String? = null

    private fun loadFleetDispatch() {
        if (!session.isFleetAdminDriver) return
        val token = session.bearerToken
        if (token.isBlank()) return
        fleetLoadJob?.cancel()
        fleetLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pending = fleetApi.listMmsPending(token)
                val assigned = fleetApi.listMmsAssigned(token)
                val vehicles = fleetApi.listMmsVehicles(token)
                if (_binding == null) return@launch
                fleetError = null
                // Rows with no id can't be opened or allocated; drop them
                // rather than let one odd record break the screen.
                fleetPending = pending.rows.filter { !it.id.isNullOrBlank() }
                val assignedRows = assigned.rows.filter { !it.id.isNullOrBlank() }
                // A dispatch trip is done when the driver has ended it
                // (travelDeskEndedAt), the same signal the backend's "complete"
                // sub-tab uses. status stays "scheduled" the whole way for a
                // travel-desk trip, so testing it here never matched and left
                // the Completed tab permanently empty.
                val isDone = { t: com.manjugroups.m_connect.network.TravelDeskTrip ->
                    t.travelDeskEndedAt != null ||
                        (t.status ?: "").equals("completed", ignoreCase = true)
                }
                // A trip that was never started and whose slot has passed is
                // expired — it can't run, so it shouldn't sit in Assigned as if
                // it were still live. It moves to the Completed tab, badged.
                val isExpired = { t: com.manjugroups.m_connect.network.TravelDeskTrip ->
                    !isDone(t) && t.travelDeskStartedAt == null &&
                        com.manjugroups.m_connect.util.VisitExpiry.isExpired(
                            t.scheduledDate, t.scheduledTime ?: t.pickupTime,
                            isDone = false,
                        )
                }
                fleetAssigned = assignedRows.filterNot(isDone).filterNot(isExpired)
                fleetExpired = assignedRows.filter(isExpired)
                fleetCompleted = assignedRows.filter(isDone) + fleetExpired
                fleetVehicles = vehicles.rows
                renderFleetDispatch()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                fleetError = when {
                    e is java.net.UnknownHostException ||
                        e is java.net.ConnectException ||
                        e is java.net.SocketTimeoutException ->
                        "No internet connection. Pull to refresh."
                    (e as? retrofit2.HttpException)?.code() == 403 ->
                        "You don't have fleet permissions (marketing.fleet.view)."
                    (e as? retrofit2.HttpException)?.code() == 404 ->
                        "Fleet dispatch isn't available on this server yet."
                    else -> e.message ?: "Couldn't load trips"
                }
                renderFleetDispatch()
            }
        }
    }

    private fun fleetRowsForTab(): List<com.manjugroups.m_connect.network.TravelDeskTrip> =
        when (selectedTab) {
            "upcoming" -> fleetAssigned      // tab 2 = Assigned
            "completed" -> fleetCompleted
            else -> fleetPending             // tab 1 = Pending
        }

    private fun renderFleetDispatch() {
        if (_binding == null || !session.isFleetAdminDriver) return
        val rows = fleetRowsForTab()
        val c = binding.visitListContent

        binding.tvVisitCountBadge.visibility =
            if (rows.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvVisitCountBadge.text = rows.size.toString()

        if (rows.isEmpty()) {
            c.removeAllViews()
            c.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.VISIBLE
            binding.tvVisitEmptyTitle.text = fleetError?.let { "Couldn't load trips" }
                ?: when (selectedTab) {
                    "upcoming" -> "No assigned trips"
                    "completed" -> "No completed trips"
                    else -> "Nothing to allocate"
                }
            binding.tvVisitEmptySubtitle.text = fleetError
                ?: "Trips waiting for a vehicle will appear here."
            return
        }

        binding.visitEmptyContent.visibility = View.GONE
        c.visibility = View.VISIBLE
        c.removeAllViews()
        val inflater = layoutInflater
        rows.forEach { trip ->
            val card = com.manjugroups.m_connect.databinding.ItemAdminFleetTripBinding
                .inflate(inflater, c, false)
            card.tvTripTime.text = listOfNotNull(
                trip.scheduledDate,
                (trip.scheduledTime ?: trip.pickupTime)?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            card.tvTripAddress.text = trip.pickupAddress?.trim()?.ifBlank { null }
                ?: trip.project?.name?.let { "Project: $it" }
                ?: "Address pending"
            card.tvAttendeesTag.text = trip.expectedAttendeeCount?.toString() ?: "—"
            // LMO (telecaller who created the visit) in place of the vehicle tag.
            card.tvLmoTag.text = trip.lmoName?.trim()?.takeIf { it.isNotBlank() }
                ?.let { "LMO: $it" } ?: "LMO —"

            val isPending = selectedTab != "upcoming" && selectedTab != "completed"
            // Expired trips live in the Completed tab but keep their own badge.
            val isExpiredRow = fleetExpired.any { it.id == trip.id }
            card.tvTripStatus.text = when {
                isExpiredRow -> "Expired"
                selectedTab == "completed" -> "Complete"
                selectedTab == "upcoming" -> "Assigned"
                else -> "Pending"
            }
            if (isExpiredRow) {
                card.tvTripStatus.setTextColor(android.graphics.Color.parseColor("#B42318"))
            }
            // Allocate only makes sense while the trip has no vehicle.
            card.btnAllocate.visibility = if (isPending) View.VISIBLE else View.GONE
            if (isPending) {
                card.btnAllocate.setOnClickListener { openFleetAllocate(trip) }
                card.assignmentInfo.visibility = View.GONE
            } else {
                // Assigned / Completed: surface who the trip went to and how
                // far it's progressed, so the admin can track it — whether it
                // was allocated to the in-house fleet or an external agency.
                bindFleetAssignmentInfo(card, trip)
            }
            c.addView(card.root)
        }
    }

    /**
     * Fill the assignment-tracking block on an Assigned/Completed dispatch
     * card. Works for both allocation sources: the in-house MMS fleet (a
     * vehicle + its driver, no agency) and an external agency (agency name +
     * the driver they allotted). Progress is read off the travel-desk stamps,
     * which both flows write.
     */
    private fun bindFleetAssignmentInfo(
        card: com.manjugroups.m_connect.databinding.ItemAdminFleetTripBinding,
        trip: com.manjugroups.m_connect.network.TravelDeskTrip,
    ) {
        card.assignmentInfo.visibility = View.VISIBLE

        val vehicleNo = trip.vehicle?.vehicleNumber?.takeIf { it.isNotBlank() }
        val agency = trip.travelAgency?.name?.takeIf { it.isNotBlank() }
        card.tvAssignedVehicle.text = listOfNotNull(
            vehicleNo ?: "Vehicle pending",
            agency?.let { "· $it" },
        ).joinToString(" ")

        val driver = trip.driverName?.takeIf { it.isNotBlank() }
        val phone = trip.driverPhone?.takeIf { it.isNotBlank() }
        card.tvAssignedDriver.text = when {
            driver != null && phone != null -> "$driver · $phone"
            driver != null -> driver
            else -> "Driver not set"
        }

        card.tvAssignedProgress.text = when {
            trip.travelDeskEndedAt != null -> "Dropped"
            trip.travelDeskPickedFromSiteAt != null -> "Picked from site"
            trip.travelDeskOnSiteAt != null -> "On site"
            trip.travelDeskStartedAt != null -> "Picked from CP"
            trip.travelDeskArrivedAt != null -> "Reached client"
            else -> "Awaiting pickup"
        }
    }

    private fun openFleetAllocate(
        trip: com.manjugroups.m_connect.network.TravelDeskTrip,
    ) {
        val siteVisitId = trip.id?.takeIf { it.isNotBlank() } ?: return
        val options = fleetVehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .map {
                com.manjugroups.m_connect.ui.library.AllocateVehicleOption(
                    vehicleId = it.id,
                    label = listOfNotNull(it.vehicleNumber, it.type)
                        .joinToString(" · ")
                        .ifBlank { "Vehicle" },
                    defaultDriverName = it.defaultDriverName,
                    defaultDriverPhone = it.defaultDriverPhone,
                )
            }
        if (options.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(), "No active vehicles to allocate.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        com.manjugroups.m_connect.ui.library.AllocateVehicleBottomSheet
            // Home dispatch is always the internal MMS fleet —
            // marketing.vehicles.list returns own + internal-agency
            // vehicles only — so per-trip pricing applies. The driver is fixed
            // to the vehicle's default (not editable) and the pickup time is
            // imported from the SV's scheduled time.
            .newInstance(
                options,
                showPricing = true,
                lockDriverToVehicleDefault = true,
                fixedPickupTime = (trip.scheduledTime ?: trip.pickupTime)
                    ?.takeIf { it.isNotBlank() },
            ) { result ->
                submitFleetAllocate(siteVisitId, result)
            }
            .showOnce(parentFragmentManager, "home_allocate_vehicle")
    }

    private fun submitFleetAllocate(
        siteVisitId: String,
        result: com.manjugroups.m_connect.ui.library.AllocateVehicleResult,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                fleetApi.allocateMms(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.AllocateTripRequest(
                        siteVisitId = siteVisitId,
                        vehicleId = result.vehicleId,
                        pickupTime = result.pickupTime,
                        pricingMode = result.pricingMode,
                        driverName = result.driverName,
                        driverPhone = result.driverPhone,
                        kmRate = if (result.pricingMode == "km") result.amount else null,
                        packageAmount =
                            if (result.pricingMode == "package") result.amount else null,
                    ),
                )
            }.getOrNull()
            if (_binding == null) return@launch
            if (resp?.success == true) {
                android.widget.Toast.makeText(
                    requireContext(), "Trip allocated.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                loadFleetDispatch()
            } else {
                android.widget.Toast.makeText(
                    requireContext(), resp?.error ?: "Allocation failed.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setupDriverTabs() {
        val clickListener = View.OnClickListener { v ->
            selectedTab = when (v.id) {
                R.id.tabUpcoming -> "upcoming"
                R.id.tabCompleted -> "completed"
                else -> "all"
            }
            updateTabSelectionVisuals()
            if (session.isFleetAdminDriver) {
                renderFleetDispatch()
            } else {
                (viewModel.uiState.value as? HomeUiState.Loaded)?.let { renderVisitCard(it) }
            }
        }
        binding.tabAll.setOnClickListener(clickListener)
        binding.tabUpcoming.setOnClickListener(clickListener)
        binding.tabCompleted.setOnClickListener(clickListener)

        // One rounded track with three equal segments — the same segmented
        // control the fleet screen uses. Transport drivers used to get three
        // loose pills instead, which read as a different control for the same
        // job; only the labels differ by role now.
        binding.layoutDriverTabs.setBackgroundResource(
            R.drawable.bg_admin_trips_tabs_container,
        )
        val tabTrackPad = dpx(4)
        binding.layoutDriverTabs.setPadding(
            tabTrackPad, tabTrackPad, tabTrackPad, tabTrackPad,
        )
        if (session.isFleetAdminDriver) {
            binding.tabAll.text = "Pending"
            binding.tabUpcoming.text = "Assigned"
            binding.tabCompleted.text = "Completed"
        }
        listOf(binding.tabAll, binding.tabUpcoming, binding.tabCompleted)
            .forEach { tab ->
                tab.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }

        updateTabSelectionVisuals()
    }

    private fun updateTabSelectionVisuals() {
        val white = android.graphics.Color.WHITE
        val tabs = listOf(
            binding.tabAll to "all",
            binding.tabUpcoming to "upcoming",
            binding.tabCompleted to "completed",
        )

        run {
            // Segmented control: ONE white track (the parent), with only the
            // active segment drawing a filled pill. Inactive segments must have
            // no background at all — giving them the bordered "inactive pill"
            // drawable is what made this read as three separate pills instead
            // of one switch. Mirrors AdminFleetTripsFragment.applyFilter.
            val activeText = white
            val inactiveText = android.graphics.Color.parseColor("#475467")
            tabs.forEach { (tab, key) ->
                val isActive = selectedTab == key
                if (isActive) {
                    tab.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
                    tab.backgroundTintList = android.content.res.ColorStateList
                        .valueOf(android.graphics.Color.parseColor("#0B61CA"))
                    tab.setTextColor(activeText)
                } else {
                    tab.setBackgroundResource(0)
                    tab.backgroundTintList = null
                    tab.setTextColor(inactiveText)
                }
                tab.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(),
                    if (isActive) R.font.inter_semibold else R.font.inter_medium,
                )
                val vPad = dpx(8)
                tab.setPadding(0, vPad, 0, vPad)
            }
        }
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
                .commitOnce()
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
