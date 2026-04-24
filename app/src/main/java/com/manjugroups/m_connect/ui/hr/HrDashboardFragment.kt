package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentHrDashboardBinding
import kotlinx.coroutines.launch

class HrDashboardFragment : Fragment() {

    private var _binding: FragmentHrDashboardBinding? = null
    private val binding get() = _binding!!
    private val flowViewModel: AttendanceFlowViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHrDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClockInNow.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ClockInAreaFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnClockOut.setOnClickListener {
            ClockOutConfirmBottomSheet().show(parentFragmentManager, "clock_out_confirm")
        }

        parentFragmentManager.setFragmentResultListener(
            ClockOutConfirmBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ClockOutConfirmBottomSheet.KEY_CONFIRMED, false)) {
                ClockOutSuccessBottomSheet().show(parentFragmentManager, "clock_out_success")
            }
        }

        parentFragmentManager.setFragmentResultListener(
            ClockOutSuccessBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ClockOutSuccessBottomSheet.KEY_CLOSED, false)) {
                flowViewModel.markClockOut()
            }
        }

        collectState()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(true)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#7155FF"), false)
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flowViewModel.uiState.collect { state ->
                    binding.tvTodayHours.text = state.todayHours
                    binding.tvLatestTotalHours.text = state.latestTotalHours
                    binding.tvLatestRange.text = state.latestRange

                    if (state.isClockedIn) {
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                    } else {
                        binding.clockInButtonGroup.visibility = View.VISIBLE
                        binding.clockedInButtonGroup.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
