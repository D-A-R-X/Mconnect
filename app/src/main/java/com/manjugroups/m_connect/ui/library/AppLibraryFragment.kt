package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.IamUpdateBus
import com.manjugroups.m_connect.databinding.FragmentAppLibraryBinding
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.PlaceholderFragment
import com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment
import com.manjugroups.m_connect.ui.hr.AttendanceReviewFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.marketing.CpVisitsFragment
import com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment
import com.manjugroups.m_connect.ui.marketing.bookings.BookingCreateFragment
import com.manjugroups.m_connect.ui.marketing.bookings.BookingsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import com.manjugroups.m_connect.ui.projects.ProjectExpensesFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.telecaller.DialerFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment
import com.manjugroups.m_connect.ui.library.collections.CollectionsFragment
import com.manjugroups.m_connect.ui.library.accounts.PostSalesVerificationFragment
import com.manjugroups.m_connect.ui.library.loans.LoanDeskFragment

class AppLibraryFragment : Fragment() {

    private var _binding: FragmentAppLibraryBinding? = null
    private val binding get() = _binding!!

    private enum class Filter { ALL, HR, MARKETING, PROJECT, LAND, FLEET, SALES, ACCOUNTS, SETTINGS }
    // Tracked so the IAM-bus listener can re-apply whichever filter
    // the user is currently looking at when tiles re-bind; otherwise
    // an IAM update would silently snap the filter back to ALL.
    private var currentFilter: Filter = Filter.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        movePillStripIntoScroll()
        setupClickActions()
        pruneEmptySections()
        setupFilterPills()
        setupScrollAnimation()
        applyFilter(Filter.ALL)

