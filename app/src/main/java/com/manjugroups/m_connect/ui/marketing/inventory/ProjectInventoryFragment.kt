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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.InventoryUnit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.pushDetail
import com.manjugroups.m_connect.ui.common.showOnce
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.ui.common.commitOnce

/**
 * KOS-52: project-detail screen that lists inventoryUnits with filter chips
 * for type, facing and status. The unit-type chip set is filtered by the
 * project's `scope` so a plots_only project does not show villa/flat chips.
 */
class ProjectInventoryFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var unitTypeFilter: String? = null
    private var facingFilter: String? = null
    private var statusFilter: String? = null

    private val projectId: String get() = requireArguments().getString(ARG_PROJECT_ID).orEmpty()
    private val projectName: String get() = requireArguments().getString(ARG_PROJECT_NAME) ?: "Project"
    private val projectScope: String? get() = requireArguments().getString(ARG_PROJECT_SCOPE)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_inventory, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<TextView>(R.id.tvProjectInventoryTitle).text = projectName
        view.findViewById<TextView>(R.id.tvProjectInventoryScope).apply {
            text = projectScope?.let { "Scope: ${prettyScope(it)}" } ?: ""
            visibility = if (projectScope.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.btnProjectInventoryBack).setOnClickListener {
            navigateUp()
        }
        view.findViewById<View>(R.id.btnOpenLayoutMap).setOnClickListener {
            openLayoutMap()
        }

        setupTypeChips(view.findViewById(R.id.chipsUnitType))
        setupFacingChips(view.findViewById(R.id.chipsFacing))
        setupStatusChips(view.findViewById(R.id.chipsStatus))

        loadUnits(view)
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

    private fun setupTypeChips(group: ChipGroup) {
        val allTypes = listOf("plot" to "Plot", "villa" to "Villa", "flat" to "Flat")
        val allowed = allTypes.filter { allowedByScope(it.first) }
        addChip(group, "All", null) { unitTypeFilter = null; reload() }
        allowed.forEach { (id, label) ->
            addChip(group, label, id) { unitTypeFilter = id; reload() }
        }
        // Default-select "All".
        if (group.childCount > 0) (group.getChildAt(0) as Chip).isChecked = true
    }

    private fun setupFacingChips(group: ChipGroup) {
        addChip(group, "All", null) { facingFilter = null; reload() }
        listOf("N", "E", "S", "W", "NE", "NW", "SE", "SW").forEach { dir ->
            addChip(group, dir, dir) { facingFilter = dir; reload() }
        }
        if (group.childCount > 0) (group.getChildAt(0) as Chip).isChecked = true
    }

    private fun setupStatusChips(group: ChipGroup) {
        val statuses = listOf(
            null to "All",
            "available" to "Available",
            "held" to "Held",
            "booked" to "Booked",
            "sold" to "Sold",
        )
        statuses.forEach { (id, label) ->
            addChip(group, label, id) { statusFilter = id; reload() }
        }
        if (group.childCount > 0) (group.getChildAt(0) as Chip).isChecked = true
    }

    private fun addChip(group: ChipGroup, label: String, tag: String?, onSelected: () -> Unit) {
        val chip = Chip(requireContext()).apply {
            text = label
            isCheckable = true
            tagId(tag)
        }
        chip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) onSelected()
        }
        group.addView(chip)
    }

    private fun Chip.tagId(value: String?) {
        this.tag = value
    }

    private fun allowedByScope(unitType: String): Boolean {
        val scope = projectScope ?: return true
        return when (scope) {
            "mixed" -> true
            "plots_only" -> unitType == "plot"
            "villas" -> unitType == "villa"
            "flats" -> unitType == "flat"
            else -> true
        }
    }

    private fun reload() {
        val v = view ?: return
        loadUnits(v)
    }

    private fun loadUnits(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<TextView>(R.id.tvProjectInventoryEmpty)
        val list = root.findViewById<LinearLayout>(R.id.projectInventoryList)
        val count = root.findViewById<TextView>(R.id.tvProjectInventoryCount)

        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        empty.visibility = View.GONE
        list.removeAllViews()
        count.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listInventoryUnits(
                    token = session.bearerToken,
                    projectId = projectId,
                    unitType = unitTypeFilter,
                    facing = facingFilter,
                    status = statusFilter,
                )
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load units"
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                val units = resp.units
                count.text = "${units.size} unit${if (units.size == 1) "" else "s"}"
                if (units.isEmpty()) {
                    empty.text = "No units match the current filter."
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                units.forEach { u -> list.addView(createUnitRow(u, list)) }
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun createUnitRow(unit: InventoryUnit, parent: ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_inventory_unit, parent, false)
        row.findViewById<TextView>(R.id.tvUnitNumber).text =
            (unit.unitNumber ?: "—") + "  ·  " + (unit.unitType ?: "unit")
        row.findViewById<TextView>(R.id.tvUnitMeta).text = formatMeta(unit)
        val statusPill = row.findViewById<TextView>(R.id.tvUnitStatus)
        val statusStr = unit.status ?: "unknown"
        statusPill.text = statusStr.replaceFirstChar { it.uppercase() }
        statusPill.setTextColor(statusColor(statusStr))

        val canBook = canCreateBooking() && statusStr.equals("available", ignoreCase = true)
        row.setOnClickListener {
            if (canBook) {
                openBookingForm(unit)
            }
        }
        row.isClickable = canBook
        row.isFocusable = canBook
        return row
    }

    private fun canCreateBooking(): Boolean =
        session.hasPermission(PERM_BOOKINGS_CREATE)

    private fun statusColor(status: String): Int = when (status) {
        "available" -> Color.parseColor("#067647")
        "held" -> Color.parseColor("#B54708")
        "booked" -> Color.parseColor("#1849A9")
        "sold" -> Color.parseColor("#B42318")
        else -> Color.parseColor("#475467")
    }

    private fun formatMeta(u: InventoryUnit): String {
        val parts = mutableListOf<String>()
        u.facing?.let { parts += "Facing $it" }
        u.area?.let { parts += "${formatArea(it)} sqft" }
        u.dimensions?.let { parts += it }
        u.floor?.let { parts += "Floor $it" }
        u.priceSnapshot?.let { parts += "₹${formatPrice(it)}" }
        return parts.joinToString(" · ").ifBlank { "—" }
    }

    private fun formatArea(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)

    private fun formatPrice(value: Double): String =
        java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("en-IN")).format(value)

    private fun openBookingForm(unit: InventoryUnit) {
        CompleteCpVisitBottomSheet.forStandaloneBooking(
            projectId = projectId,
            projectName = projectName,
            unitId = unit.id,
            unitNumber = unit.unitNumber,
        ).showOnce(parentFragmentManager, "inventory_booking_create")
    }

    private fun openLayoutMap() {
        val fragment = InventoryLayoutMapFragment.newInstance(
            projectId = projectId,
            projectName = projectName,
        )
        parentFragmentManager.pushDetail(fragment)
    }

    private fun prettyScope(scope: String): String = when (scope) {
        "plots_only" -> "Plots only"
        "villas" -> "Villas"
        "flats" -> "Flats"
        "mixed" -> "Mixed"
        else -> scope
    }

    companion object {
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_NAME = "projectName"
        private const val ARG_PROJECT_SCOPE = "projectScope"
        const val PERM_BOOKINGS_CREATE = "marketing.bookings.create"

        fun newInstance(
            projectId: String,
            projectName: String,
            projectScope: String?,
        ): ProjectInventoryFragment {
            val f = ProjectInventoryFragment()
            f.arguments = Bundle().apply {
                putString(ARG_PROJECT_ID, projectId)
                putString(ARG_PROJECT_NAME, projectName)
                putString(ARG_PROJECT_SCOPE, projectScope)
            }
            return f
        }
    }
}
