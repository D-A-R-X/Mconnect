package com.manjugroups.m_connect.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAppLibraryBinding
import com.manjugroups.m_connect.ui.PlaceholderFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment

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
        binding.itemHrLeave.setOnClickListener { openScreen(LeavesFragment()) }
        binding.itemHrPermissions.setOnClickListener { openScreen(PermissionsFragment()) }
        binding.itemHrLoans.setOnClickListener { comingSoon("Loans") }

        binding.itemMarketingSiteVisits.setOnClickListener { comingSoon("Site Visits") }
        binding.itemMarketingClientPlace.setOnClickListener { comingSoon("Places") }
        binding.itemMarketingDialer.setOnClickListener { comingSoon("Dialer") }
        binding.itemMarketingCall.setOnClickListener { comingSoon("Call") }

        binding.itemProjectTasks.setOnClickListener { comingSoon("Tasks") }

        // Keep existing profile/settings capabilities reachable from the new Apps tab.
        binding.itemSettings.setOnClickListener { openScreen(ProfileFragment()) }
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
