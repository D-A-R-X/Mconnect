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
import com.manjugroups.m_connect.ui.PlaceholderFragment
import com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.marketing.CpVisitsFragment
import com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment
import com.manjugroups.m_connect.ui.marketing.bookings.BookingCreateFragment
import com.manjugroups.m_connect.ui.marketing.inventory.InventoryProjectsListFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.telecaller.DialerFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment

class AppLibraryFragment : Fragment() {

    private var _binding: FragmentAppLibraryBinding? = null
    private val binding get() = _binding!!

    private enum class Filter { ALL, HR, MARKETING, PROJECT, SETTINGS }

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
        setupClickActions()
        setupFilterPills()
        setupScrollAnimation()
        applyFilter(Filter.ALL)
        
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
            binding.pillProjectIcon, binding.pillSettingsIcon
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

    private fun setupScrollAnimation() {
        binding.scrollLibrary.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val alpha = (1f - (scrollY.toFloat() / 300f)).coerceIn(0f, 1f)
            binding.libraryHeaderContent.alpha = alpha
            binding.ivLibraryIllustration.alpha = alpha
            binding.ivLibraryIllustration.translationY = scrollY.toFloat() * 0.45f
            binding.libraryHeaderContent.translationY = scrollY.toFloat() * 0.25f
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

        binding.itemProjectTasks.setOnClickListener { openScreen(TasksFragment()) }
        binding.itemSettings.setOnClickListener { openScreen(ProfileFragment()) }
    }

    private fun setupFilterPills() {
        binding.pillAllApps.setOnClickListener { applyFilter(Filter.ALL) }
        binding.pillHr.setOnClickListener { applyFilter(Filter.HR) }
        binding.pillMarketing.setOnClickListener { applyFilter(Filter.MARKETING) }
        binding.pillProject.setOnClickListener { applyFilter(Filter.PROJECT) }
        binding.pillSettings.setOnClickListener { applyFilter(Filter.SETTINGS) }
    }

    private fun applyFilter(filter: Filter) {
        binding.cardHr.visibility = if (filter == Filter.ALL || filter == Filter.HR) View.VISIBLE else View.GONE
        binding.cardMarketing.visibility = if (filter == Filter.ALL || filter == Filter.MARKETING) View.VISIBLE else View.GONE
        binding.cardProject.visibility = if (filter == Filter.ALL || filter == Filter.PROJECT) View.VISIBLE else View.GONE
        binding.cardConfig.visibility = if (filter == Filter.ALL || filter == Filter.SETTINGS) View.VISIBLE else View.GONE

        styleTab(binding.pillAllAppsIcon, binding.pillAllAppsText, binding.pillAllAppsIndicator, filter == Filter.ALL)
        styleTab(binding.pillHrIcon, binding.pillHrText, binding.pillHrIndicator, filter == Filter.HR)
        styleTab(binding.pillMarketingIcon, binding.pillMarketingText, binding.pillMarketingIndicator, filter == Filter.MARKETING)
        styleTab(binding.pillProjectIcon, binding.pillProjectText, binding.pillProjectIndicator, filter == Filter.PROJECT)
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
