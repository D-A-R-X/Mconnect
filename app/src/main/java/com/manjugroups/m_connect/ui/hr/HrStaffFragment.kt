package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHrStaffBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

class HrStaffFragment : Fragment() {

    private var _binding: FragmentHrStaffBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HrStaffViewModel by viewModels()
    private lateinit var session: SessionManager
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHrStaffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnSearch.setOnClickListener { parentFragmentManager.popBackStack() }

        setupSearch()
        collectState()
        viewModel.loadStaff(session.bearerToken)
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
                        }
                        is HrStaffUiState.Loaded -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            renderStaffList(state)
                        }
                        is HrStaffUiState.Error -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
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
                    .commit()
            }

            binding.staffList.addView(card)
        }

        // Load more button
        if (state.hasMore && state.searchQuery.isBlank()) {
            val loadMoreBtn = TextView(requireContext()).apply {
                text = if (state.isLoadingMore) "Loading..." else "Load More"
                textSize = 14f
                typeface = resources.getFont(R.font.inter_semibold)
                setTextColor(resolveColor(R.attr.colorAccentPrimary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 16)
                setOnClickListener {
                    if (!state.isLoadingMore) viewModel.loadMore(session.bearerToken)
                }
            }
            binding.staffList.addView(loadMoreBtn)
        }
    }

    private fun formatPhone(phone: String): String {
        return if (phone.length == 10) "${phone.substring(0, 5)} ${phone.substring(5)}" else phone
    }

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        _binding = null
    }
}
