package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAdminFleetContainerBinding

class AdminFleetContainerFragment : Fragment() {

    private var _binding: FragmentAdminFleetContainerBinding? = null
    private val binding get() = _binding!!
    private var currentTabIndex = 0
    private var previousTabIndex = 0
    private lateinit var backCallback: androidx.activity.OnBackPressedCallback
    private var isBottomNavVisible = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminFleetContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Listen for window insets to lift bottom navigation bar safely above system gesture handle
        ViewCompat.setOnApplyWindowInsetsListener(binding.tabBarContainer) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Lift it slightly higher (16dp base bottom padding) so it sits beautifully above the line
            val baseBottomPx = (16 * resources.displayMetrics.density).toInt()
            v.updatePadding(
                left = sys.left,
                right = sys.right,
                bottom = sys.bottom + baseBottomPx
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.tabBarContainer)

        // Setup bottom tab clicks
        binding.tabTrips.setOnClickListener { selectTab(0) }
        binding.tabVehicles.setOnClickListener { selectTab(1) }
        binding.tabDriver.setOnClickListener { selectTab(2) }
        binding.tabSettings.setOnClickListener { selectTab(3) }

        // Handle back press to switch back to previous tab when on Settings tab
        backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                goBackToPreviousTab()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // Start with Trips tab
        selectTab(0)
    }

    fun goBackToPreviousTab() {
        selectTab(previousTabIndex)
    }

    private fun selectTab(index: Int) {
        if (index != 3) {
            previousTabIndex = index
        }
        currentTabIndex = index
        if (::backCallback.isInitialized) {
            backCallback.isEnabled = (index == 3)
        }

        // Toggle bottom navigation bar visibility
        if (index == 3) {
            binding.tabBarContainer.visibility = View.GONE
            binding.bottomNavFadeOverlay.visibility = View.GONE
        } else {
            binding.tabBarContainer.visibility = View.VISIBLE
            binding.bottomNavFadeOverlay.visibility = View.VISIBLE
            binding.tabBarContainer.translationY = 0f
            binding.tabBarContainer.alpha = 1f
            binding.bottomNavFadeOverlay.translationY = 0f
            binding.bottomNavFadeOverlay.alpha = 1f
            isBottomNavVisible = true
        }
        val tag = when (index) {
            0 -> "admin_tab_trips"
            1 -> "admin_tab_vehicles"
            2 -> "admin_tab_driver"
            3 -> "admin_tab_settings"
            else -> "admin_tab_trips"
        }

        val transaction = childFragmentManager.beginTransaction()
        val trips = childFragmentManager.findFragmentByTag("admin_tab_trips")
        val vehicles = childFragmentManager.findFragmentByTag("admin_tab_vehicles")
        val driver = childFragmentManager.findFragmentByTag("admin_tab_driver")
        val settings = childFragmentManager.findFragmentByTag("admin_tab_settings")

        trips?.let { transaction.hide(it) }
        vehicles?.let { transaction.hide(it) }
        driver?.let { transaction.hide(it) }
        settings?.let { transaction.hide(it) }

        var target = childFragmentManager.findFragmentByTag(tag)
        if (target == null) {
            target = when (index) {
                0 -> AdminFleetTripsFragment()
                1 -> AdminFleetVehiclesFragment()
                2 -> AdminFleetDriversFragment()
                3 -> AdminFleetSettingsFragment()
                else -> AdminFleetTripsFragment()
            }
            transaction.add(R.id.adminFleetContainer, target, tag)
        } else {
            transaction.show(target)
        }
        transaction.commit()

        // Update tab styling matching MainActivity exactly
        updateTabStyles(index)

        // Dynamically adjust status bar to be white with dark icons on Vehicles, Drivers, and Settings list tabs
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            if (index == 1 || index == 2 || index == 3) {
                main.setTopBarAppearance(Color.parseColor("#FFFFFF"), true, fullBleed = true)
            } else {
                main.setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
            }
        }
    }

    private fun updateTabStyles(selectedIndex: Int) {
        // Bright green (#1BCA0B) for active, soft gray (#D0D5DD) for inactive
        val activeColor = Color.parseColor("#1BCA0B")
        val inactiveColor = Color.parseColor("#D0D5DD")

        val tabs = listOf(
            Pair(binding.tabTripsIcon, binding.tabTripsText),
            Pair(binding.tabVehiclesIcon, binding.tabVehiclesText),
            Pair(binding.tabDriverIcon, binding.tabDriverText),
            Pair(binding.tabSettingsIcon, binding.tabSettingsText)
        )

        tabs.forEachIndexed { idx, (iv, tv) ->
            val isActive = idx == selectedIndex
            val tintColor = if (isActive) activeColor else inactiveColor
            iv.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
            tv.setTextColor(tintColor)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false) // Hide parent MainActivity tab bar since we replicate it locally
            if (currentTabIndex == 1 || currentTabIndex == 2 || currentTabIndex == 3) {
                main.setTopBarAppearance(Color.parseColor("#FFFFFF"), true, fullBleed = true)
            } else {
                main.setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
            }
        }
        if (currentTabIndex != 3) {
            binding.tabBarContainer.translationY = 0f
            binding.tabBarContainer.alpha = 1f
            binding.bottomNavFadeOverlay.translationY = 0f
            binding.bottomNavFadeOverlay.alpha = 1f
            isBottomNavVisible = true
        }
    }

    fun setBottomNavScrollState(visible: Boolean) {
        if (_binding == null) return
        if (binding.tabBarContainer.visibility != View.VISIBLE && visible) return
        if (isBottomNavVisible == visible) return
        isBottomNavVisible = visible

        val translationDistance = 150f * resources.displayMetrics.density
        val translationY = if (visible) 0f else translationDistance
        val alpha = if (visible) 1f else 0f

        binding.tabBarContainer.animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(400)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()

        binding.bottomNavFadeOverlay.animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(400)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
    }

    override fun onPause() {
        super.onPause()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(true) // Restore parent MainActivity tab bar
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
