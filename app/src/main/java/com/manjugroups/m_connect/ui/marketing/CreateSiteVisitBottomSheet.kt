package com.manjugroups.m_connect.ui.marketing

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateSiteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.network.TelecallerLeadSearchData
import com.manjugroups.m_connect.ui.common.MapPinDropBottomSheet
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.util.ongoingThenCompleted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class CreateSiteVisitBottomSheet : BottomSheetDialogFragment() {
    private data class Choice(val id: String, val label: String, val detail: String = "")
    private data class VisitorFields(
        val root: View,
        val name: EditText,
        val relation: EditText,
        val age: EditText,
        val veg: CheckBox,
    )

    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private var requestId = UUID.randomUUID().toString()
    private var submitJob: Job? = null

    private var routing: Choice? = null
    private var origin = Choice("telecaller", "Calls")
    private var travelMode = Choice("cab", "Cab required")
    private var lead: TelecallerLeadSearchData? = null
    private var project: MarketingProject? = null
    private var scheduledDate = ""
    private var scheduledTime = ""
    private var pickupTime = ""
    private var pinLat: Double? = null
    private var pinLng: Double? = null
    private var pinMapsLink: String? = null
    private var lmo: StaffData? = null
    private var bdo: StaffData? = null
    private var incharge: StaffData? = null
    private var fieldStaff: StaffData? = null
    private var hod: StaffData? = null
    private var avp: StaffData? = null
    private var gm: StaffData? = null
    private var seniorManager: StaffData? = null
    private var staffCache: List<StaffData> = emptyList()
    private var projectCache: List<MarketingProject> = emptyList()
    private val visitorFields = mutableListOf<VisitorFields>()
    private var lastPhoneLookup = ""
    private var phoneLookupJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(STATE_REQUEST_ID) ?: requestId
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REQUEST_ID, requestId)
        super.onSaveInstanceState(outState)
    }

    private val routingChoices = listOf(
        Choice("direct_sv", "Direct SV", "Client travels directly; no confirmation handoff"),
        Choice("same_area", "Same Area", "Field staff verifies the client before the SV"),
        Choice("out_of_station", "Outstation", "Requires GM verification"),
        Choice("immediate_pickup", "Immediate SV", "Today only; requires GM verification"),
    )
    private val originChoices = listOf(
        Choice("telecaller", "Calls"), Choice("walk_in", "Walk-in"),
        Choice("campaign", "Campaign"), Choice("referral", "Referral"),
        Choice("client_place_visit", "Client place visit"),
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { shown ->
            (shown as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { sheet ->
                    sheet.setBackgroundColor(Color.TRANSPARENT)
                    sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                    BottomSheetBehavior.from(sheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                        peekHeight = resources.displayMetrics.heightPixels
                    }
                }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_create_site_visit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        bindPickers(view)
        bindPhoneLookup(view)
        bindVisitors(view)
        view.findViewById<View>(R.id.btnSvDropPin).setOnClickListener { openMap(view) }
        view.findViewById<View>(R.id.btnSvCancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnSvSubmit).setOnClickListener { submit(view) }
        applyRouting(view)
        preloadOptions()
    }

    override fun onDestroyView() {
        submitJob?.cancel()
        phoneLookupJob?.cancel()
        super.onDestroyView()
    }

    private fun bindPickers(root: View) {
        root.findViewById<EditText>(R.id.etSvType).setOnClickListener {
            pickChoice("Select SV type", routingChoices) { routing = it; setText(R.id.etSvType, it.label); applyRouting(root) }
        }
        root.findViewById<EditText>(R.id.etSvOrigin).apply {
            setText(origin.label)
            setOnClickListener { pickChoice("Select origin", originChoices) { choice -> origin = choice; setText(choice.label) } }
        }
        root.findViewById<EditText>(R.id.etSvTravelMode).apply {
            setText(travelMode.label)
            setOnClickListener {
                if (routing?.id == "direct_sv") return@setOnClickListener
                pickChoice("Client travel", listOf(Choice("cab", "Cab required"), Choice("own_vehicle", "Own vehicle"))) {
                    choice -> travelMode = choice; setText(choice.label)
                }
            }
        }
        root.findViewById<EditText>(R.id.etSvProject).setOnClickListener { pickProject(it as EditText) }
        root.findViewById<EditText>(R.id.etSvDateTime).setOnClickListener { pickSchedule(it as EditText) }
        root.findViewById<EditText>(R.id.etSvPickupTime).setOnClickListener { pickTime(it as EditText, isPickup = true) }
        bindStaffPicker(root, R.id.etSvLmo, "Select LMO", ::eligibleLmo) { lmo = it }
        bindStaffPicker(root, R.id.etSvBdo, "Select BDO", ::eligibleLmo) { bdo = it }
        bindStaffPicker(root, R.id.etSvIncharge, "Select site incharge", ::salesMarketing) { incharge = it }
        bindStaffPicker(root, R.id.etSvFieldStaff, "Select field staff", ::salesMarketing) { fieldStaff = it }
        bindStaffPicker(root, R.id.etSvHod, "Select HOD", { true }) { hod = it }
        bindStaffPicker(root, R.id.etSvAvp, "Select AVP", ::salesMarketing) { avp = it }
        bindStaffPicker(root, R.id.etSvGm, "Select GM", ::salesMarketing) { gm = it }
        bindStaffPicker(root, R.id.etSvSeniorManager, "Select senior manager", ::salesMarketing) { seniorManager = it }
    }

    private fun bindPhoneLookup(root: View) {
        root.findViewById<EditText>(R.id.etSvLead).setOnClickListener {
            if (lead == null) allowNewClientName(root) else Unit
        }
        root.findViewById<EditText>(R.id.etSvClientPhone).addTextChangedListener { value ->
            val phone = value?.toString().orEmpty().filter(Char::isDigit).takeLast(10)
            lead = null
            if (phone.length != 10) {
                lastPhoneLookup = ""
                root.findViewById<TextView>(R.id.tvSvClientHint).text = "Enter a phone number to find an existing lead."
                return@addTextChangedListener
            }
            phoneLookupJob?.cancel()
            phoneLookupJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(350)
                lookupLead(root, phone)
            }
        }
    }

    private suspend fun lookupLead(root: View, phone: String) {
        if (lastPhoneLookup == phone) return
        lastPhoneLookup = phone
        val name = root.findViewById<EditText>(R.id.etSvLead)
        val hint = root.findViewById<TextView>(R.id.tvSvClientHint)
        hint.text = "Looking up client..."
        try {
            val response = api.searchTelecallerLeadsByPhone(session.bearerToken, phone)
            if (!response.success) throw IllegalStateException(response.error ?: "Could not search clients")
            val matches = response.leads
            if (matches.isEmpty()) {
                lead = null
                name.setText("")
                allowNewClientName(root)
                hint.text = "New client: enter the full name. The backend will create the client and lead safely."
            } else {
                val chosen = matches.first()
                applyLead(root, chosen)
                if (matches.size > 1) {
                    name.setOnClickListener { pickLead(root, matches) }
                    hint.text = "${matches.size} leads found. Tap to choose."
                }
            }
        } catch (error: Throwable) {
            lastPhoneLookup = ""
            hint.text = friendlyError(error)
        }
    }

    private fun pickLead(root: View, leads: List<TelecallerLeadSearchData>) {
        SearchableSelectionDialog.show(requireContext(), "Select client", leads.map {
            SearchableOption(it, it.contactName ?: "Unknown client", it.mobileNumber, listOfNotNull(it.contactName, it.mobileNumber).joinToString(" "))
        }) { applyLead(root, it) }
    }

    private fun applyLead(root: View, selected: TelecallerLeadSearchData) {
        lead = selected
        root.findViewById<EditText>(R.id.etSvLead).apply {
            isFocusable = false
            setText(selected.contactName ?: selected.mobileNumber ?: "Selected lead")
        }
        root.findViewById<TextView>(R.id.tvSvClientHint).text = "Existing lead linked."
        if (project == null && !selected.projectId.isNullOrBlank()) {
            projectCache.firstOrNull { it.id == selected.projectId }?.let {
                project = it; setText(root, R.id.etSvProject, it.name ?: "Selected project")
            }
        }
        selected.assignedToStaffId?.let { owner ->
            staffCache.firstOrNull { it.id == owner && eligibleLmo(it) }?.let {
                lmo = it; setText(root, R.id.etSvLmo, it.name ?: "Selected LMO")
            }
        }
        val address = selected.suggestedVisitAddress ?: selected.locationPreferred
        if (root.findViewById<EditText>(R.id.etSvPickupAddress).text.isBlank() && !address.isNullOrBlank()) {
            root.findViewById<EditText>(R.id.etSvPickupAddress).setText(address)
        }
        selected.suggestedVisitLat?.let { lat -> selected.suggestedVisitLng?.let { lng ->
            pinLat = lat; pinLng = lng; pinMapsLink = selected.suggestedGoogleMapsLink
            renderPin(root)
        } }
        if (visitorFields.isEmpty()) {
            root.findViewById<EditText>(R.id.etSvVisitorCount).setText("1")
            visitorFields.firstOrNull()?.name?.setText(selected.contactName.orEmpty())
            visitorFields.firstOrNull()?.relation?.setText("Self")
        }
    }

    private fun allowNewClientName(root: View) {
        root.findViewById<EditText>(R.id.etSvLead).apply {
            isFocusableInTouchMode = true
            isFocusable = true
            hint = "Enter new client's full name"
            setOnClickListener { requestFocus() }
        }
    }

    private fun bindVisitors(root: View) {
        root.findViewById<EditText>(R.id.etSvVisitorCount).addTextChangedListener { raw ->
            val count = raw?.toString()?.toIntOrNull()?.coerceIn(0, 10) ?: 0
            val container = root.findViewById<LinearLayout>(R.id.svVisitorsContainer)
            while (visitorFields.size < count) addVisitor(container, visitorFields.size)
            while (visitorFields.size > count) {
                container.removeView(visitorFields.removeLast().root)
            }
        }
    }

    private fun addVisitor(container: LinearLayout, index: Int) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 4)
        }
        val title = TextView(requireContext()).apply { text = "Visitor ${index + 1}"; setTextColor(Color.parseColor("#344054")) }
        val name = input("Name")
        val relation = input("Relation (Self, Spouse, Parent...)")
        val age = input("Age").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val veg = CheckBox(requireContext()).apply { text = "Vegetarian"; isChecked = true }
        card.addView(title); card.addView(name); card.addView(relation); card.addView(age); card.addView(veg)
        container.addView(card)
        visitorFields += VisitorFields(card, name, relation, age, veg)
    }

    private fun input(hintText: String) = EditText(requireContext()).apply {
        hint = hintText
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(14.dp, 0, 14.dp, 0)
        background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_cp_creation_input_box)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp).apply { topMargin = 6.dp }
    }

    private fun applyRouting(root: View) {
        val sameArea = routing?.id == "same_area"
        val direct = routing?.id == "direct_sv"
        root.findViewById<View>(R.id.blockSvFieldStaff).visibility = if (sameArea) View.VISIBLE else View.GONE
        if (!sameArea) { fieldStaff = null; setText(root, R.id.etSvFieldStaff, "") }
        if (direct) {
            travelMode = Choice("own_vehicle", "Own vehicle")
            setText(root, R.id.etSvTravelMode, travelMode.label)
        }
        root.findViewById<View>(R.id.blockSvPickupTime).visibility = if (direct) View.GONE else View.VISIBLE
        if (direct) { pickupTime = ""; setText(root, R.id.etSvPickupTime, "") }
    }

    private fun openMap(root: View) {
        MapPinDropBottomSheet.newInstance(pinLat, pinLng).apply {
            setListener { result ->
                pinLat = result.lat; pinLng = result.lng; pinMapsLink = result.googleMapsLink
                root.findViewById<EditText>(R.id.etSvPickupAddress).setText(result.address)
                renderPin(root)
            }
        }.show(childFragmentManager, "sv_pickup_pin")
    }

    private fun renderPin(root: View) {
        val lat = pinLat ?: return
        val lng = pinLng ?: return
        root.findViewById<TextView>(R.id.tvSvPin).text = "Pinned: ${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, lng)}"
    }

    private fun pickSchedule(label: EditText) {
        val now = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val selected = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }
            scheduledDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selected.time)
            pickTime(label, isPickup = false)
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1_000
        }.show()
    }

    private fun pickTime(label: EditText, isPickup: Boolean) {
        val now = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, hour, minute ->
            val stored = "%02d:%02d".format(Locale.US, hour, minute)
            if (isPickup) { pickupTime = stored; label.setText(displayTime(stored)) }
            else { scheduledTime = stored; label.setText("$scheduledDate • ${displayTime(stored)}") }
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
    }

    private fun pickProject(label: EditText) {
        if (projectCache.isEmpty()) { toast("Projects are still loading"); return }
        SearchableSelectionDialog.show(requireContext(), "Select project", projectCache.ongoingThenCompleted().map {
            SearchableOption(it, it.name ?: "Unnamed project", listOfNotNull(it.location, it.status).joinToString(" • "), listOfNotNull(it.name, it.location).joinToString(" "))
        }) { project = it; label.setText(it.name ?: "Selected project") }
    }

    private fun bindStaffPicker(root: View, id: Int, title: String, filter: (StaffData) -> Boolean, save: (StaffData) -> Unit) {
        root.findViewById<EditText>(id).setOnClickListener { clicked ->
            if (staffCache.isEmpty()) { toast("Staff are still loading"); return@setOnClickListener }
            val options = staffCache.filter { it.id != null && filter(it) }
            SearchableSelectionDialog.show(requireContext(), title, options.map {
                SearchableOption(it, it.name ?: "Unnamed staff", listOfNotNull(it.employeeId, it.designation, it.department).joinToString(" • "), listOfNotNull(it.name, it.employeeId, it.phone).joinToString(" "))
            }, "No eligible active staff found") { selected -> save(selected); (clicked as EditText).setText(selected.name ?: "Selected") }
        }
    }

    private fun preloadOptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val staff = api.getStaff(session.bearerToken, "active")
                if (staff.success) staffCache = staff.staff.filter { it.status.isNullOrBlank() || it.status.equals("active", true) }
            } catch (_: Throwable) { }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projects = geoApi.getMarketingProjects(session.bearerToken)
                if (projects.success) projectCache = projects.projects
            } catch (_: Throwable) { }
        }
    }

    private fun submit(root: View) {
        if (submitJob?.isActive == true) return
        val phone = root.findViewById<EditText>(R.id.etSvClientPhone).text.toString().filter(Char::isDigit).takeLast(10)
        val clientName = root.findViewById<EditText>(R.id.etSvLead).text.toString().trim()
        val address = root.findViewById<EditText>(R.id.etSvPickupAddress).text.toString().trim()
        val error = validate(phone, clientName, address)
        if (error != null) { toast(error); return }
        val button = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSvSubmit)
        button.isEnabled = false; button.text = "Scheduling..."
        val attendees = visitorFields.map {
            SiteVisitAttendeeRequest(it.name.text.toString().trim().ifBlank { null }, it.relation.text.toString().trim().ifBlank { null }, it.age.text.toString().trim().ifBlank { null }, it.veg.isChecked)
        }
        val body = CreateSiteVisitRequest(
            requestId = requestId, routing = routing!!.id, origin = origin.id,
            leadId = lead?.id, clientName = if (lead == null) clientName else null, mobileNumber = phone,
            projectId = project!!.id, scheduledDate = scheduledDate, scheduledTime = scheduledTime,
            pickupTime = pickupTime.takeIf { routing?.id != "direct_sv" },
            travelMode = if (routing?.id == "direct_sv") "own_vehicle" else travelMode.id,
            telecallerId = lmo!!.id!!, bdoStaffId = bdo!!.id!!, inchargeStaffId = incharge!!.id!!,
            fieldStaffId = fieldStaff?.id, hodStaffId = hod!!.id!!, avpStaffId = avp!!.id!!,
            gmStaffId = gm!!.id!!, seniorManagerStaffId = seniorManager!!.id!!,
            pickupAddress = address, pickupLat = pinLat!!, pickupLng = pinLng!!, pickupGoogleMapsLink = pinMapsLink,
            expectedAttendeeCount = attendees.size.takeIf { it > 0 }, attendees = attendees.takeIf { it.isNotEmpty() },
            notes = root.findViewById<EditText>(R.id.etSvNotes).text.toString().trim().ifBlank { null },
        )
        submitJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = geoApi.createSiteVisit(session.bearerToken, body)
                if (!response.success) throw IllegalStateException(response.error ?: "Could not schedule site visit")
                toast(response.message ?: if (response.handoffId != null) "Sent for GM verification" else "Site visit scheduled")
                setFragmentResult(RESULT_CREATED, bundleOf("siteVisitId" to response.siteVisitId, "handoffId" to response.handoffId))
                requestId = UUID.randomUUID().toString()
                dismiss()
            } catch (error: Throwable) {
                toast(friendlyError(error))
                button.isEnabled = true; button.text = "Schedule"
            }
        }
    }

    private fun validate(phone: String, name: String, address: String): String? {
        if (routing == null) return "Select SV type"
        if (phone.length != 10) return "Enter the client's 10-digit phone number"
        if (lead == null && name.isBlank()) return "Enter the new client's full name"
        if (project == null) return "Select a project"
        if (scheduledDate.isBlank() || scheduledTime.isBlank()) return "Select the scheduled date and time"
        if (routing?.id != "direct_sv" && pickupTime.isBlank()) return "Select the pickup time"
        if (pickupTime.isNotBlank() && pickupTime > scheduledTime) return "Pickup time can't be after the scheduled site time"
        val scheduled = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$scheduledDate $scheduledTime") }.getOrNull()
        if (scheduled != null && scheduled.time < System.currentTimeMillis()) return "Scheduled date/time cannot be in the past"
        val pickup = pickupTime.takeIf { it.isNotBlank() }?.let {
            runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$scheduledDate $it") }.getOrNull()
        }
        if (pickup != null && pickup.time < System.currentTimeMillis()) return "Pickup date/time cannot be in the past"
        if (routing?.id == "immediate_pickup" && scheduledDate != SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())) return "Immediate SV must be scheduled for today"
        if (lmo == null) return "Select an LMO"
        if (bdo == null) return "Select a BDO"
        if (incharge == null) return "Select a site incharge"
        if (routing?.id == "same_area" && fieldStaff == null) return "Select the Same Area verification staff"
        if (hod == null || avp == null || gm == null || seniorManager == null) return "Complete all required reporting assignments"
        if (address.isBlank()) return "Enter the pickup address"
        val lat = pinLat
        val lng = pinLng
        if (lat == null || lng == null) return "Set the client pickup location on the map"
        if (!lat.isFinite() || !lng.isFinite() || lat == 0.0 || lng == 0.0 ||
            lat !in -90.0..90.0 || lng !in -180.0..180.0
        ) return "Select a valid pickup location on the map"
        return null
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is UnknownHostException -> "No network. Check your connection and try again."
        is SocketTimeoutException -> "The server took too long to respond. Please retry."
        is HttpException -> {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val payload = raw?.let { runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull() }
            val code = payload?.get("code")?.takeUnless { it.isJsonNull }?.asString
            val message = payload?.get("error")?.takeUnless { it.isJsonNull }?.asString
            val fieldMessage = payload?.getAsJsonObject("fieldErrors")
                ?.entrySet()
                ?.firstOrNull()
                ?.value
                ?.takeUnless { it.isJsonNull }
                ?.asString
            val correlationId = payload?.get("correlationId")?.takeUnless { it.isJsonNull }?.asString
            message?.substringAfter("Uncaught Error:")?.lineSequence()?.firstOrNull()?.trim()
                ?: fieldMessage
                ?: when (code) {
                    "DUPLICATE_VISIT" -> "This client already has a site visit scheduled for that day."
                    "STAFF_BUSY" -> "The selected site incharge is already assigned near that time."
                    "IDEMPOTENCY_CONFLICT" -> "The visit details changed during retry. Close this form and schedule again."
                    "VALIDATION_ERROR", "INVALID_REQUEST" -> "Some visit details are invalid. Check the form."
                    else -> when (error.code()) {
                        401 -> "Your session expired. Sign in again."
                        403 -> "You do not have permission to schedule site visits."
                        404 -> "A selected project, lead, or staff record is no longer available."
                        409 -> "This client or staff has a scheduling conflict."
                        422 -> "Some visit details are invalid. Check the form."
                        500 -> correlationId?.let { "Couldn't schedule site visit. Reference: $it" }
                            ?: "Couldn't schedule site visit. Please try again."
                        else -> "Couldn't schedule site visit (${error.code()})."
                    }
                }
        }
        else -> error.message?.substringAfter("Uncaught Error:")?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: "Couldn't schedule site visit. Please try again."
    }

    private fun salesMarketing(staff: StaffData): Boolean =
        staff.status.equals("active", true) && staff.department.orEmpty().lowercase(Locale.ROOT).let { it.contains("sales") && it.contains("marketing") }

    private fun eligibleLmo(staff: StaffData): Boolean {
        if (!staff.status.equals("active", true)) return false
        val dept = staff.department.orEmpty().lowercase(Locale.ROOT)
        val designation = staff.designation.orEmpty().lowercase(Locale.ROOT)
        return (dept.contains("telesales") && (designation == "lmo" || designation.contains("lead management") || designation.contains("telecaller"))) ||
            dept.contains("channel partner") ||
            (dept.contains("sales") && dept.contains("marketing") && (designation == "bdo" || designation.contains("business development")))
    }

    private fun pickChoice(title: String, values: List<Choice>, chosen: (Choice) -> Unit) {
        SearchableSelectionDialog.show(requireContext(), title, values.map { SearchableOption(it, it.label, it.detail, "${it.id} ${it.label}") }) { chosen(it) }
    }

    private fun setText(id: Int, text: String) = setText(view, id, text)
    private fun setText(root: View?, id: Int, text: String) { root?.findViewById<EditText>(id)?.setText(text) }
    private fun displayTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(value)!!
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(parsed)
    }.getOrDefault(value)
    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_REQUEST_ID = "site_visit_request_id"
        const val RESULT_CREATED = "site_visit_created"
        fun newInstance() = CreateSiteVisitBottomSheet()
    }
}
