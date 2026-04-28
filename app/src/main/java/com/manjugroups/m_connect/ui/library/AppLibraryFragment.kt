package com.manjugroups.m_connect.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAppLibraryBinding
import com.manjugroups.m_connect.ui.PlaceholderFragment
import com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.marketing.SiteVisitsListFragment
import com.manjugroups.m_connect.ui.marketing.bookings.BookingCreateFragment
import com.manjugroups.m_connect.ui.marketing.inventory.InventoryProjectsListFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.telecaller.DialerFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment

class AppLibraryFragment : Fragment() {

    private var _binding: FragmentAppLibraryBinding? = null
    private val binding get() = _binding!!

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
    }

    private fun setupClickActions() {
        binding.itemHrAttendance.setOnClickListener { openScreen(AttendanceHistoryFragment()) }
        binding.itemHrLeave.setOnClickListener { openScreen(LeavesFragment()) }
        binding.itemHrPermissions.setOnClickListener { openScreen(PermissionsFragment()) }
        binding.itemHrLoans.setOnClickListener { comingSoon("Loans") }

        binding.itemMarketingSiteVisits.setOnClickListener { openScreen(SiteVisitsListFragment()) }
        binding.itemMarketingDialer.setOnClickListener { openScreen(DialerFragment()) }
        binding.itemMarketingMyLeads.setOnClickListener {
            openScreen(MyLeadsFragment.newInstance(MyLeadsFragment.Mode.ALL))
        }

        // KOS-52: Inventory + Bookings entries. Gated client-side by the same
        // IAM permissions the web side enforces (lib/iam-model.ts).
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

        // Keep existing profile/settings capabilities reachable from the new Apps tab.
        binding.itemSettings.setOnClickListener { openScreen(ProfileFragment()) }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
