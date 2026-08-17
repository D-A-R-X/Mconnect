package com.manjugroups.m_connect.ui.marketing

import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.manjugroups.m_connect.network.PostSaleCaseSummary
import com.manjugroups.m_connect.util.ongoingThenCompleted
import com.manjugroups.m_connect.network.TelecallerLeadSearchData
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.util.EditableTimeFormat
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
import com.manjugroups.m_connect.ui.common.showOnce

class CreateCpVisitBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    // Selected variables
    private var selectedStaff: StaffData? = null
    private var selectedProject: MarketingProject? = null
    // LMO / Channel Partner / BDO owner — required, mirrors the web CP form.
    private var selectedLmo: StaffData? = null
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
        // "Follow-up" retired as a creatable type (VP): postpone/cancel outcomes
        // already spawn a same-type follow-up. New Client CP is auto-created from
        // Aster, so it's not offered here either.
        CpTypeOption("booking_cp", "Booking CP", "Paperwork run for an active booking"),
        CpTypeOption("collection_cp", "Collection CP", "Payment chase at client place"),
        CpTypeOption("old_client", "Old Client", "Re-engagement touch"),
        CpTypeOption("gift_distribution", "Gift Distribution", "Loyalty drop-off"),
        CpTypeOption("other_cp", "Other CP", "Miscellaneous client work"),
    )

    // Caches for fast display
    private var staffCache: List<StaffData> = emptyList()
    private var projectCache: List<MarketingProject> = emptyList()

    // Collection CP gate — when the user picks "Collection CP" we
    // pre-fetch the client's confirmed bookings (postSaleCases) and
    // cache them here keyed by the phone digits that were on the form
    // at the time. The Yes path of the trip flow re-uses the same
    // shape from /api/postsales/cases/byMobile so the field staff
    // doesn't have to re-confirm which booking they're collecting
    // against. An empty list disables the type and surfaces the
    // "client has no bookings" popup.
    private var collectionCasesPhone: String? = null
    private var collectionCasesCache: List<PostSaleCaseSummary> = emptyList()

    // Lead lookup autofill — remembers which phone we already looked up
    // so a stray keystroke (e.g. user editing the 11th char then
    // deleting it back to 10) doesn't re-fire the network call. Mirrors
    // web's `createLastEnrichedPin` guard but keyed on the phone tail
    // rather than the pincode.
    private var lastAutofilledPhone: String? = null

    // Pin-drop state — populated when the user picks a spot from the
    // (forthcoming) map widget. The web equivalent stores these on
    // UnifiedAddressValue directly. Left null until the drop-pin
    // bottom sheet lands; submit sends them as optional fields.
    private var pinLat: Double? = null
    private var pinLng: Double? = null
    private var pinMapsLink: String = ""
    private val mapApi = com.manjugroups.m_connect.network.MapServiceApi.create()

    /** Show the current pin (from a web lead or a fresh drop) in the form,
     *  reverse-geocoding the coordinates into a readable address. */
    private fun renderPinLocation(view: View) {
        val lat = pinLat ?: return
        val lng = pinLng ?: return
        val tv = view.findViewById<android.widget.TextView>(R.id.tvPinLocation) ?: return
        tv.visibility = View.VISIBLE
        tv.text = "Pinned: ${String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)}"
        viewLifecycleOwner.lifecycleScope.launch {
            val addr = runCatching {
                mapApi.reverseGeocode(lat, lng).results.firstOrNull()?.address
            }.getOrNull()
            if (!isAdded) return@launch
            if (!addr.isNullOrBlank()) tv.text = "Pinned: $addr"
        }
    }

    // Address-line-1 OpenAI parse guard — same idea as the web's
    // lastParsedRef. Stops us re-firing for every keystroke once a
    // long string has been parsed.
    private var lastParsedAddressLine1: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused input above the soft keyboard;
        // without it the keyboard covers the lower fields and the submit
        // button with no way to scroll them back into view.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                // Full-height sheet so the Cancel / Create-visit footer can pin
                // to the bottom while the form scrolls behind it (instead of the
                // buttons scrolling away with the content).
                it.layoutParams = it.layoutParams.apply {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
                val behavior = BottomSheetBehavior.from(it)
                behavior.peekHeight = resources.displayMetrics.heightPixels
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
        val etLmo = view.findViewById<EditText>(R.id.etLmo)
        val etCpType = view.findViewById<EditText>(R.id.etCpType)
        val etDateTime = view.findViewById<EditText>(R.id.etDateTime)

        // ── Canonical address fields (7) ───────────────────────────
        // Match the web UnifiedAddressFields component 1:1 so reads /
        // writes share the same shape: doorNo · street · addressLine1 ·
        // addressLine2 · city · state · pincode. Pin-drop will fill
        // lat/lng/googleMapsLink in a follow-up turn.
        val etClientName = view.findViewById<EditText>(R.id.etClientName)
        val etDoorNo = view.findViewById<EditText>(R.id.etDoorNo)
        val etStreet = view.findViewById<EditText>(R.id.etStreet)
        val etAddressLine1 = view.findViewById<EditText>(R.id.etAddressLine1)
        val etAddressLine2 = view.findViewById<EditText>(R.id.etAddressLine2)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etState = view.findViewById<EditText>(R.id.etState)
        val etPincode = view.findViewById<EditText>(R.id.etPincode)
        val tvAddressParseStatus = view.findViewById<android.widget.TextView>(R.id.tvAddressParseStatus)
        val btnDropPin = view.findViewById<View>(R.id.btnDropPin)
        val tvPinLocation = view.findViewById<android.widget.TextView>(R.id.tvPinLocation)

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
        etLmo.setOnClickListener { pickLmo(etLmo) }
        etCpType.setOnClickListener { pickCpType(etCpType) }
        etDateTime.setOnClickListener { pickDateTime(etDateTime) }

        // Lead-autofill — when the phone field hits 10 digits we hit
        // /api/telecaller/leads/search-by-phone and prefill the
        // structured address + project from the matched lead. Blank-
        // only patch so we never overwrite anything the user already
        // typed; user-entered values always win.
        etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString().orEmpty().filter { it.isDigit() }.takeLast(10)
                if (digits.length == 10 && digits != lastAutofilledPhone) {
                    lastAutofilledPhone = digits
                    autofillFromLead(
                        phone = digits,
                        view = view,
                    )
                }
            }
        })

        // Address-Line-1 paste-to-parse — when the user pastes a full
        // address (>25 chars) into Address Line 1, debounce 700ms then
        // POST it to /api/address/parse (OpenAI gpt-4o-mini, JSON mode)
        // and distribute the structured fields back into door/street/
        // addressLine2/city/state/pincode. Blank-only fill so user
        // typing always wins. Mirrors the web UnifiedAddressFields
        // behaviour 1:1 — same endpoint, same debounce, same guard.
        var pendingParse: kotlinx.coroutines.Job? = null
        etAddressLine1.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val line1 = s?.toString()?.trim().orEmpty()
                if (line1.length < 25 || line1 == lastParsedAddressLine1) return
                pendingParse?.cancel()
                pendingParse = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(700)
                    lastParsedAddressLine1 = line1
                    tvAddressParseStatus.text = "Splitting address…"
                    tvAddressParseStatus.visibility = View.VISIBLE
                    try {
                        val resp = geoApi.parseAddress(
                            session.bearerToken,
                            com.manjugroups.m_connect.network.AddressParseRequest(raw = line1),
                        )
                        if (!resp.success || resp.fields == null) {
                            tvAddressParseStatus.text = resp.error ?: "Could not split address"
                            return@launch
                        }
                        val f = resp.fields
                        fun fillIfBlank(et: EditText, value: String?) {
                            if (value.isNullOrBlank()) return
                            if (et.text?.toString()?.isBlank() != false) et.setText(value)
                        }
                        fillIfBlank(etDoorNo, f.doorNo)
                        fillIfBlank(etStreet, f.street)
                        // Replace addressLine1 only if the parser came
                        // back with a different (usually shorter)
                        // address segment — keeps the user's verbatim
                        // text if the parser failed to split.
                        if (!f.addressLine1.isNullOrBlank() && f.addressLine1 != line1) {
                            etAddressLine1.setText(f.addressLine1)
                        }
                        fillIfBlank(etAddressLine2, f.addressLine2)
                        fillIfBlank(etCity, f.city)
                        fillIfBlank(etState, f.state)
                        fillIfBlank(etPincode, f.pincode)
                        tvAddressParseStatus.text = "Address auto-filled"
                    } catch (_: Exception) {
                        // The splitter is opportunistic (OpenAI-backed) — if it's
                        // unavailable, never dump a raw "HTTP 500" at the user;
                        // they can just fill the fields below by hand.
                        tvAddressParseStatus.text =
                            "Couldn't auto-split — fill the address fields below manually."
                    }
                }
            }
        })

        // Pincode enrichment — parity with the Booking form and the web
        // UnifiedAddressFields. On a 6-digit pincode, look up India Post and
        // blank-fill District (from the API District), State, and the locality
        // (into Address Line 2). Same anti-overwrite policy: only fill fields
        // the operator left blank so typed values always win.
        var lastEnrichedPincode: String? = null
        etPincode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pin = s?.toString().orEmpty().filter { it.isDigit() }
                if (pin.length != 6 || pin == lastEnrichedPincode) return
                lastEnrichedPincode = pin
                viewLifecycleOwner.lifecycleScope.launch {
                    val enriched = try {
                        com.manjugroups.m_connect.network.PincodeLookup.lookup(pin)
                    } catch (_: Throwable) {
                        null
                    } ?: return@launch
                    fun fillIfBlank(et: EditText, value: String?) {
                        if (value.isNullOrBlank()) return
                        if (et.text?.toString()?.isBlank() != false) et.setText(value)
                    }
                    fillIfBlank(etCity, enriched.district)
                    fillIfBlank(etState, enriched.state)
                    fillIfBlank(etAddressLine2, enriched.locality)
                }
            }
        })

        btnDropPin.setOnClickListener {
            // Map pin-drop with address search + suggestions (map service).
            // Seed at the current pin if one was already set.
            val sheet = com.manjugroups.m_connect.ui.common.MapPinDropBottomSheet
                .newInstance(pinLat, pinLng)
            sheet.setListener { result ->
                pinLat = result.lat
                pinLng = result.lng
                pinMapsLink = result.googleMapsLink
                tvPinLocation.text = result.address.ifBlank { "Location set" }
                tvPinLocation.visibility = View.VISIBLE
                // An explicit pin drop is a DELIBERATE location change — REPLACE
                // the address, don't just fill blanks. Clear the derived fields
                // and re-seed Address Line 1 so the paste-to-parse + pincode
                // enrichment repopulate everything from the NEW location.
                // (Previously a second pin left the first location's address /
                // pincode / district stale because every fill was blank-only.)
                if (result.address.isNotBlank()) {
                    etDoorNo.setText("")
                    etStreet.setText("")
                    etAddressLine2.setText("")
                    etCity.setText("")
                    etState.setText("")
                    etPincode.setText("")
                    // Let the pincode enrichment re-run even if the new pincode
                    // happens to repeat a previously enriched one.
                    lastEnrichedPincode = null
                    etAddressLine1.setText(result.address)
                }
            }
            sheet.showOnce(parentFragmentManager, "map_pin_drop")
        }

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        btnSubmit.setOnClickListener {
            // Validation
            val phone = etPhone.text.toString().filter { it.isDigit() }.takeLast(10)
            if (phone.length != 10) {
                toast("Enter a valid 10-digit phone number")
                return@setOnClickListener
            }

            // Client name is required. For an existing client/lead it auto-fills
            // from the phone lookup; for a new number the staff must type it, so
            // the CP always stores a named client (never a phone-named one).
            if (etClientName.text.toString().trim().isBlank()) {
                toast("Client name is required")
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

            val lmo = selectedLmo
            if (lmo == null || lmo.id.isNullOrBlank()) {
                toast("Select the LMO, Channel Partner, or BDO")
                return@setOnClickListener
            }

            if (selectedDate.isBlank() || selectedTime.isBlank()) {
                toast("Select Date & Time")
                return@setOnClickListener
            }

            val doorNo = etDoorNo.text.toString().trim()
            val street = etStreet.text.toString().trim()
            val addressLine1 = etAddressLine1.text.toString().trim()
            val addressLine2 = etAddressLine2.text.toString().trim()
            val city = etCity.text.toString().trim()
            val state = etState.text.toString().trim()
            val pincode = etPincode.text.toString().trim()

            if (addressLine1.isBlank()) {
                toast("Address Line 1 is required")
                return@setOnClickListener
            }
            if (city.isBlank()) {
                toast("District is required")
                return@setOnClickListener
            }
            if (pincode.isBlank() || pincode.length != 6) {
                toast("Pincode must be 6 digits")
                return@setOnClickListener
            }

            // Compile composite address — same key:value comma-joined
            // shape the web UnifiedAddressFields submit uses, so detail
            // views that parse visitAddress stay happy. Village/Taluk/
            // District/Locality/Full Address segments are dropped per
            // the unified 7-field set.
            val addressParts = listOfNotNull(
                doorNo.takeIf { it.isNotBlank() }?.let { "Door/Plot No: $it" },
                street.takeIf { it.isNotBlank() }?.let { "Street: $it" },
                addressLine1.takeIf { it.isNotBlank() }?.let { "Address: $it" },
                addressLine2.takeIf { it.isNotBlank() }?.let { "Landmark: $it" },
                city.takeIf { it.isNotBlank() }?.let { "City: $it" },
                state.takeIf { it.isNotBlank() }?.let { "State: $it" },
                pincode.takeIf { it.isNotBlank() }?.let { "Pincode: $it" }
            )
            val compiledAddress = addressParts.joinToString(", ")

            // Coordinates + maps link from the pin-drop UX. When no pin was
            // dropped these stay null here and are resolved by geocoding the
            // typed address at submit (below) so the CP always ships with
            // coordinates for the trip map pin.
            val latVal: Double? = pinLat
            val lngVal: Double? = pinLng
            val maps: String = pinMapsLink
            val notesVal = etNotes.text.toString().trim()

            // Defensive re-check — the user might have changed the
            // phone number after picking a booking-dependent CP type.
            // Never let collection_cp / booking_cp ship with a mobile
            // that has no cached booking-found result.
            val isBookingDependent = selectedCpType?.id == "collection_cp" ||
                selectedCpType?.id == "booking_cp"
            if (isBookingDependent &&
                (collectionCasesPhone != phone || collectionCasesCache.isEmpty())
            ) {
                AlertDialog.Builder(requireContext())
                    .setTitle("The client has no bookings")
                    .setMessage(
                        "${selectedCpType?.label ?: "This CP type"} needs a confirmed booking for this mobile. " +
                            "Re-pick the CP type or use a number that already has a booking."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // A CP MUST carry coordinates so the trip map can pin the
                    // client location (without them the trip screen tries to
                    // geocode the free-text address, and a vague/junk address
                    // lands the pin on null-island / the ocean). When the user
                    // didn't drop a pin, geocode the typed address here; if it
                    // can't be located, require a pin rather than saving a
                    // coordinate-less CP.
                    var resolvedLat = latVal
                    var resolvedLng = lngVal
                    if (resolvedLat == null || resolvedLng == null) {
                        val plainAddress = listOf(
                            doorNo, street, addressLine1, addressLine2,
                            city, state, pincode,
                        ).filter { it.isNotBlank() }.joinToString(", ")
                        val geo = com.manjugroups.m_connect.network.DirectionsClient
                            .geocodeAddress(session.bearerToken, plainAddress)
                        if (!isAdded) return@launch
                        if (geo != null) {
                            resolvedLat = geo.latLng.latitude
                            resolvedLng = geo.latLng.longitude
                        } else {
                            btnSubmit.isEnabled = true
                            toast("Couldn't locate this address on the map. Drop a pin to set the exact client location.")
                            btnDropPin.performClick()
                            return@launch
                        }
                    }
                    val resp = geoApi.createCpVisit(
                        session.bearerToken,
                        CreateCpVisitRequest(
                            clientName = etClientName.text.toString().trim().takeIf { it.isNotBlank() },
                            mobileNumber = phone,
                            assignedStaffId = staff.id,
                            lmoStaffId = lmo.id,
                            scheduledDate = selectedDate,
                            scheduledTime = selectedTime,
                            visitAddress = compiledAddress,
                            visitLat = resolvedLat,
                            visitLng = resolvedLng,
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
                    // The create route returns real reasons (staff busy, duplicate
                    // slot, "Collection CP requires a booking", etc.) as a 500 with
                    // a JSON { error } body. Retrofit throws on the 500, so read the
                    // body — otherwise the user only ever sees a bare "HTTP 500".
                    toast(serverErrorMessage(e) ?: e.message ?: "Failed to create CP visit")
                }
            }
        }
    }

    /** Mirrors the web `leadToLocationFields` + project autolink. Fires
     *  on first 10-digit phone entry, only patches blank fields. */
    private fun autofillFromLead(phone: String, view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 1) Telecaller-lead autofill (name + address), blank-only.
            try {
                val resp = api.searchTelecallerLeadsByPhone(session.bearerToken, phone)
                if (resp.success && resp.leads.isNotEmpty()) {
                    applyLeadAutofill(view, resp.leads.first())
                }
            } catch (_: Exception) {
                // Silent — autofill is opportunistic, never block submit.
            }
            if (!isAdded) return@launch
            // 2) Clients-master fallback — many numbers exist as a client (an
            // existing buyer) with NO telecaller lead, or with a lead whose
            // contactName is blank. Fill any still-blank name / address from the
            // clients row keyed by this mobile so the real client name shows.
            try {
                val resp = api.searchClientByPhone(session.bearerToken, phone)
                val client = resp.client
                if (isAdded && resp.success && client != null) {
                    applyClientAutofill(view, client)
                }
            } catch (_: Exception) {
                // Silent — fallback is opportunistic too.
            }
        }
    }

    /** Blank-only fill from the clients master (searchClientByPhone). Runs after
     *  the lead autofill so it only closes gaps — never overwrites typed text or
     *  a value the lead already supplied. */
    private fun applyClientAutofill(
        view: View,
        c: com.manjugroups.m_connect.network.ClientProfile,
    ) {
        fun fillIfBlank(id: Int, value: String?) {
            if (value.isNullOrBlank()) return
            val et = view.findViewById<EditText>(id) ?: return
            if (et.text?.toString()?.isBlank() != false) et.setText(value.trim())
        }
        fillIfBlank(R.id.etClientName, c.clientName)
        fillIfBlank(R.id.etDoorNo, c.doorNo)
        fillIfBlank(R.id.etStreet, c.streetName)
        fillIfBlank(R.id.etAddressLine1, c.addressLine1 ?: c.homeAddress ?: c.formattedAddress)
        fillIfBlank(R.id.etAddressLine2, c.addressLine2 ?: c.landmark)
        fillIfBlank(R.id.etCity, c.district)
        fillIfBlank(R.id.etState, c.state)
        fillIfBlank(R.id.etPincode, c.pincode)
        if (pinLat == null && c.lat != null) pinLat = c.lat
        if (pinLng == null && c.lng != null) pinLng = c.lng
        if (pinMapsLink.isBlank() && !c.googleMapsLink.isNullOrBlank()) {
            pinMapsLink = c.googleMapsLink!!
        }
        renderPinLocation(view)
    }

    private fun applyLeadAutofill(view: View, lead: TelecallerLeadSearchData) {
        val cp = lead.clientPlaceProfile
        val ai = lead.latestAnalysisProfile
        val mp = lead.manualProfile

        fun pickFirst(vararg candidates: String?): String =
            candidates.firstOrNull { !it.isNullOrBlank() && it != "Unknown" }?.trim().orEmpty()

        // Six-digit pincode extracted from the comma-joined fallback
        // text so a lead with only "Salem 636001 - Yercaud Road" in
        // locationPreferred still surfaces the pincode in the box.
        val fallback = (lead.suggestedVisitAddress ?: lead.locationPreferred).orEmpty()
        val sixDigit = Regex("""\b(\d{6})\b""").find(fallback)?.groupValues?.get(1).orEmpty()

        val pincode = pickFirst(cp?.pincode, ai?.pincode, mp?.pincode, sixDigit)
        val doorNo = pickFirst(cp?.doorNo, ai?.doorNo, mp?.doorNo)
        val city = pickFirst(cp?.city, lead.clientCity)
        val state = pickFirst(cp?.state, ai?.state, mp?.state)
        val addressLine2 = pickFirst(cp?.landmark, ai?.landmark, mp?.landmark)
        // Address Line 1 takes the best prose address available.
        val addressLine1 = pickFirst(
            ai?.address,
            cp?.address,
            cp?.formattedAddress,
            fallback,
        )

        // Blank-only patcher — never overwrite user-typed text.
        fun fillIfBlank(id: Int, value: String) {
            if (value.isBlank()) return
            val et = view.findViewById<EditText>(id) ?: return
            if (et.text?.toString()?.isBlank() != false) et.setText(value)
        }

        // Client name from the lead (blank-only, like the address fields).
        val clientName = pickFirst(lead.contactName, ai?.clientName, mp?.clientName)
        fillIfBlank(R.id.etClientName, clientName)
        fillIfBlank(R.id.etDoorNo, doorNo)
        fillIfBlank(R.id.etAddressLine1, addressLine1)
        fillIfBlank(R.id.etAddressLine2, addressLine2)
        fillIfBlank(R.id.etCity, city)
        fillIfBlank(R.id.etState, state)
        fillIfBlank(R.id.etPincode, pincode)

        // Visit lat/lng + Google Maps link — stash in the pin-drop
        // state so submit can ship them and the (forthcoming) pin
        // widget can render the existing location on open.
        if (pinLat == null && lead.suggestedVisitLat != null) pinLat = lead.suggestedVisitLat
        if (pinLng == null && lead.suggestedVisitLng != null) pinLng = lead.suggestedVisitLng
        if (pinMapsLink.isBlank() && !lead.suggestedGoogleMapsLink.isNullOrBlank()) {
            pinMapsLink = lead.suggestedGoogleMapsLink!!
        }
        // Surface the web-dropped pin so the user sees the existing location
        // (and can re-drop it from the map if it needs adjusting).
        renderPinLocation(view)

        // Project — auto-pick if the lead carries one and the user
        // hasn't picked yet. Falls back gracefully if the project
        // cache isn't loaded yet (next pickProject() call still works).
        if (selectedProject == null && !lead.projectId.isNullOrBlank()) {
            val cached = projectCache.firstOrNull { it.id == lead.projectId }
            if (cached != null) {
                selectedProject = cached
                view.findViewById<EditText>(R.id.etProject)?.setText(cached.name ?: "Selected")
            } else {
                // Lazy-load and try again — non-blocking, swallow errors.
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val r = api.getMarketingProjects(session.bearerToken)
                        if (r.success) {
                            projectCache = r.projects
                            r.projects.firstOrNull { it.id == lead.projectId }?.let { p ->
                                if (selectedProject == null) {
                                    selectedProject = p
                                    view.findViewById<EditText>(R.id.etProject)
                                        ?.setText(p.name ?: "Selected")
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Surface a small confirmation so the user knows where the
        // values came from. Single-line toast, not a dialog — they
        // can keep typing immediately.
        val who = lead.contactName?.takeIf { it.isNotBlank() } ?: "lead"
        Toast.makeText(
            requireContext(),
            "Auto-linked to $who",
            Toast.LENGTH_SHORT,
        ).show()
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

    /** LMO / Channel Partner / BDO owner picker — reuses the staff list but
     *  filters to the same eligible roles the web CP form allows. */
    private fun pickLmo(label: EditText) {
        if (staffCache.isNotEmpty()) {
            showLmoPicker(label, staffCache)
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
                showLmoPicker(label, resp.staff)
            } catch (e: Exception) {
                toast("Network error: ${e.message}")
            }
        }
    }

    private fun showLmoPicker(label: EditText, items: List<StaffData>) {
        val eligible = items.filter { isEligibleLmo(it) }
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select LMO / Channel Partner / BDO",
            options = eligible.map { s ->
                SearchableOption(
                    item = s,
                    title = s.name ?: "Unnamed Staff",
                    subtitle = listOfNotNull(s.employeeId, s.designation, s.department)
                        .joinToString(" • "),
                    keywords = listOfNotNull(s.id, s.name, s.employeeId, s.designation, s.department)
                        .joinToString(" "),
                )
            },
            emptyMessage = "No eligible active staff found",
        ) { staff ->
            selectedLmo = staff
            label.setText(staff.name ?: "Selected")
        }
    }

    /** Mirrors convex/marketing/lib/cpVisitLmo.ts: an active Telesales LMO,
     *  Channel Partner, or Sales & Marketing BDO. */
    private fun isEligibleLmo(s: StaffData): Boolean {
        if (!"active".equals(s.status, ignoreCase = true)) return false
        val dept = s.department?.lowercase(java.util.Locale.ROOT).orEmpty()
        val desig = s.designation?.lowercase(java.util.Locale.ROOT).orEmpty()
        val telesalesLmo = dept.contains("telesales") &&
            (desig == "lmo" || desig.contains("lead management executive") ||
                desig.contains("telecaller"))
        val channelPartner = dept.contains("channel partner")
        val salesMarketingBdo = dept.contains("sales") && dept.contains("marketing") &&
            (desig == "bdo" || desig.contains("business development officer") ||
                desig.contains("business development executive"))
        return telesalesLmo || channelPartner || salesMarketingBdo
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
            // Every ongoing project first, completed projects at the bottom.
            options = items.ongoingThenCompleted().map { p ->
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
            // Booking-dependent gate — Collection CP and Booking CP
            // both need a row in postSaleCases for the entered mobile.
            // Collection CP would open an empty Payment Entry sheet
            // mid-trip; Booking CP is by definition a "paperwork run
            // for an active booking", so without one it's invalid.
            // Block the pick at this point so the user sees the popup
            // immediately and can keep going with a different type.
            if (picked.id == "collection_cp" || picked.id == "booking_cp") {
                gateBookingDependentCpSelection(picked, label)
                return@show
            }
            selectedCpType = picked
            label.setText(picked.label)
        }
    }

    /** Verifies the entered mobile number has at least one confirmed
     *  booking (postSaleCase) before committing a booking-dependent
     *  type ("collection_cp" / "booking_cp"). Empty result → popup
     *  "The client has no bookings" + leave the cpType cleared so the
     *  dropdown effectively disables the pick. */
    private fun gateBookingDependentCpSelection(
        picked: CpTypeOption,
        label: EditText,
    ) {
        val typeLabel = picked.label
        val phone = view?.findViewById<EditText>(R.id.etClientPhone)
            ?.text?.toString().orEmpty()
            .filter { it.isDigit() }.takeLast(10)
        if (phone.length != 10) {
            AlertDialog.Builder(requireContext())
                .setTitle("Enter client mobile first")
                .setMessage(
                    "$typeLabel needs the client's 10-digit mobile to check for a confirmed booking. " +
                        "Add the mobile number, then pick $typeLabel again."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getPostSaleCasesByMobile(session.bearerToken, phone)
                if (!resp.success) {
                    toast(resp.error ?: "Failed to look up bookings")
                    return@launch
                }
                if (resp.cases.isEmpty()) {
                    collectionCasesPhone = phone
                    collectionCasesCache = emptyList()
                    AlertDialog.Builder(requireContext())
                        .setTitle("The client has no bookings")
                        .setMessage(
                            "$typeLabel is only allowed for clients with a confirmed booking. " +
                                "This mobile number is not on any active booking, so the type is blocked."
                        )
                        .setPositiveButton("Got it", null)
                        .show()
                    return@launch
                }
                collectionCasesPhone = phone
                collectionCasesCache = resp.cases
                selectedCpType = picked
                label.setText(picked.label)
            } catch (e: Exception) {
                toast("Network error: ${e.message}")
            }
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
                    val (storageTime, displayTime) = EditableTimeFormat.fromPicker(h, m)
                    selectedTime = storageTime
                    label.setText("$selectedDate $displayTime")
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
        ).showOnce(parentFragmentManager, "cp_visit_create_calendar")
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Pull the backend's real error out of an HTTP failure body. The CP-create
     *  route returns business errors (staff busy, duplicate slot, "Collection CP
     *  requires a booking", invalid staff, etc.) as a 500 with a JSON { error }
     *  body; Retrofit throws on the non-2xx, so without this the toast only shows
     *  a bare "HTTP 500". */
    private fun serverErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        const val RESULT_KEY_CREATED = "cp_visit_created_result"
        private const val RESULT_KEY_DATE = "cp_visit_create_date_calendar"

        fun newInstance(): CreateCpVisitBottomSheet = CreateCpVisitBottomSheet()
    }
}
