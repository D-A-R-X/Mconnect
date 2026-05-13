package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.MainActivity
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
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.telecaller.DialerFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment

class AppLibraryFragment : Fragment() {

    private var _binding: FragmentAppLibraryBinding? = null
    private val binding get() = _binding!!

    private var hasAnimatedBanner = false
    private var isBannerCollapsed = false
    private var bannerMeasuredHeight = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupActions()
        setupScroll()
        
        binding.appsContentWrapper.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(true)
        
        // Always trigger animation on swipe back
        if (!hasAnimatedBanner) {
            animateBannerExpansion()
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset state so it re-animates next time
        hasAnimatedBanner = false
        isBannerCollapsed = false
        if (_binding != null) {
            binding.appsBannerExpandable.visibility = View.INVISIBLE
            binding.appsBannerExpandable.alpha = 0f
            binding.appsBannerExpandable.layoutParams.height = 0
        }
    }

    private fun setupHeader() {
        val session = SessionManager(requireContext())
        val name = (session.userName ?: "User").ifBlank { "User" }
        binding.tvAppsAvatarInitial.text = name.first().uppercase()
    }

    private fun setupActions() {
        binding.btnAppsProfile.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ProfileFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.btnAppsBell.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, NotificationsFragment())
                .addToBackStack(null)
                .commit()
        }

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

    private fun setupScroll() {
        binding.appsScrollView.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 50 && !isBannerCollapsed && bannerMeasuredHeight > 0) {
                collapseBanner()
            } else if (scrollY < 10 && isBannerCollapsed) {
                expandBanner()
            }
        })
    }

    private fun collapseBanner() {
        if (!isBannerCollapsed) {
            isBannerCollapsed = true
            val animator = android.animation.ValueAnimator.ofInt(binding.appsBannerExpandable.height, 0)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.appsBannerExpandable.layoutParams
                params.height = value
                binding.appsBannerExpandable.layoutParams = params
            }
            animator.duration = 300
            animator.start()
            binding.appsBannerExpandable.animate().alpha(0f).setDuration(200).start()
        }
    }

    private fun expandBanner() {
        if (isBannerCollapsed) {
            isBannerCollapsed = false
            val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.appsBannerExpandable.layoutParams
                params.height = value
                binding.appsBannerExpandable.layoutParams = params
            }
            animator.duration = 300
            animator.start()
            binding.appsBannerExpandable.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun animateBannerExpansion() {
        if (hasAnimatedBanner || _binding == null) return
        hasAnimatedBanner = true

        binding.appsContentWrapper.visibility = View.VISIBLE
        binding.appsBannerExpandable.visibility = View.VISIBLE
        binding.appsBannerExpandable.alpha = 0f
        
        binding.appsBannerExpandable.post {
            if (_binding == null) return@post
            val widthSpec = View.MeasureSpec.makeMeasureSpec(binding.appsBannerExpandable.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            binding.appsBannerExpandable.measure(widthSpec, heightSpec)
            bannerMeasuredHeight = binding.appsBannerExpandable.measuredHeight

            if (bannerMeasuredHeight <= 0) {
                hasAnimatedBanner = false
                return@post
            }

            val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.appsBannerExpandable.layoutParams
                params.height = value
                binding.appsBannerExpandable.layoutParams = params
            }
            
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    _binding?.appsBannerExpandable?.animate()?.alpha(1f)?.setDuration(400)?.start()
                }
            })
            
            animator.duration = 700
            animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            animator.start()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedBanner = false
        isBannerCollapsed = false
        bannerMeasuredHeight = 0
    }
}
