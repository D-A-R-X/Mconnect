package com.manjugroups.m_connect.ui.marketing.inventory

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

/**
 * KOS-52: lists projects available to this staff so they can drill into the
 * inventory units screen. Backed by /api/marketing/projects which is gated by
 * a valid session; finer-grained role gating is enforced by the entry point
 * in AppLibrary (projects.view).
 */
class InventoryProjectsListFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_inventory_projects, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnInventoryProjectsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        loadProjects(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    private fun loadProjects(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<TextView>(R.id.tvInventoryProjectsEmpty)
        val list = root.findViewById<LinearLayout>(R.id.inventoryProjectsList)
        val countText = root.findViewById<TextView>(R.id.tvInventoryProjectsCount)

        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        empty.visibility = View.GONE
        list.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load projects"
                    empty.visibility = View.VISIBLE
                    countText.text = ""
                    return@launch
                }
                val projects = resp.projects
                countText.text = "${projects.size}"
                if (projects.isEmpty()) {
                    empty.text = "No projects available."
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                projects.forEach { p ->
                    list.addView(createProjectRow(p, list))
                }
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun createProjectRow(project: MarketingProject, parent: ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_inventory_project, parent, false)
        row.findViewById<TextView>(R.id.tvProjectName).text = project.name ?: "Unnamed project"
        row.findViewById<TextView>(R.id.tvProjectMeta).text = formatMeta(project)
        row.setOnClickListener { openProjectDetail(project) }
        return row
    }

    private fun formatMeta(p: MarketingProject): String {
        val parts = mutableListOf<String>()
        p.scope?.let { parts += "Scope: ${prettyScope(it)}" }
        p.status?.let { parts += "Status: $it" }
        p.location?.let { parts += it }
        return parts.joinToString(" · ").ifBlank { "—" }
    }

    private fun prettyScope(scope: String): String = when (scope) {
        "plots_only" -> "Plots only"
        "villas" -> "Villas"
        "flats" -> "Flats"
        "mixed" -> "Mixed"
        else -> scope
    }

    private fun openProjectDetail(project: MarketingProject) {
        val fragment = ProjectInventoryFragment.newInstance(
            projectId = project.id,
            projectName = project.name ?: "Project",
            projectScope = project.scope,
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }
}
