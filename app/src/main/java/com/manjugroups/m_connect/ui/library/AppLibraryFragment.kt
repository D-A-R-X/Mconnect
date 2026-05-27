package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAppLibraryBinding
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
import com.manjugroups.m_connect.ui.marketing.inventory.InventoryProjectsListFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import com.manjugroups.m_connect.ui.projects.ProjectExpensesFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.telecaller.DialerFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment

class AppLibraryFragment : Fragment() {

    private var _binding: FragmentAppLibraryBinding? = null
    private val binding get() = _binding!!

    private enum class Filter { ALL, HR, MARKETING, PROJECT, LAND, SETTINGS }

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
        setupFilterPills()
        setupScrollAnimation()
        applyFilter(Filter.ALL)

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
            binding.pillProjectIcon, binding.pillLandIcon, binding.pillSettingsIcon
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
        hsv.post {
            val pillWidth = hsv.width / 5
            if (pillWidth <= 0) return@post
            listOf(
                binding.pillAllApps,
                binding.pillHr,
                binding.pillMarketing,
                binding.pillProject,
                binding.pillLand,
                binding.pillSettings,
            ).forEach { pill ->
                val lp = pill.layoutParams as android.widget.LinearLayout.LayoutParams
                lp.width = pillWidth
                lp.weight = 0f
                pill.layoutParams = lp
            }
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
        val maxBottomRadiusPx = 24f * density
        val headerBg = binding.libraryHeaderFrame
            .applyShrinkableBlueHeaderBackground()
        headerBg.setBottomCornerRadius(maxBottomRadiusPx)

        // Need the panel container (parent of the scroll view) to translate.
        val panel = binding.libraryRefresh
        // Without a solid background the SwipeRefreshLayout is transparent;
        // translating it up would leave the blue header visible through any
        // gap between section cards. Painting it with the page bg makes the
        // whole panel act as the "cover" that hides the header on scroll.
        panel.setBackgroundColor(android.graphics.Color.parseColor("#F1F3F8"))

        binding.libraryHeaderFrame.post {
            val overlayDistancePx = binding.libraryHeaderFrame.height.toFloat()
            // Extend the panel past the column bottom by headerHeight so
            // once the panel is fully translated up its visual bottom
            // still reaches the screen bottom — eliminates the grey
            // strip that used to be visible between the last card and
            // the floating tab bar.
            //
            // Note: in a LinearLayout-with-weight, setting bottomMargin
            // alone does NOT grow the panel's drawing area — it only
            // re-balances the column. We also have to override the
            // measured `height` so the SwipeRefresh actually paints
            // those extra pixels. Without this the panel translates up
            // and leaves `overlayDistancePx`-tall grey strip below.
            (panel.layoutParams as ViewGroup.MarginLayoutParams).apply {
                height = panel.height + overlayDistancePx.toInt()
                bottomMargin = -overlayDistancePx.toInt()
                panel.layoutParams = this
            }
            binding.scrollLibrary.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val translateY = -scrollY.toFloat().coerceAtMost(overlayDistancePx)
                panel.translationY = translateY
                val progress = (-translateY / overlayDistancePx).coerceIn(0f, 1f)
                headerBg.setBottomCornerRadius((1f - progress) * maxBottomRadiusPx)
            }
        }
    }

    private fun setupClickActions() {
        binding.itemHrAttendance.setOnClickListener { openScreen(AttendanceHistoryFragment()) }
        binding.itemHrLeave.setOnClickListener { openScreen(LeavesFragment()) }
        binding.itemHrPermissions.setOnClickListener { openScreen(PermissionsFragment()) }
        binding.itemHrLoans.setOnClickListener {
            openScreen(com.manjugroups.m_connect.ui.library.loans.LoansFragment())
        }

        binding.itemMarketingCpVisits.setOnClickListener { openScreen(CpVisitsFragment()) }
        binding.itemMarketingSiteVisits.setOnClickListener { openScreen(SiteVisitsFragment()) }
        binding.itemMarketingDialer.setOnClickListener { openScreen(DialerFragment()) }
        binding.itemMarketingMyLeads.setOnClickListener {
            openScreen(MyLeadsFragment.newInstance(MyLeadsFragment.Mode.ALL))
        }

        val session = SessionManager(requireContext())
        bindIamEntry(
            row = binding.itemMarketingInventory,
            allowed = session.hasPermission("projects.view"),
        ) { openScreen(InventoryProjectsListFragment()) }
        bindIamEntry(
            row = binding.itemMarketingNewBooking,
            allowed = session.hasPermission("marketing.bookings.create"),
        ) { openScreen(BookingCreateFragment.newEmpty()) }

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

        binding.itemProjectTasks.setOnClickListener { openScreen(TasksFragment()) }
        binding.itemProjectExpenses.setOnClickListener {
            openScreen(ProjectExpensesFragment())
        }
        binding.itemSettings.setOnClickListener { openScreen(ProfileFragment()) }

        // Land Procurement — Inspection list. Opens LandInspectionFragment,
        // which currently renders 5 placeholder rows from the design
        // pending a real backend endpoint.
        binding.itemLandInspection.setOnClickListener {
            openScreen(com.manjugroups.m_connect.ui.library.land.LandInspectionFragment())
        }
    }

    private fun setupFilterPills() {
        binding.pillAllApps.setOnClickListener { applyFilter(Filter.ALL) }
        binding.pillHr.setOnClickListener { applyFilter(Filter.HR) }
        binding.pillMarketing.setOnClickListener { applyFilter(Filter.MARKETING) }
        binding.pillProject.setOnClickListener { applyFilter(Filter.PROJECT) }
        binding.pillLand.setOnClickListener { applyFilter(Filter.LAND) }
        binding.pillSettings.setOnClickListener { applyFilter(Filter.SETTINGS) }
    }

    private fun applyFilter(filter: Filter) {
        binding.cardHr.visibility = if (filter == Filter.ALL || filter == Filter.HR) View.VISIBLE else View.GONE
        binding.cardMarketing.visibility = if (filter == Filter.ALL || filter == Filter.MARKETING) View.VISIBLE else View.GONE
        binding.cardProject.visibility = if (filter == Filter.ALL || filter == Filter.PROJECT) View.VISIBLE else View.GONE
        binding.cardLand.visibility = if (filter == Filter.ALL || filter == Filter.LAND) View.VISIBLE else View.GONE
        binding.cardConfig.visibility = if (filter == Filter.ALL || filter == Filter.SETTINGS) View.VISIBLE else View.GONE

        styleTab(binding.pillAllAppsIcon, binding.pillAllAppsText, binding.pillAllAppsIndicator, filter == Filter.ALL)
        styleTab(binding.pillHrIcon, binding.pillHrText, binding.pillHrIndicator, filter == Filter.HR)
        styleTab(binding.pillMarketingIcon, binding.pillMarketingText, binding.pillMarketingIndicator, filter == Filter.MARKETING)
        styleTab(binding.pillProjectIcon, binding.pillProjectText, binding.pillProjectIndicator, filter == Filter.PROJECT)
        styleTab(binding.pillLandIcon, binding.pillLandText, binding.pillLandIndicator, filter == Filter.LAND)
        styleTab(binding.pillSettingsIcon, binding.pillSettingsText, binding.pillSettingsIndicator, filter == Filter.SETTINGS)
    }

    /**
     * Flips a pill between active and inactive look:
     * - Active: solid blue circle + white icon + blue label + visible underline
     * - Inactive: light grey circle + grey icon + grey label + hidden underline
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
            indicator.visibility = View.INVISIBLE
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
