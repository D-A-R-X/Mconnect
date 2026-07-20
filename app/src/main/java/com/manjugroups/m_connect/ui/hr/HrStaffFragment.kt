package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHrStaffBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.ui.common.commitOnce

class HrStaffFragment : Fragment() {

    private var _binding: FragmentHrStaffBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HrStaffViewModel by viewModels()
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHrStaffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnSearch.setOnClickListener { navigateUp() }

        setupSearch()
        collectState()
        viewModel.loadStaff(session.bearerToken)
        loadStaffCount()
    }

    /**
     * One-shot fetch of the org-wide active/inactive/total roll-up. Lives
     * outside the list ViewModel because the count is a header chip, not
     * paginated list state — and it never needs to refresh as the user
     * scrolls.
     */
    private fun loadStaffCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getStaffCount(session.bearerToken) }.getOrNull()
            if (_binding == null || resp == null || !resp.success) return@launch
            binding.tvStaffCountSummary.text =
                "Active ${resp.activeCount} · Inactive ${resp.inactiveCount} · Total ${resp.allCount}"
            binding.tvStaffCountSummary.visibility = View.VISIBLE
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    viewModel.searchStaff(session.bearerToken, s?.toString() ?: "")
                }
                searchHandler.postDelayed(searchRunnable!!, 400)
            }
        })
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HrStaffUiState.Loading -> {
                            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
                            binding.staffList.visibility = View.GONE
                        }

                        is HrStaffUiState.Loaded -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.staffList.visibility = View.VISIBLE
                            renderStaffList(state)
                        }

                        is HrStaffUiState.Error -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.staffList.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun renderStaffList(state: HrStaffUiState.Loaded) {
        binding.staffList.removeAllViews()

        state.staff.forEach { staff ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_staff, binding.staffList, false)

            val initials = staff.name.split(" ").take(2).joinToString("") {
                if (it.isNotEmpty()) it.first().uppercase() else ""
            }
            card.findViewById<TextView>(R.id.tvStaffInitials).text = initials
            card.findViewById<TextView>(R.id.tvStaffName).text = staff.name
            card.findViewById<TextView>(R.id.tvStaffRole).text = "${staff.designation} · ${staff.role}"
            card.findViewById<TextView>(R.id.tvStaffPhone).text = "+91 ${formatPhone(staff.phone)}"
            renderGeoTrackHealth(card, staff)

            val badge = card.findViewById<TextView>(R.id.tvStaffStatus)
            badge.text = staff.status.replaceFirstChar { it.uppercase() }
            if (staff.status == "active") {
                badge.setBackgroundResource(R.drawable.bg_badge_success)
                badge.setTextColor(resolveColor(R.attr.colorSuccess))
            } else {
                badge.setBackgroundResource(R.drawable.bg_badge_error)
                badge.setTextColor(resolveColor(R.attr.colorError))
            }

            card.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, StaffDetailFragment.newInstance(staff.id))
                    .addToBackStack(null)
                    .commitOnce()
            }

            binding.staffList.addView(card)
        }

        // Load more button
        if (state.hasMore && state.searchQuery.isBlank()) {
            val row = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 32, 0, 16)
            }

            val label = TextView(requireContext()).apply {
                text = "Load More"
                textSize = 14f
                typeface = resources.getFont(R.font.inter_semibold)
                setTextColor(resolveColor(R.attr.colorAccentPrimary))
                gravity = android.view.Gravity.CENTER
                isClickable = !state.isLoadingMore
                isFocusable = !state.isLoadingMore
                setOnClickListener {
                    if (!state.isLoadingMore) viewModel.loadMore(session.bearerToken)
                }
            }

            val skeleton = View(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(120),
                    dpToPx(12),
                    android.view.Gravity.CENTER
                )
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_skeleton_rounded)
                visibility = View.GONE
            }

            row.addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
            row.addView(skeleton)

            if (state.isLoadingMore) {
                label.visibility = View.INVISIBLE
                skeleton.visibility = View.VISIBLE
                SkeletonUtils.startSkeletonPulse(skeleton)
            } else {
                SkeletonUtils.stopSkeletonPulse(skeleton)
                skeleton.visibility = View.GONE
                label.visibility = View.VISIBLE
            }

            binding.staffList.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun formatPhone(phone: String): String {
        return if (phone.length == 10) "${phone.substring(0, 5)} ${phone.substring(5)}" else phone
    }

    private fun renderGeoTrackHealth(card: View, staff: StaffItem) {
        val health = card.findViewById<TextView>(R.id.tvStaffGeoTrackHealth)
        if (!staff.geoTrackingEnabled) {
            health.visibility = View.GONE
            return
        }

        val status = staff.trackingHealthStatus.orEmpty()
        val message = when {
            status == "not_installed" ->
                "GeoTrack setup missing: app/device not synced"
            status == "stale" ->
                "GeoTrack alert: device stale"
            staff.trackingHealthMissing.isNotEmpty() ->
                "GeoTrack alert: ${staff.trackingHealthMissing.joinToString(", ") { trackingMissingLabel(it) }}"
            status == "healthy" -> {
                health.visibility = View.GONE
                return
            }
            else ->
                "GeoTrack setup needs review"
        }

        health.text = message
        health.visibility = View.VISIBLE
        health.setBackgroundResource(R.drawable.bg_badge_error)
        health.setTextColor(resolveColor(R.attr.colorError))
    }

    private fun trackingMissingLabel(key: String): String {
        return when (key) {
            "background_location" -> "background location missing"
            "battery_optimization" -> "battery optimization not disabled"
            "notification" -> "notification permission missing"
            "activity_recognition" -> "activity permission missing"
            "device" -> "device not synced"
            else -> key.replace("_", " ")
        }
    }

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        _binding = null
    }
}
