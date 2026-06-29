package com.manjugroups.m_connect.ui.issues

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.databinding.FragmentIssuesBinding
import com.manjugroups.m_connect.ui.common.navigateUp

/**
2. * "Issues" — displays project issues. Matches screenshot empty state and header controls.
3. */
class IssuesFragment : Fragment() {

    private var _binding: FragmentIssuesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIssuesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back button navigation
        binding.btnIssuesBack.setOnClickListener {
            navigateUp()
        }

        // Add Issue button click action
        binding.btnCreateIssue.setOnClickListener {
            Toast.makeText(requireContext(), "Create Issue is coming soon", Toast.LENGTH_SHORT).show()
        }

        // Search action listener / typing handling placeholder
        binding.etSearchIssues.setOnEditorActionListener { _, _, _ ->
            Toast.makeText(requireContext(), "Search is not available yet", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Hide MainActivity bottom tab bar when inside child fragment issues
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
