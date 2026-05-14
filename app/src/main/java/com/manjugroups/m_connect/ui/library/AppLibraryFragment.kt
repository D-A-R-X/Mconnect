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
        val riseTravel = 36f * density

        // Header pulls slightly downward into place (opposite-direction echo of the home
        // descent — feels stable while the body content lifts up from below).
        listOfNotNull(binding.libraryHeaderContent, binding.ivLibraryIllustration).forEach { v ->
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = -10f * density
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(360L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
                .start()
        }

        // Filter pill (visible at any time) lifts up
        val pill = binding.pillAllApps.parent as? View
        pill?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.translationY = riseTravel * 0.5f
            it.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380L)
                .setStartDelay(80L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
                .start()
        }

        // Section cards rise from below in a stagger — the "ascending curtain" that
        // mirrors the home curtain falling.
        val container = binding.sectionsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = riseTravel
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(460L)
                .setStartDelay(180L + i * 90L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
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
        binding.itemHrLoans.setOnClickListener { comingSoon("Loans") }

        binding.itemMarketingCpVisits.setOnClickListener { openScreen(CpVisitsFragment()) }
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

        styleTab(binding.pillAllAppsText, binding.pillAllAppsIndicator, filter == Filter.ALL)
        styleTab(binding.pillHrText, binding.pillHrIndicator, filter == Filter.HR)
        styleTab(binding.pillMarketingText, binding.pillMarketingIndicator, filter == Filter.MARKETING)
        styleTab(binding.pillProjectText, binding.pillProjectIndicator, filter == Filter.PROJECT)
        styleTab(binding.pillSettingsText, binding.pillSettingsIndicator, filter == Filter.SETTINGS)
    }

    private fun styleTab(label: TextView, indicator: View, active: Boolean) {
        if (active) {
            label.setTextColor(Color.parseColor("#0B61CA"))
            indicator.visibility = View.VISIBLE
        } else {
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