        // Live-update tiles whenever the IAM bus emits. The bus is
        // driven by MainActivity.onResume (every foreground) AND by
        // this fragment's own onResume below (every time the user
        // opens the Apps tab), so a permission change on the web is
        // reflected within seconds — no app restart needed.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                IamUpdateBus.updates.collect {
                    // SessionManager already has the new set by the
                    // time we get here (the bus writes it before
                    // emitting), so just re-bind tiles + prune empty
                    // sections + re-apply the current filter so
                    // visibility is recomputed.
                    setupClickActions()
                    pruneEmptySections()
                    applyFilter(currentFilter)
                }
            }
        }

        // App Library has no remote data of its own — the pull just
        // re-plays the entry animation so the user sees the screen
        // visibly "refresh" without any unnecessary network noise.
        binding.libraryRefresh.setupPullToRefresh {
            binding.sectionsContainer.post { playLibraryEntryAnimation() }
            binding.libraryRefresh.postDelayed({ binding.libraryRefresh.dismissRefresh() }, 600)
        }

        binding.sectionsContainer.post { playLibraryEntryAnimation() }
    }


    private fun playLibraryEntryAnimation() {
        if (_binding == null) return
        val density = binding.root.resources.displayMetrics.density
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val expoOut = android.view.animation.PathInterpolator(0.19f, 1f, 0.22f, 1f)

        // 1. Header text slides in from the left — mirrors the Home banner title cadence.
        binding.libraryHeaderContent.animate().cancel()
        binding.libraryHeaderContent.alpha = 0f
        binding.libraryHeaderContent.translationX = -28f * density
        binding.libraryHeaderContent.translationY = 0f
        binding.libraryHeaderContent.animate()
            .alpha(1f).translationX(0f)
            .setStartDelay(80L)
            .setDuration(420L)
            .setInterpolator(emphasized)
            .start()

        // 2. Illustration drifts in from the right with a subtle scale-up.
        binding.ivLibraryIllustration.animate().cancel()
        binding.ivLibraryIllustration.alpha = 0f
        binding.ivLibraryIllustration.translationX = 32f * density
        binding.ivLibraryIllustration.translationY = 0f
        binding.ivLibraryIllustration.scaleX = 0.88f
        binding.ivLibraryIllustration.scaleY = 0.88f
        binding.ivLibraryIllustration.animate()
            .alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(180L)
            .setDuration(520L)
            .setInterpolator(expoOut)
            .start()

        // 3. Filter pill strip rises in from below as the white curtain over the blue.
        val pill = binding.pillAllApps.parent as? View
        pill?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.translationY = 28f * density
            it.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(260L)
                .setDuration(460L)
                .setInterpolator(expoOut)
                .start()
        }

        // 4. Each pill icon scale-pops in after the strip arrives — gives the toolbar
        //    a small "items dropping into place" rhythm.
        val pillIcons = listOf(
            binding.pillAllAppsIcon, binding.pillHrIcon, binding.pillMarketingIcon,
            binding.pillProjectIcon, binding.pillLandIcon, binding.pillFleetIcon,
            binding.pillSalesIcon, binding.pillAccountsIcon, binding.pillSettingsIcon
        )
        pillIcons.forEachIndexed { i, icon ->
            icon.animate().cancel()
            icon.scaleX = 0.6f
            icon.scaleY = 0.6f
            icon.alpha = 0f
            icon.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(420L + i * 40L)
                .setDuration(320L)
                .setInterpolator(expoOut)
                .start()
        }

        // 5. Section cards rise from below in a stagger — the "ascending curtain" mirror
        //    of the Home curtain descending. 60ms stagger so 4 cards finish around 900ms.
        val container = binding.sectionsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = 36f * density
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(340L + i * 60L)
                .setDuration(460L)
                .setInterpolator(expoOut)
                .start()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.sectionsContainer.post { playLibraryEntryAnimation() }
        }
    }

    /**
     * The pill strip is declared inside the sticky blue header in the XML
     * (so the designer view shows the original layered look). At runtime
     * we move it OUT of the header into the scrollable column so it sits
     * BELOW the sticky header and scrolls up under it as the user pans
     * the cards.
     *
     * We also wrap the strip in a HorizontalScrollView so the filter
     * pills themselves can scroll horizontally — only 5 are shown at a
     * time and the rest (e.g. Settings when Land is added) become
     * reachable by swiping the strip left.
     */
    private fun movePillStripIntoScroll() {
        val strip = binding.libraryPillStrip
        val oldParent = strip.parent as? ViewGroup ?: return
        oldParent.removeView(strip)

        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        // Inside the HorizontalScrollView the strip must be wrap_content
        // so its content width can exceed the viewport and trigger
        // scrolling. Move the pill-strip background drawable onto the HSV
        // (it has rounded top corners that should bound the visible
        // viewport, not the inner over-scrolling content).
        val hsv = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = strip.background
        }
        strip.background = null
        strip.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        hsv.addView(strip)

        val scrollColumn = binding.sectionsContainer.parent as? ViewGroup ?: return
        val hsvLp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (79 * density).toInt(),
        )
        scrollColumn.addView(hsv, 0, hsvLp)

        // After layout, size each pill to exactly 1/5 of the visible HSV
        // width so 5 fit on screen regardless of device width, and the
        // 6th tab (currently Settings) becomes reachable by scrolling.
        val sizePills = { width: Int ->
            val pillWidth = width / 5
            if (pillWidth > 0) {
                listOf(
                    binding.pillAllApps,
                    binding.pillHr,
                    binding.pillMarketing,
                    binding.pillProject,
                    binding.pillLand,
                    binding.pillFleet,
                    binding.pillSales,
                    binding.pillAccounts,
                    binding.pillSettings,
                ).forEach { pill ->
                    val lp = pill.layoutParams as android.widget.LinearLayout.LayoutParams
                    lp.width = pillWidth
                    lp.weight = 0f
                    pill.layoutParams = lp
                }
            }
        }

        if (hsv.width > 0) {
            sizePills(hsv.width)
        } else {
            hsv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    val width = right - left
                    if (width > 0) {
                        hsv.removeOnLayoutChangeListener(this)
                        sizePills(width)
                    }
                }
            })
        }
    }

    /**
     * Collapsing-header effect for the sticky blue bar:
     *  - subtitle + illustration fade out as the user scrolls down
     *  - the header shrinks from 160dp → 108dp — only the dead space
     *    BELOW the title is removed, so the title itself stays exactly
     *    where it was (60dp from header top, safely below the status
     *    bar / notch).
     *  - the pill strip (now a scroll-child) slides up under the header.
     * Effect saturates within ~140dp of scroll.
     *
     * Important: the title is NOT translated. Pulling it up made it
     * collide with the system clock / notch on full-bleed devices —
     * which is what you saw in the broken screenshot. The header
     * collapses from the bottom only.
     */
    /**
     * "Panel slides up over fixed header" scroll effect.
     *  - The blue header stays at full size and full opacity.
     *  - The SwipeRefresh + scroll column translates UP as the user
     *    scrolls; once the user has scrolled past the header height,
     *    the panel has fully overlaid the header.
     *  - Header background's bottom corners interpolate 24dp → 0dp so
     *    the bottom edge straightens into a sharp rectangle as the
     *    panel slides up.
     */
    private fun setupScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxPanelRadiusPx = 30f * density

        // Blue header — full rectangular background (no rounded bottom corners).
        binding.libraryHeaderFrame.applyShrinkableBlueHeaderBackground()

        // Grey background card — rounded TOP corners + grey bg applied to
        // `libraryWhitePanel`, which lives INSIDE the NestedScrollView.
        // The panel IS the scrolling content, so it moves up at
        // exactly 1× rate (no parallax, no shrinking, no separate
        // translation). The ancestors all set clipChildren=false in
        // XML so the rounded top edge can draw OUTSIDE the panel into
        // the blue header's area — that's how the panel visually
        // "overlays" the blue as it scrolls up.
        val whiteCardBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.parseColor("#F1F3F8"))
            cornerRadii = floatArrayOf(
                maxPanelRadiusPx, maxPanelRadiusPx, // top-left
                maxPanelRadiusPx, maxPanelRadiusPx, // top-right
                0f, 0f,                              // bottom-right
                0f, 0f,                              // bottom-left
            )
        }
        binding.libraryWhitePanel.background = whiteCardBg
    }

    private fun setupClickActions() {
        // Pull effective IAM permissions once per setup pass. Every
        // App Library tile then gates its click + visibility on the
        // matching permission key — mirrors the web's per-designation
        // IAM model so a staff who can't see a feature on web can't
        // see its mobile tile either. Mobile uses GONE rather than
        // disabled-grey because the App Library is a discoverable
        // catalog; if a permission lapses, the tile vanishes rather
        // than tempting the user to tap it. bindIamEntry handles both
        // visibility and click wiring atomically.
        val session = SessionManager(requireContext())
        val hasAny = { keys: List<String> -> keys.any { session.hasPermission(it) } }

        // ── HR ────────────────────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemHrAttendance,
            allowed = hasAny(listOf("attendance.view", "attendance.viewAll")),
        ) { openScreen(AttendanceHistoryFragment()) }
        bindIamEntry(
            row = binding.itemHrLeave,
            allowed = hasAny(listOf("leaves.view", "leaves.viewAll", "leaves.approve")),
        ) { openScreen(LeavesFragment()) }
        bindIamEntry(
            row = binding.itemHrPermissions,
            allowed = hasAny(listOf("permissions.view", "permissions.viewAll", "permissions.approve")),
        ) { openScreen(PermissionsFragment()) }
        bindIamEntry(
            row = binding.itemHrLoans,
            allowed = hasAny(listOf("loans.view", "loans.manage", "loans.approve")),
        ) { openScreen(com.manjugroups.m_connect.ui.library.loans.LoansFragment()) }

        // ── Marketing ─────────────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemMarketingCpVisits,
            allowed = session.hasPermission("marketing.cpVisits.view"),
        ) { openScreen(CpVisitsFragment()) }
        bindIamEntry(
            row = binding.itemMarketingSiteVisits,
            allowed = session.hasPermission("marketing.siteVisits.view"),
        ) { openScreen(SiteVisitsFragment()) }
        // Dialer is gated as Coming Soon — IAM checks still decide
        // whether the row appears (so unauthorised users don't see it),
        // but the row itself is non-tappable until the feature ships.
        // Layout swaps the chevron for a "Coming soon" pill; we mirror
        // that here by clearing the click handler + isClickable.
        bindIamEntry(
            row = binding.itemMarketingDialer,
            allowed = hasAny(listOf("telecaller.dashboard", "telecaller.calls")),
        ) { /* no-op — Dialer is coming soon */ }
        binding.itemMarketingDialer.isClickable = false
        binding.itemMarketingDialer.isFocusable = false
        binding.itemMarketingDialer.setOnClickListener(null)
        binding.itemMarketingDialer.background = null
        // Leads is gated as Coming Soon — same treatment as Dialer / Inventory.
        // IAM still decides whether the row appears, but it's non-tappable
        // until the feature ships. Layout swaps the chevron for a "Coming
        // soon" pill; we mirror that here by clearing the click handler +
        // isClickable.
        bindIamEntry(
            row = binding.itemMarketingMyLeads,
            allowed = hasAny(listOf(
                "telecaller.externalLeads.viewOwn",
                "telecaller.externalLeads.viewAll",
            )),
        ) { /* no-op — Leads is coming soon */ }
        binding.itemMarketingMyLeads.isClickable = false
        binding.itemMarketingMyLeads.isFocusable = false
        binding.itemMarketingMyLeads.setOnClickListener(null)
        binding.itemMarketingMyLeads.background = null
        // Inventory is gated as Coming Soon — same treatment as Dialer.
        // IAM still decides whether the row appears, but it's non-tappable
        // until the feature ships. Layout swaps the chevron for a "Coming
        // soon" pill; we mirror that here by clearing the click handler +
        // isClickable.
        bindIamEntry(
            row = binding.itemMarketingInventory,
            allowed = session.hasPermission("projects.view"),
        ) { /* no-op — Inventory is coming soon */ }
        binding.itemMarketingInventory.isClickable = false
        binding.itemMarketingInventory.isFocusable = false
        binding.itemMarketingInventory.setOnClickListener(null)
        binding.itemMarketingInventory.background = null
        // Booking tile now opens the list screen (the "+" inside the list
        // routes to the create form). Permission to merely view the list is
        // marketing.bookings.view; the create button inside the list is
        // gated separately on marketing.bookings.create. Showing the tile
        // if the user has EITHER permission preserves access for creators
        // even when their org hasn't granted the view scope.
        bindIamEntry(
            row = binding.itemMarketingNewBooking,
            allowed = session.hasPermission("marketing.bookings.view") ||
                session.hasPermission("marketing.bookings.create"),
        ) { openScreen(BookingsFragment.newInstance()) }

        // Managers only — surfaced when the backend grants attendance.approve.
        // Show the matching divider so the row joins the HR card cleanly when
        // it's visible, and stays invisible (no orphan separator) when it's not.
        val canApproveAttendance = session.hasPermission("attendance.approve")
        binding.dividerHrAttendanceReview.visibility =
            if (canApproveAttendance) View.VISIBLE else View.GONE
        bindIamEntry(
            row = binding.itemHrAttendanceReview,
            allowed = canApproveAttendance,
        ) { openScreen(AttendanceReviewFragment.newInstance()) }

        // ── Project ───────────────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemProjectTasks,
            allowed = hasAny(listOf("tasks.view", "tasks.viewAll", "tasks.create")),
        ) { openScreen(TasksFragment()) }
        bindIamEntry(
            row = binding.itemProjectExpenses,
            allowed = hasAny(listOf(
                "projects.expenses.view",
                "projects.expenses.create",
                "projects.expenses.approve",
            )),
        ) { openScreen(ProjectExpensesFragment()) }

        // Settings → Profile. Every authenticated staff edits their own
        // profile; no IAM gate.
        binding.itemSettings.setOnClickListener { openScreen(ProfileFragment()) }

        // ── Land Procurement ──────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemLandInspection,
            allowed = hasAny(listOf(
                "land.inspect",
                "land.inspection.view",
                "land.inspection.edit",
                "land.view",
            )),
        ) { openScreen(com.manjugroups.m_connect.ui.library.land.LandInspectionFragment()) }
        bindIamEntry(
            row = binding.itemLandQueries,
            // No dedicated `land.queries.*` permission key — gate on the
            // section's view permission. Queries are a sub-feature of
            // the inspection workflow.
            allowed = hasAny(listOf("land.view", "land.inspect", "land.inspection.view")),
        ) { openScreen(com.manjugroups.m_connect.ui.library.land.QueriesFragment()) }

        // ── Fleet Management ──────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemFleetMyTrips,
            allowed = session.isDriverMode || session.hasPermission("marketing.siteVisits.view") || session.hasPermission("fleet.view"),
        ) {
            openScreen(MyTripsFragment())
        }

        // ── Post Sales ────────────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemSalesCollections,
            allowed = true,
        ) { openScreen(CollectionsFragment()) }
        bindIamEntry(
            row = binding.itemSalesLoanDesk,
            allowed = true,
        ) { openScreen(LoanDeskFragment()) }

        // ── Accounts ───────────────────────────────────────────────────────
        bindIamEntry(
            row = binding.itemAccountsPostSalesVerification,
            allowed = true,
        ) { openScreen(PostSalesVerificationFragment()) }
    }

    private fun setupFilterPills() {
        binding.pillAllApps.setOnClickListener { applyFilter(Filter.ALL) }
        binding.pillHr.setOnClickListener { applyFilter(Filter.HR) }
        binding.pillMarketing.setOnClickListener { applyFilter(Filter.MARKETING) }
        binding.pillProject.setOnClickListener { applyFilter(Filter.PROJECT) }
        binding.pillLand.setOnClickListener { applyFilter(Filter.LAND) }
        binding.pillFleet.setOnClickListener { applyFilter(Filter.FLEET) }
        binding.pillSales.setOnClickListener { applyFilter(Filter.SALES) }
        binding.pillAccounts.setOnClickListener { applyFilter(Filter.ACCOUNTS) }
        binding.pillSettings.setOnClickListener { applyFilter(Filter.SETTINGS) }
    }

    private fun applyFilter(filter: Filter) {
        currentFilter = filter
        // Section visibility is the AND of two predicates:
        //   1. the filter chip picks this section (or ALL is selected)
        //   2. the section has at least one tile the user can see
        // (2) is determined by walking the tile IDs in sectionTileMap;
        // applying it here means selecting a category never resurrects
        // a card that IAM has pruned to empty.
        fun show(card: View, allowedByFilter: Boolean, sectionFilter: Filter): Int {
            if (!allowedByFilter) return View.GONE
            val tileIds = sectionTileMap.firstOrNull { it.first == sectionFilter }?.third
                ?: return View.VISIBLE
            val anyVisible = tileIds.any { id ->
                binding.root.findViewById<View>(id)?.visibility == View.VISIBLE
            }
            return if (anyVisible) View.VISIBLE else View.GONE
        }
        binding.cardHr.visibility = show(binding.cardHr, filter == Filter.ALL || filter == Filter.HR, Filter.HR)
        binding.cardMarketing.visibility = show(binding.cardMarketing, filter == Filter.ALL || filter == Filter.MARKETING, Filter.MARKETING)
        binding.cardProject.visibility = show(binding.cardProject, filter == Filter.ALL || filter == Filter.PROJECT, Filter.PROJECT)
        binding.cardLand.visibility = show(binding.cardLand, filter == Filter.ALL || filter == Filter.LAND, Filter.LAND)
        binding.cardFleet.visibility = show(binding.cardFleet, filter == Filter.ALL || filter == Filter.FLEET, Filter.FLEET)
        binding.cardSales.visibility = show(binding.cardSales, filter == Filter.ALL || filter == Filter.SALES, Filter.SALES)
        binding.cardAccounts.visibility = show(binding.cardAccounts, filter == Filter.ALL || filter == Filter.ACCOUNTS, Filter.ACCOUNTS)
        binding.cardConfig.visibility = show(binding.cardConfig, filter == Filter.ALL || filter == Filter.SETTINGS, Filter.SETTINGS)

        styleTab(binding.pillAllAppsIcon, binding.pillAllAppsText, binding.pillAllAppsIndicator, filter == Filter.ALL)
        styleTab(binding.pillHrIcon, binding.pillHrText, binding.pillHrIndicator, filter == Filter.HR)
        styleTab(binding.pillMarketingIcon, binding.pillMarketingText, binding.pillMarketingIndicator, filter == Filter.MARKETING)
        styleTab(binding.pillProjectIcon, binding.pillProjectText, binding.pillProjectIndicator, filter == Filter.PROJECT)
        styleTab(binding.pillLandIcon, binding.pillLandText, binding.pillLandIndicator, filter == Filter.LAND)
        styleTab(binding.pillFleetIcon, binding.pillFleetText, binding.pillFleetIndicator, filter == Filter.FLEET)
        styleTab(binding.pillSalesIcon, binding.pillSalesText, binding.pillSalesIndicator, filter == Filter.SALES)
        styleTab(binding.pillAccountsIcon, binding.pillAccountsText, binding.pillAccountsIndicator, filter == Filter.ACCOUNTS)
        styleTab(binding.pillSettingsIcon, binding.pillSettingsText, binding.pillSettingsIndicator, filter == Filter.SETTINGS)
    }

    /**
     * Flips a pill between active and inactive look — matches the
     * reference design exactly:
     * - Active   = solid blue circle behind a white icon + blue label
     *              + small blue underline dash below.
     * - Inactive = bare grey icon (no circle, no border) + grey label
     *              + indicator hidden.
     */
    private fun styleTab(icon: android.widget.ImageView, label: TextView, indicator: View, active: Boolean) {
        if (active) {
            icon.setBackgroundResource(R.drawable.bg_apps_pill_circle_active)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#FFFFFF")
            )
            label.setTextColor(Color.parseColor("#0B61CA"))
            indicator.visibility = View.VISIBLE
        } else {
            icon.setBackgroundResource(R.drawable.bg_apps_pill_circle_inactive)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#6A6D78")
            )
            label.setTextColor(Color.parseColor("#6A6D78"))
            indicator.visibility = View.GONE
        }
    }

    private fun bindIamEntry(row: View, allowed: Boolean, onClick: () -> Unit) {
        if (allowed) {
            row.visibility = View.VISIBLE
            row.setOnClickListener { onClick() }
        } else {
            row.visibility = View.GONE
            row.setOnClickListener(null)
        }
    }

    // Map of section card → tile IDs inside it. When every tile in a
    // section is hidden by IAM (all GONE after setupClickActions), the
    // whole section card disappears too — otherwise the user would see
    // an empty card with just the section header. The matching filter
    // pill also hides so the user can't tap a category that has nothing
    // to show. Re-run on every setupClickActions / IAM bus update.
    private val sectionTileMap: List<Triple<Filter, View, List<Int>>> by lazy {
        listOf(
            Triple(
                Filter.HR, binding.cardHr,
                listOf(
                    R.id.itemHrAttendance,
                    R.id.itemHrLeave,
                    R.id.itemHrPermissions,
                    R.id.itemHrLoans,
                    R.id.itemHrAttendanceReview,
                ),
            ),
            Triple(
                Filter.MARKETING, binding.cardMarketing,
                listOf(
                    R.id.itemMarketingCpVisits,
                    R.id.itemMarketingSiteVisits,
                    R.id.itemMarketingMyLeads,
                    R.id.itemMarketingDialer,
                    R.id.itemMarketingInventory,
                    R.id.itemMarketingNewBooking,
                ),
            ),
            Triple(
                Filter.PROJECT, binding.cardProject,
                listOf(R.id.itemProjectTasks, R.id.itemProjectExpenses),
            ),
            Triple(
                Filter.LAND, binding.cardLand,
                listOf(R.id.itemLandInspection, R.id.itemLandQueries),
            ),
            Triple(
                Filter.FLEET, binding.cardFleet,
                listOf(R.id.itemFleetMyTrips),
            ),
            Triple(
                Filter.SALES, binding.cardSales,
                listOf(R.id.itemSalesCollections, R.id.itemSalesLoanDesk),
            ),
            Triple(
                Filter.ACCOUNTS, binding.cardAccounts,
                listOf(R.id.itemAccountsPostSalesVerification),
            ),
            // Settings card: itemSettings is never IAM-gated (every
            // staff can edit their own profile), so this section is
            // effectively always non-empty. Listed for completeness.
            Triple(
                Filter.SETTINGS, binding.cardConfig,
                listOf(R.id.itemSettings),
            ),
        )
    }

    private fun pruneEmptySections() {
        if (_binding == null) return
        for ((filter, card, tileIds) in sectionTileMap) {
            val anyVisible = tileIds.any { id ->
                binding.root.findViewById<View>(id)?.visibility == View.VISIBLE
            }
            card.visibility = if (anyVisible) View.VISIBLE else View.GONE
            // Hide the matching filter pill too so a user can't tap a
            // category that has nothing to show. Mapping pill → filter
            // is the inverse of styleTab's mapping; we just hide the
            // pill's parent (each pill is wrapped in its own clickable
            // LinearLayout).
            val pillRoot = pillRootFor(filter)
            pillRoot?.visibility = if (anyVisible) View.VISIBLE else View.GONE

            // When IAM hides some tiles inside a visible card, the static
            // `<View>` dividers between rows can be left dangling (no row
            // above or no row below), or doubled-up when an interior row
            // collapses — e.g. with Loans hidden, the divider above Loans
            // AND `dividerHrAttendanceReview` end up between Permissions
            // and Approvals. Re-run a sweep that keeps exactly one
            // divider between each pair of visible rows.
            if (anyVisible) {
                val firstVisibleTile = tileIds
                    .mapNotNull { binding.root.findViewById<View>(it) }
                    .firstOrNull { it.visibility == View.VISIBLE }
                    ?: tileIds.firstNotNullOfOrNull { binding.root.findViewById<View>(it) }
                (firstVisibleTile?.parent as? ViewGroup)?.let { pruneDanglingDividers(it) }
            }
        }
    }

    /**
     * Walks a tile container in order and enforces: at most one divider
     * sits between any two visible rows, and no divider hangs at the top
     * or bottom of the run. Identifies rows as `LinearLayout` children
     * (the tile rows are LinearLayouts; the dividers are plain `View`s).
     */
    private fun pruneDanglingDividers(container: ViewGroup) {
        var lastShownDivider: View? = null
        // pendingGap = a visible row has been seen; the next divider is
        // eligible. dividerInGap = a divider has already been placed in
        // the current gap, so any further divider before the next visible
        // row should be hidden.
        var pendingGap = false
        var dividerInGap = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is android.widget.LinearLayout) {
                // It's a tile row. A visible row opens a new gap; a GONE
                // row collapses to 0 height and doesn't change state.
                if (child.visibility == View.VISIBLE) {
                    pendingGap = true
                    dividerInGap = false
                }
            } else {
                // Plain View → treat as a divider.
                if (pendingGap && !dividerInGap) {
                    child.visibility = View.VISIBLE
                    lastShownDivider = child
                    dividerInGap = true
                } else {
                    child.visibility = View.GONE
                }
            }
        }
        // If a divider was placed but no visible row followed it before
        // we hit the end (trailing divider), hide it too.
        if (dividerInGap) lastShownDivider?.visibility = View.GONE
    }

    /**
     * Resolve the clickable wrapper for a given filter's pill. The
     * pills sit in a horizontal strip; each one has a known root view
     * (e.g. `pillHr` for the HR section's pill). Returns null for
     * Filter.ALL (which is never hidden — it remains a way back to the
     * unfiltered view if some sections are visible, or it's the only
     * pill visible when everything else is gone, but tapping it just
     * shows whatever cards remain).
     */
    private fun pillRootFor(filter: Filter): View? = when (filter) {
        Filter.ALL -> null
        Filter.HR -> binding.pillHr
        Filter.MARKETING -> binding.pillMarketing
        Filter.PROJECT -> binding.pillProject
        Filter.LAND -> binding.pillLand
        Filter.FLEET -> binding.pillFleet
        Filter.SALES -> binding.pillSales
        Filter.ACCOUNTS -> binding.pillAccounts
        Filter.SETTINGS -> binding.pillSettings
    }

    private fun openScreen(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun comingSoon(feature: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, PlaceholderFragment.newInstance("$feature - Coming Soon"))
            .addToBackStack(null)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(true)
            main.setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
        }
        if (_binding != null) {
            binding.sectionsContainer.post { playLibraryEntryAnimation() }
        }
        // Pull the freshest permissions whenever the user opens the
        // Apps tab — covers the case where MainActivity's foreground
        // resume already fired (and was throttled) but the user has
        // been mid-session and now wants to see something newly
        // granted. The bus's own throttle prevents back-to-back
        // fetches; the SharedFlow subscriber set up in onViewCreated
        // re-binds tiles automatically when the fetch returns a
        // changed permission set.
        if (_binding == null) return
        val session = SessionManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            IamUpdateBus.refresh(session)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
