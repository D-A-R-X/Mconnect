package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateCpVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CreateCpVisitBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    // Selected variables
    private var selectedStaff: StaffData? = null
    private var selectedProject: MarketingProject? = null
    private var selectedDate: String = ""
    private var selectedTime: String = ""
    // CP Type — visit intent enum (sv_cum_cp / follow_up / booking_cp /
    // collection_cp / old_client / gift_distribution). Optional; null
    // means no type was picked and the server stores the row without it.
    private var selectedCpType: CpTypeOption? = null

    /** CP visit intent enum shared with the web form. The `id` is the
     *  wire value sent to convex; `label` is what the picker shows. */
    private data class CpTypeOption(
        val id: String,
        val label: String,
        val sublabel: String,
    )

    private val cpTypeOptions = listOf(
        CpTypeOption("sv_cum_cp", "SV cum CP", "Combo site visit + CP"),
        CpTypeOption("follow_up", "Follow-up", "Continues a postponed client"),
        CpTypeOption("booking_cp", "Booking CP", "Paperwork run for an active booking"),
        CpTypeOption("collection_cp", "Collection CP", "Payment chase at client place"),
        CpTypeOption("old_client", "Old Client", "Re-engagement touch"),
        CpTypeOption("gift_distribution", "Gift Distribution", "Loyalty drop-off"),
    )

    // Caches for fast display
    private var staffCache: List<StaffData> = emptyList()
    private var projectCache: List<MarketingProject> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_create_cp_visit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val etPhone = view.findViewById<EditText>(R.id.etClientPhone)
        val etStaff = view.findViewById<EditText>(R.id.etFieldStaff)
        val etProj = view.findViewById<EditText>(R.id.etProject)
        val etCpType = view.findViewById<EditText>(R.id.etCpType)
        val etDateTime = view.findViewById<EditText>(R.id.etDateTime)

        val etDoorNo = view.findViewById<EditText>(R.id.etDoorNo)
        val etPincode = view.findViewById<EditText>(R.id.etPincode)
        val etVillage = view.findViewById<EditText>(R.id.etVillage)
        val etTaluk = view.findViewById<EditText>(R.id.etTaluk)
        val etDistrict = view.findViewById<EditText>(R.id.etDistrict)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etLocality = view.findViewById<EditText>(R.id.etLocality)
        val etState = view.findViewById<EditText>(R.id.etState)
        val etFullAddress = view.findViewById<EditText>(R.id.etFullAddress)

        val etMaps = view.findViewById<EditText>(R.id.etGoogleMapsLink)
        val etLat = view.findViewById<EditText>(R.id.etLatitude)
        val etLng = view.findViewById<EditText>(R.id.etLongitude)
        val etNotes = view.findViewById<EditText>(R.id.etNotes)

        val btnCancel = view.findViewById<View>(R.id.btnCancel)
        val btnSubmit = view.findViewById<View>(R.id.btnSubmit)

        // Load logged-in staff info if available
        val currentStaffId = session.staffId
        val currentStaffName = session.userName
        if (!currentStaffId.isNullOrBlank()) {
            selectedStaff = StaffData(
                id = currentStaffId,
                name = currentStaffName ?: "Me",
                phone = session.userPhone,
                role = null,
                designation = null,
                status = null,
                employeeId = null,
                department = null
            )
            etStaff.setText(currentStaffName ?: "Me")
        }

        // Setup click listeners for spinners
        etStaff.setOnClickListener { pickStaff(etStaff) }
        etProj.setOnClickListener { pickProject(etProj) }
        etCpType.setOnClickListener { pickCpType(etCpType) }
        etDateTime.setOnClickListener { pickDateTime(etDateTime) }

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        btnSubmit.setOnClickListener {
            // Validation
            val phone = etPhone.text.toString().filter { it.isDigit() }.takeLast(10)
            if (phone.length != 10) {
                toast("Enter a valid 10-digit phone number")
                return@setOnClickListener
            }

            val staff = selectedStaff
            if (staff == null || staff.id.isNullOrBlank()) {
                toast("Select field staff")
                return@setOnClickListener
            }

            val project = selectedProject
            if (project == null || project.id.isNullOrBlank()) {
                toast("Select project")
                return@setOnClickListener
            }

            if (selectedDate.isBlank() || selectedTime.isBlank()) {
                toast("Select Date & Time")
                return@setOnClickListener
            }

            val village = etVillage.text.toString().trim()
            val taluk = etTaluk.text.toString().trim()
            val district = etDistrict.text.toString().trim()
            val locality = etLocality.text.toString().trim()
            val fullAddrInput = etFullAddress.text.toString().trim()

            if (village.isBlank()) {
                toast("Village is required")
                return@setOnClickListener
            }
            if (taluk.isBlank()) {
                toast("Taluk is required")
                return@setOnClickListener
            }
            if (district.isBlank()) {
                toast("District is required")
                return@setOnClickListener
            }
            if (locality.isBlank()) {
                toast("Locality is required")
                return@setOnClickListener
            }
            if (fullAddrInput.isBlank()) {
                toast("Full Address is required")
                return@setOnClickListener
            }

            val doorNo = etDoorNo.text.toString().trim()
            val pincode = etPincode.text.toString().trim()
            val city = etCity.text.toString().trim()
            val state = etState.text.toString().trim()

            // Compile composite address
            val addressParts = listOfNotNull(
                doorNo.takeIf { it.isNotBlank() }?.let { "Door/Plot No: $it" },
                locality.takeIf { it.isNotBlank() }?.let { "Locality: $it" },
                village.takeIf { it.isNotBlank() }?.let { "Village: $it" },
                taluk.takeIf { it.isNotBlank() }?.let { "Taluk: $it" },
                city.takeIf { it.isNotBlank() }?.let { "City: $it" },
                district.takeIf { it.isNotBlank() }?.let { "District: $it" },
                state.takeIf { it.isNotBlank() }?.let { "State: $it" },
                pincode.takeIf { it.isNotBlank() }?.let { "Pincode: $it" },
                fullAddrInput.takeIf { it.isNotBlank() }?.let { "Full Address: $it" }
            )
            val compiledAddress = addressParts.joinToString(", ")

            val maps = etMaps.text.toString().trim()
            val latVal = etLat.text.toString().trim().toDoubleOrNull()
            val lngVal = etLng.text.toString().trim().toDoubleOrNull()
            val notesVal = etNotes.text.toString().trim()

            btnSubmit.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = geoApi.createCpVisit(
                        session.bearerToken,
                        CreateCpVisitRequest(
                            mobileNumber = phone,
                            assignedStaffId = staff.id,
                            scheduledDate = selectedDate,
                            scheduledTime = selectedTime,
                            visitAddress = compiledAddress,
                            visitLat = latVal,
                            visitLng = lngVal,
                            googleMapsLink = maps.takeIf { it.isNotBlank() },
                            notes = notesVal.takeIf { it.isNotBlank() },
                            projectId = project.id,
                            cpType = selectedCpType?.id,
                        )
                    )
                    btnSubmit.isEnabled = true
                    if (!resp.success) {
                        toast(resp.error ?: "Failed to create CP visit")
                        return@launch
                    }
                    toast("CP visit created successfully")
                    setFragmentResult(RESULT_KEY_CREATED, bundleOf("success" to true))
                    dismissAllowingStateLoss()
                } catch (e: Exception) {
                    btnSubmit.isEnabled = true
                    toast(e.message ?: "Failed to create CP visit")
                }
            }
        }
    }

    private fun pickStaff(label: EditText) {
        if (staffCache.isNotEmpty()) {
            showStaffPicker(label, staffCache)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken)
                if (!resp.success) {
                    toast("Failed to load staff list")
                    return@launch
                }
                staffCache = resp.staff
                showStaffPicker(label, resp.staff)
            } catch (e: Exception) {
                toast("Network error: ${e.message}")
            }
        }
    }

    private fun showStaffPicker(label: EditText, items: List<StaffData>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select field staff",
            options = items.map { s ->
                SearchableOption(
                    item = s,
                    title = s.name ?: "Unnamed Staff",
                    subtitle = listOfNotNull(s.employeeId, s.role).joinToString(" • "),
                    keywords = listOfNotNull(s.id, s.name, s.employeeId, s.role, s.department).joinToString(" ")
                )
            },
            emptyMessage = "No staff found"
        ) { staff ->
            selectedStaff = staff
            label.setText(staff.name ?: "Selected")
        }
    }

    private fun pickProject(label: EditText) {
        if (projectCache.isNotEmpty()) {
            showProjectPicker(label, projectCache)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load projects")
                    return@launch
                }
                projectCache = resp.projects
                showProjectPicker(label, resp.projects)
            } catch (e: Exception) {
                toast("Network error: ${e.message}")
            }
        }
    }

    private fun showProjectPicker(label: EditText, items: List<MarketingProject>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select project",
            options = items.map { p ->
                SearchableOption(
                    item = p,
                    title = p.name ?: "Unnamed project",
                    subtitle = listOfNotNull(p.location, p.status).joinToString(" • "),
                    keywords = listOfNotNull(p.id, p.scope, p.location, p.status).joinToString(" ")
                )
            },
            emptyMessage = "No projects found"
        ) { project ->
            selectedProject = project
            label.setText(project.name ?: "Selected")
        }
    }

    private fun pickCpType(label: EditText) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select CP type",
            options = cpTypeOptions.map { opt ->
                SearchableOption(
                    item = opt,
                    title = opt.label,
                    subtitle = opt.sublabel,
                    keywords = opt.id + " " + opt.label + " " + opt.sublabel,
                )
            },
            emptyMessage = "No CP types available",
        ) { picked ->
            selectedCpType = picked
            label.setText(picked.label)
        }
    }

    private fun pickDateTime(label: EditText) {
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            selectedDate = date

            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            TimePickerDialog(
                requireContext(),
                { _, h, m ->
                    val timeStr = String.format(Locale.US, "%02d:%02d", h, m)
                    selectedTime = timeStr
                    label.setText("$selectedDate $selectedTime")
                },
                hour,
                minute,
                false // 12-hour format
            ).show()
        }
        CalendarRangePickerSheet.newInstance(
            title = "CP Visit Date",
            subtitle = "Select Date",
            initialFrom = selectedDate.takeIf { it.isNotBlank() } ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time),
            initialTo = selectedDate.takeIf { it.isNotBlank() } ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time),
            resultKey = RESULT_KEY_DATE
        ).show(parentFragmentManager, "cp_visit_create_calendar")
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val RESULT_KEY_CREATED = "cp_visit_created_result"
        private const val RESULT_KEY_DATE = "cp_visit_create_date_calendar"

        fun newInstance(): CreateCpVisitBottomSheet = CreateCpVisitBottomSheet()
    }
}
