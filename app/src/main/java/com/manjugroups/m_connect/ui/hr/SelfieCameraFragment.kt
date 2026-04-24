package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentSelfieCameraBinding

class SelfieCameraFragment : Fragment() {

    private var _binding: FragmentSelfieCameraBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelfieCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBackCamera.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnCloseCamera.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnCapture.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SelfieClockInDetailFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#000000"), false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
