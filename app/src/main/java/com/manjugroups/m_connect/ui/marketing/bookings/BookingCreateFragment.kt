package com.manjugroups.m_connect.ui.marketing.bookings

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.util.ongoingOnly
import com.manjugroups.m_connect.network.CreateBookingRequest
import com.manjugroups.m_connect.network.InventoryUnit
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.TelecallerLeadSearchData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

/**
 * KOS-52: minimal booking-create form. The picker uses
 * inventoryUnits.listByProject({ status: "available" }) and only allows
 * available rows — sold/booked are rejected client-side and the server-side
 * validator (KOS-40) is the second gate.
 *
 * The booking POST always sends `plotId` when a unit was picked.
 */
class BookingCreateFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var selectedProject: MarketingProject? = null
    private var selectedUnit: InventoryUnit? = null
    private var selectedLead: TelecallerLeadSearchData? = null
    private var leadMatches: List<TelecallerLeadSearchData> = emptyList()
    private var lastLeadLookupPhone: String? = null
    private var bookingDate: String = todayYmd()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_booking_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val args = arguments
        if (args != null && args.containsKey(ARG_PROJECT_ID)) {
            selectedProject = MarketingProject(
                id = args.getString(ARG_PROJECT_ID).orEmpty(),
                name = args.getString(ARG_PROJECT_NAME),
            )
        }
        if (args != null && args.containsKey(ARG_UNIT_ID)) {
            selectedUnit = InventoryUnit(
                id = args.getString(ARG_UNIT_ID).orEmpty(),
                projectId = args.getString(ARG_PROJECT_ID),
                unitNumber = args.getString(ARG_UNIT_NUMBER),
                status = "available",
            )
        }

        view.findViewById<TextView>(R.id.tvBookingTitle).text = "New Booking"
        view.findViewById<View>(R.id.btnBookingBack).setOnClickListener {
            navigateUp()
        }

        view.findViewById<TextView>(R.id.tvBookingProject).apply {
            text = selectedProject?.name ?: "Select project"
            setOnClickListener { pickProject(this) }
        }
        view.findViewById<TextView>(R.id.tvBookingUnit).apply {
            text = selectedUnit?.unitNumber?.let { "Unit $it" } ?: "Select unit"
            setOnClickListener { pickUnit(this) }
        }
        view.findViewById<TextView>(R.id.tvBookingDate).apply {
            text = bookingDate
            setOnClickListener { pickDate(this) }
        }
        setupLeadLookup(view)

        view.findViewById<View>(R.id.btnBookingSubmit).setOnClickListener {
            submitBooking(view)
        }

        if (!canCreateBooking()) {
            Toast.makeText(
                requireContext(),
                "You don't have permission to create bookings.",
                Toast.LENGTH_LONG,
            ).show()
            view.findViewById<View>(R.id.btnBookingSubmit).isEnabled = false
        }
    }

    private fun setupLeadLookup(root: View) {
        val mobileInput = root.findViewById<EditText>(R.id.etBookingMobile)
        val leadRow = root.findViewById<TextView>(R.id.tvBookingLinkedLead)
        leadRow.setOnClickListener {
            if (leadMatches.size > 1) {
                showLeadPicker(root)
            } else if (selectedLead != null) {
                applyLead(root, selectedLead)
            }
        }
        mobileInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val phone = normalizePhone(s?.toString().orEmpty())
                if (phone.length < 10) {
                    selectedLead = null
                    leadMatches = emptyList()
                    lastLeadLookupPhone = null
                    leadRow.visibility = View.GONE
                    return
                }
                if (phone == lastLeadLookupPhone) return
                lastLeadLookupPhone = phone
                searchLeadByPhone(root, phone)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun searchLeadByPhone(root: View, phone: String) {
        val leadRow = root.findViewById<TextView>(R.id.tvBookingLinkedLead)
        selectedLead = null
        leadMatches = emptyList()
        leadRow.visibility = View.VISIBLE
        leadRow.text = "Searching lead..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.searchTelecallerLeadsByPhone(session.bearerToken, phone)
                if (normalizePhone(root.findViewById<EditText>(R.id.etBookingMobile).text.toString()) != phone) {
                    return@launch
                }
                if (!resp.success) {
                    leadRow.text = resp.error ?: "Lead search failed"
                    return@launch
                }
                leadMatches = resp.leads
                if (leadMatches.isEmpty()) {
                    leadRow.text = "No linked lead found"
                    return@launch
                }
                applyLead(root, leadMatches.first())
                if (leadMatches.size > 1) {
                    leadRow.text = "${leadRow.text} · tap to change"
                }
            } catch (e: Exception) {
                leadRow.text = "Lead search failed"
            }
        }
    }

    private fun showLeadPicker(root: View) {
        val labels = leadMatches.map { lead ->
            val name = lead.displayName()
            val phone = lead.mobileNumber ?: "No phone"
            listOf(name, phone).filter { it.isNotBlank() }.joinToString(" · ")
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select linked lead")
            .setItems(labels) { _, idx ->
                applyLead(root, leadMatches[idx])
            }
            .show()
    }

    private fun applyLead(root: View, lead: TelecallerLeadSearchData?) {
        if (lead == null) return
        selectedLead = lead
        val nameInput = root.findViewById<EditText>(R.id.etBookingClientName)
        val name = lead.displayName()
        if (name.isNotBlank() && nameInput.text.toString().trim().isBlank()) {
            nameInput.setText(name)
        }
        root.findViewById<TextView>(R.id.tvBookingLinkedLead).apply {
            visibility = View.VISIBLE
            text = "Linked lead: ${name.ifBlank { lead.mobileNumber ?: lead.id }}"
        }
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

    private fun canCreateBooking(): Boolean =
        session.hasPermission("marketing.bookings.create")

    private fun pickProject(label: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load projects")
                    return@launch
                }
                // Bookings are only made against ongoing projects (matches the
                // web's "Ongoing" = status == "ongoing").
                val projects = resp.projects.ongoingOnly()
                if (projects.isEmpty()) {
                    toast("No ongoing projects available")
                    return@launch
                }
                if (view == null) return@launch
                val options = projects.map { SearchableOption(it, it.name ?: "Unnamed", it.status) }
                SearchableSelectionDialog.show(requireContext(), "Select project", options) { p ->
                    if (view == null) return@show
                    selectedProject = p
                    // Picking a different project invalidates the unit choice.
                    selectedUnit = null
                    label.text = p.name ?: "Select project"
                    view?.findViewById<TextView>(R.id.tvBookingUnit)?.text = "Select unit"
                }
            } catch (e: Exception) {
                toast("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun pickUnit(label: TextView) {
        val proj = selectedProject
        if (proj == null) {
            toast("Pick a project first")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listInventoryUnits(
                    token = session.bearerToken,
                    projectId = proj.id,
                    status = "available",
                )
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load units")
                    return@launch
                }
                val available = resp.units.filter { it.status == "available" }
                if (available.isEmpty()) {
                    toast("No available units in this project")
                    return@launch
                }
                if (view == null) return@launch
                val options = available.map { u ->
                    val sub = listOfNotNull(
                        u.unitType,
                        u.facing?.let { "facing $it" },
                        u.area?.let { "${it.toInt()} sqft" },
                    ).joinToString(" · ")
                    SearchableOption(u, u.unitNumber ?: "Unit", sub)
                }
                SearchableSelectionDialog.show(requireContext(), "Select available unit", options) { picked ->
                    // Defensive: never accept anything that is not available
                    // even if the picker drifted (race with another agent).
                    if (picked.status != "available") {
                        toast("Unit is no longer available")
                        return@show
                    }
                    selectedUnit = picked
                    label.text = "Unit ${picked.unitNumber ?: picked.id}"
                }
            } catch (e: Exception) {
                toast("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun pickDate(label: TextView) {
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            bookingDate = date
            label.text = date
        }
        CalendarRangePickerSheet.newInstance(
            title = "Booking Date",
            subtitle = "Select Date",
            initialFrom = bookingDate,
            initialTo = bookingDate,
            resultKey = RESULT_KEY_DATE,
        ).showOnce(parentFragmentManager, "booking_create_calendar")
    }

    private fun submitBooking(root: View) {
        val name = root.findViewById<EditText>(R.id.etBookingClientName).text.toString().trim()
        val mobile = root.findViewById<EditText>(R.id.etBookingMobile).text.toString().trim()
        val cost = root.findViewById<EditText>(R.id.etBookingCost).text.toString().trim()
        val advance = root.findViewById<EditText>(R.id.etBookingAdvance).text.toString().trim()

        if (name.isEmpty()) { toast("Client name is required"); return }
        if (mobile.length < 10) { toast("Valid mobile number is required"); return }
        val proj = selectedProject ?: run { toast("Pick a project"); return }
        val unit = selectedUnit
        if (unit != null && unit.status != "available") {
            toast("Selected unit is not available — pick a different unit.")
            return
        }

        val req = CreateBookingRequest(
            clientName = name,
            mobileNumber = mobile,
            bookingDate = bookingDate,
            leadId = selectedLead?.id,
            projectId = proj.id,
            // KOS-52 acceptance: always pass plotId when a unit was selected.
            plotId = unit?.id,
            plotNo = unit?.unitNumber,
            bookingCost = cost.toDoubleOrNull(),
            advanceAmount = advance.toDoubleOrNull(),
            balanceAmount = run {
                val c = cost.toDoubleOrNull()
                val a = advance.toDoubleOrNull()
                if (c != null && a != null) c - a else null
            },
        )

        val submit = root.findViewById<View>(R.id.btnBookingSubmit)
        submit.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.createBooking(session.bearerToken, req)
                submit.isEnabled = true
                if (!resp.success) {
                    toast(resp.error ?: "Failed to create booking")
                    return@launch
                }
                toast("Booking created")
                navigateUp()
            } catch (e: Exception) {
                submit.isEnabled = true
                toast("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun normalizePhone(value: String): String =
        value.filter { it.isDigit() }.takeLast(10)

    private fun TelecallerLeadSearchData.displayName(): String =
        latestAnalysisProfile?.clientName?.trim().takeUnless { it.isNullOrBlank() }
            ?: contactName?.trim().takeUnless { it.isNullOrBlank() }
            ?: mobileNumber?.trim().orEmpty()

    private fun todayYmd(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    companion object {
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_NAME = "projectName"
        private const val ARG_UNIT_ID = "unitId"
        private const val ARG_UNIT_NUMBER = "unitNumber"
        private const val RESULT_KEY_DATE = "booking_create_date_calendar"

        fun newEmpty(): BookingCreateFragment = BookingCreateFragment()

        fun forUnit(
            projectId: String,
            projectName: String,
            unitId: String,
            unitNumber: String?,
        ): BookingCreateFragment {
            val f = BookingCreateFragment()
            f.arguments = Bundle().apply {
                putString(ARG_PROJECT_ID, projectId)
                putString(ARG_PROJECT_NAME, projectName)
                putString(ARG_UNIT_ID, unitId)
                putString(ARG_UNIT_NUMBER, unitNumber)
            }
            return f
        }
    }
}
