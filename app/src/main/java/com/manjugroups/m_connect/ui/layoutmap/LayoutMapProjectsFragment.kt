package com.manjugroups.m_connect.ui.layoutmap

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLayoutMapProjectsBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.LayoutMapProject
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.pushDetail
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import kotlinx.coroutines.launch

/**
 * 3D Layout Maps picker. Lists only the projects that have a layout map
 * configured (and that this staff member can open); tapping one opens the map.
 */
class LayoutMapProjectsFragment : Fragment() {

    private var _binding: FragmentLayoutMapProjectsBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()
    private var projects: List<LayoutMapProject> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLayoutMapProjectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLayoutMapsBack.setOnClickListener { navigateUp() }
        binding.btnLayoutMapsRetry.setOnClickListener { load() }
        binding.etLayoutMapsSearch.doAfterTextChanged { render() }
        binding.layoutMapsRefresh.setupPullToRefresh { load() }

        // Full-bleed blue header: keep its content below the status bar.
        val basePaddingTop = binding.layoutMapsHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutMapsHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, basePaddingTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.layoutMapsHeader)

        load()
    }

    private fun load() {
        val session = SessionManager(requireContext())
        if (projects.isEmpty()) showState("Loading projects…", loading = true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { api.getLayoutMaps(session.bearerToken) }
            val b = _binding ?: return@launch
            b.layoutMapsRefresh.dismissRefresh()
            val response = result.getOrNull()
            if (response?.success == true) {
                projects = response.maps.sortedBy { it.name.orEmpty().lowercase() }
                render()
            } else {
                projects = emptyList()
                b.layoutMapsList.removeAllViews()
                showState(response?.error ?: "Couldn't load the projects. Check your connection.", retry = true)
            }
        }
    }

    private fun render() {
        val b = _binding ?: return
        val query = b.etLayoutMapsSearch.text?.toString().orEmpty().trim()
        val shown = projects.filter {
            query.isEmpty() || it.name.orEmpty().contains(query, true) || it.location.orEmpty().contains(query, true)
        }
        b.layoutMapsList.removeAllViews()
        if (shown.isEmpty()) {
            showState(if (projects.isEmpty()) "No project has a 3D layout map yet.\nMaps are uploaded from the web, in the project's Details tab." else "No project matches \"$query\".")
            return
        }
        b.layoutMapsState.visibility = View.GONE
        val inflater = LayoutInflater.from(b.root.context)
        for (project in shown) {
            val row = inflater.inflate(R.layout.item_layout_map_project, b.layoutMapsList, false)
            row.findViewById<TextView>(R.id.tvMapProjectName).text = project.name ?: "Unnamed project"
            row.findViewById<TextView>(R.id.tvMapProjectLocation).apply {
                text = project.location
                visibility = if (project.location.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.tvMapProjectPlots).text = "${project.plotCount ?: 0} plots · 3D map"
            row.setOnClickListener { pushDetail(LayoutMapFragment.newInstance(project.projectId, project.name.orEmpty())) }
            b.layoutMapsList.addView(row)
        }
    }

    private fun showState(message: String, loading: Boolean = false, retry: Boolean = false) {
        val b = _binding ?: return
        b.layoutMapsState.visibility = View.VISIBLE
        b.layoutMapsProgress.visibility = if (loading) View.VISIBLE else View.GONE
        b.tvLayoutMapsState.text = message
        b.btnLayoutMapsRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.parseColor("#0B61CA"), darkStatusIcons = false, fullBleed = true)
        }
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
