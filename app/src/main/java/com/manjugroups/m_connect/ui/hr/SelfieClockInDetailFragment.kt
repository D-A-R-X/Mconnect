package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.databinding.FragmentSelfieClockInDetailBinding

class SelfieClockInDetailFragment : Fragment() {

    private var _binding: FragmentSelfieClockInDetailBinding? = null
    private val binding get() = _binding!!
    private val flowViewModel: AttendanceFlowViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelfieClockInDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnRetakePhoto.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        parentFragmentManager.setFragmentResultListener(
            ClockInSuccessBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ClockInSuccessBottomSheet.KEY_DONE, false)) {
                flowViewModel.markClockIn()
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }

        binding.btnClockInAction.setOnClickListener {
            ClockInSuccessBottomSheet().show(parentFragmentManager, "clock_in_success")
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
