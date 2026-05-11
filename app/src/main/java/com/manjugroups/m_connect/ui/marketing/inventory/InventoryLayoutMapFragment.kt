package com.manjugroups.m_connect.ui.marketing.inventory

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch

/**
 * KOS-52 v1 stub: 2D project layout view. Renders rect units only — polygon
 * support and richer hit-testing land after the UX subtask locks the
 * layoutCoordinates schema. Each rect is colored by status so a CP can scan
 * the plot map at a glance.
 */
class InventoryLayoutMapFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val projectId: String get() = requireArguments().getString(ARG_PROJECT_ID).orEmpty()
    private val projectName: String get() = requireArguments().getString(ARG_PROJECT_NAME) ?: "Project"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_inventory_layout_map, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<TextView>(R.id.tvLayoutMapTitle).text = "$projectName · Layout"
        view.findViewById<View>(R.id.btnLayoutMapBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        loadLayout(view)
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

    private fun loadLayout(root: View) {
        val loading = root.findViewById<View>(R.id.layoutMapLoading)
        val empty = root.findViewById<TextView>(R.id.tvLayoutMapEmpty)
        val mapView = root.findViewById<UnitMapView>(R.id.layoutMapView)
        val count = root.findViewById<TextView>(R.id.tvLayoutMapCount)

        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        mapView.visibility = View.GONE
        count.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getInventoryLayout(session.bearerToken, projectId)
                loading.visibility = View.GONE
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load layout"
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                val rectUnits = resp.units.filter {
                    it.layoutCoordinates?.shape == "rect" &&
                        it.layoutCoordinates.x != null &&
                        it.layoutCoordinates.y != null &&
                        it.layoutCoordinates.width != null &&
                        it.layoutCoordinates.height != null
                }
                count.text = "${rectUnits.size} rect unit${if (rectUnits.size == 1) "" else "s"}"
                if (rectUnits.isEmpty()) {
                    empty.text = "No layout coordinates published yet."
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                mapView.setUnits(rectUnits)
                mapView.visibility = View.VISIBLE
            } catch (e: Exception) {
                loading.visibility = View.GONE
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_NAME = "projectName"

        fun newInstance(projectId: String, projectName: String): InventoryLayoutMapFragment {
            val f = InventoryLayoutMapFragment()
            f.arguments = Bundle().apply {
                putString(ARG_PROJECT_ID, projectId)
                putString(ARG_PROJECT_NAME, projectName)
            }
            return f
        }
    }
}
