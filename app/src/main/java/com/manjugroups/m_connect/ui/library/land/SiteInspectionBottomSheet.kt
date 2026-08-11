package com.manjugroups.m_connect.ui.library.land

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.InspectionAreaEntry
import com.manjugroups.m_connect.network.InspectionCompetitor
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.network.InspectionReportData
import com.manjugroups.m_connect.network.InspectionSaveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Site Inspection bottom sheet — opens when the user taps a card in the
 * Inspection list.
 *
 * Three responsibilities right now:
 *  1. Lay out the 5-tab header (Basic / Area / Market / Conclusions /
 *     Competitors). Only Basic's content is rendered; the others toast
 *     "Coming soon" because their forms aren't designed yet.
 *  2. Render the long form for the Basic tab: 7 "Basic Details" fields
 *     and 6 "Accessibility & Infrastructure" fields, plus the 4-checkbox
 *     Road Type group. Each field is bound by `bindField(...)` which
 *     reaches into the `<include>` block to set label, hint, icon, and
 *     optional trailing "K/m" / "Feet" suffix.
 *  3. Carry the LP id of the tapped row in the arguments so a future
 *     "Submit" path can attach the inspection to the right record.
 *
 * Starts expanded so the form is visible right away (instead of the
 * default half-collapsed peek that would hide most of the fields).
 */
class SiteInspectionBottomSheet : BottomSheetDialogFragment() {

    private val propertyId: String?
        get() = arguments?.getString(ARG_PROPERTY_ID)
    private val displayTitle: String?
        get() = arguments?.getString(ARG_DISPLAY_TITLE)

    private lateinit var session: SessionManager
    private val api by lazy { ApiService.create() }
    private var isSaving: Boolean = false
    /**
     * Once VP has approved this inspection, the sheet flips to view-only
     * and the onPause auto-save is suppressed so a tab-switch while
     * scrolling can never overwrite an approved record with the same
     * field values (no-op in effect, but safer to skip entirely).
     */
    private var isViewOnly: Boolean = false
    /** Held so onPause / tab-switch auto-save can read the current fields. */
    private var rootView: View? = null

    // Road-type multi-select state.
    private val selectedRoadTypes = mutableSetOf<String>()

    private enum class Tab { BASIC, AREA, MARKET, CONCLUSIONS, COMPETITORS }
    private var activeTab: Tab = Tab.BASIC

    // Market-tab single-select demand chips + multi-select target clients.
    private var presentDemand: String? = null
    private var futureDemand: String? = null
    private val selectedTargets = mutableSetOf<String>()

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Resize the sheet when the keyboard appears so the focused field
        // scrolls into view above it (and the pinned "Next" button stays
        // visible) instead of the keyboard just overlaying the form.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            // Start expanded so the user sees the full form; the
            // half-peek state would hide everything past the title row.
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            sheet.setBackgroundColor(Color.TRANSPARENT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_site_inspection, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view
        session = SessionManager(requireContext())
        bindBasicDetailsFields(view)
        bindAccessibilityFields(view)
        bindRoadTypeRows(view)
        bindAreaTab(view)
        bindMarketTab(view)
        bindCompetitorsTab(view)
        // Land location: view / adjust the property's pin on the full-screen map.
        // The Google Map Link field holds "lat,lng"; a dropped pin writes it back.
        view.findViewById<View>(R.id.btnLandViewMap)?.setOnClickListener {
            openLocationMap(fieldText(view, R.id.fieldMapLink)) { lat, lng ->
                setFieldText(view, R.id.fieldMapLink, "$lat,$lng")
            }
        }
        bindTabs(view)

        val nextBtn = view.findViewById<TextView>(R.id.btnInspectionNext)
        nextBtn.setOnClickListener {
            if (activeTab != Tab.COMPETITORS) {
                // Gate each tab on its own required fields before advancing.
                val err = when (activeTab) {
                    Tab.BASIC -> validateBasicTab(view)
                    Tab.CONCLUSIONS -> validateConclusion(view)
                    else -> null
                }
                if (err != null) {
                    toastValidation(err)
                    return@setOnClickListener
                }
                advanceToNextTab(view)
            } else {
                // Final gate before saving — the user can jump tabs, so re-check
                // Basic + Conclusion so only a complete inspection syncs to web.
                val basicErr = validateBasicTab(view)
                if (basicErr != null) {
                    view.findViewById<View>(R.id.tabBasic).performClick()
                    toastValidation(basicErr)
                    return@setOnClickListener
                }
                val conclusionErr = validateConclusion(view)
                if (conclusionErr != null) {
                    view.findViewById<View>(R.id.tabConclusions).performClick()
                    toastValidation(conclusionErr)
                    return@setOnClickListener
                }
                submitInspection(view)
            }
        }

        // Prefill from any existing per-staff report so the user picks up
        // where they (or a web editor) left off instead of seeing a fresh
        // form every time.
        loadExistingReport(view)
    }

    /**
     * Walk one step forward through the tab order. The Submit happens on
     * the last tab (Competitors); every other tap on the CTA is a Next.
     */
    private fun advanceToNextTab(root: View) {
        val next = when (activeTab) {
            Tab.BASIC -> R.id.tabArea
            Tab.AREA -> R.id.tabMarket
            Tab.MARKET -> R.id.tabConclusions
            Tab.CONCLUSIONS -> R.id.tabCompetitors
            Tab.COMPETITORS -> return
        }
        root.findViewById<View>(next).performClick()
    }

    private fun loadExistingReport(root: View) {
        val pid = propertyId ?: return
        if (!session.isLoggedIn) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getInspectionForProperty(session.bearerToken, pid)
                if (resp.success) {
                    resp.report?.let { applyPrefill(root, it) }
                    resp.competitors?.let { prefillCompetitors(root, it) }
                    // Once VP has approved the inspection, the inspector
                    // can review what was submitted but can no longer edit
                    // any field — switch the whole sheet to view-only.
                    if (resp.property?.vpInspectionStatus == "approved") {
                        applyViewOnlyMode(root)
                    }
                }
            } catch (_: Exception) {
                // Silent — first-time inspections legitimately have no report
                // yet, and network blips shouldn't block the user from
                // entering one. The submit path will surface a real error.
            }
        }
    }

    /**
     * Lock the inspection form to view-only. Called after the VP has
     * approved the inspection — the inspector can scroll through every
     * tab to verify what was submitted, but every input is disabled
     * (EditText, dropdowns, checkbox-style chips, add-competitor button,
     * etc.) and the bottom CTA is hidden. A small banner above the tab
     * strip explains the state so it never reads as a "form bug".
     */
    private fun applyViewOnlyMode(root: View) {
        // Surface the explainer banner so the user understands why the
        // fields look greyed.
        root.findViewById<TextView>(R.id.tvInspectionApprovedBanner)?.visibility = View.VISIBLE
        // Hide the pinned Next / Submit CTA — no edits will be accepted
        // from this state.
        root.findViewById<View>(R.id.btnInspectionNext)?.visibility = View.GONE
        // Recursively disable every input. Tab strip itself stays
        // clickable so the user can still switch between Basic / Area /
        // Market / Conclusions / Competitors to review.
        disableAllInputs(root)
        // Flip the view-only flag so auto-save / submit paths short-circuit.
        isViewOnly = true
    }

    private fun disableAllInputs(view: View) {
        // The 5-tab strip carries clickable=true on each tab; keep those
        // alive so the user can navigate while reviewing. We identify
        // tab buttons by their IDs and short-circuit on them.
        val tabIds = setOf(
            R.id.tabBasic, R.id.tabArea, R.id.tabMarket,
            R.id.tabConclusions, R.id.tabCompetitors,
        )
        if (view.id in tabIds) return

        when (view) {
            is EditText -> {
                view.isEnabled = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.isCursorVisible = false
            }
            is android.widget.CheckBox,
            is android.widget.RadioButton -> {
                view.isEnabled = false
                view.isClickable = false
            }
        }
        // Any clickable TextView (dropdowns, chip-style toggles, the
        // "Add Competitor" button, road-type squares, etc.) loses its
        // tap response. We don't touch focusable/cursorVisible on these
        // because they're not text-entry widgets.
        if (view is TextView && view !is EditText && view.isClickable) {
            view.isClickable = false
            view.isFocusable = false
        }
        // Generic clickable containers — competitor card rows, the
        // landmark map-link row, etc.
        if (view !is ViewGroup && view.isClickable && view !is TextView) {
            view.isClickable = false
        }
        if (view is ViewGroup) {
            // Block clicks on clickable layouts too (the "Add Area" tile,
            // map-link tile, etc.). We deliberately don't disable the
            // whole ViewGroup since that would also block child taps —
            // but we already short-circuited on tab IDs above, so the
            // remaining clickable groups should not respond.
            if (view.isClickable) {
                view.isClickable = false
            }
            for (i in 0 until view.childCount) {
                disableAllInputs(view.getChildAt(i))
            }
        }
    }

    // Persist progress whenever the sheet is paused/dismissed (accidental
    // quit, back press, app backgrounded, page change) so nothing is lost.
    override fun onPause() {
        super.onPause()
        autoSaveDraft()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }

    /**
     * Fire-and-forget save of whatever has been entered so far. Runs on a
     * lifecycle-independent scope so it still completes while the sheet is
     * being torn down, and writes to the same per-staff report the web
     * inspection page reads — so the draft survives crashes/quits AND shows
     * up filled on web. No-ops when nothing has been entered yet.
     */
    private fun autoSaveDraft() {
        val root = rootView ?: return
        val pid = propertyId ?: return
        if (!session.isLoggedIn) return
        // VP-approved inspections are immutable from mobile — skip the
        // pause-time auto-save entirely so we don't bounce a payload that
        // mirrors what's already on file (and never accidentally end up
        // re-opening the workflow if the backend ever tightens up).
        if (isViewOnly) return
        val payload = collectPayload(root, pid)
        if (!payloadHasData(payload)) return
        val token = session.bearerToken
        draftScope.launch { runCatching { api.saveInspection(token, payload) } }
    }

    private fun payloadHasData(p: InspectionSaveRequest): Boolean {
        val anyText = listOf(
            p.customerName, p.surveyNo, p.siteLocation, p.exactLocation, p.landmark,
            p.latLong, p.population, p.accessibilityWidth, p.electricity,
            p.eConnectionToLand, p.telecom, p.railwayStationDistance, p.busStopDistance,
        ).any { !it.isNullOrBlank() }
        return anyText ||
            !p.conclusion.isNullOrBlank() ||
            !p.roadType.isNullOrEmpty() ||
            !p.schoolEntries.isNullOrEmpty() ||
            !p.collegeEntries.isNullOrEmpty() ||
            !p.hospitalEntries.isNullOrEmpty() ||
            !p.mallEntries.isNullOrEmpty() ||
            !p.marketEntries.isNullOrEmpty() ||
            !p.competitors.isNullOrEmpty() ||
            !p.presentDemand.isNullOrEmpty() ||
            !p.futureDemand.isNullOrEmpty() ||
            !p.targetClients.isNullOrEmpty() ||
            p.landlordPrice != null ||
            p.recommendationPrice != null ||
            p.priceCanSell != null
    }

    private fun toastValidation(message: String) {
        android.widget.Toast.makeText(
            requireContext(), message, android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Required Basic-tab fields, mirroring the web inspection validation so a
     * mobile submission that passes here also satisfies the web. Returns the
     * first missing field's message, or null when everything required is set.
     */
    private fun validateBasicTab(root: View): String? {
        val checks = listOf(
            fieldText(root, R.id.fieldOwner) to "Land Owner Name",
            fieldText(root, R.id.fieldSiteLocation) to "Site Location",
            fieldText(root, R.id.fieldExactLocation) to "Exact Location",
            fieldText(root, R.id.fieldLandmark) to "Land Mark",
            fieldText(root, R.id.fieldMapLink) to "Google Map Link",
            fieldText(root, R.id.fieldPopulation) to "Populations",
            fieldText(root, R.id.fieldAccessWidth) to "Access Width",
            fieldText(root, R.id.fieldElectricity) to "Electricity Cable above land",
            fieldText(root, R.id.fieldEConnection) to "E-Connection to land",
            fieldText(root, R.id.fieldTelecom) to "Telecom",
        )
        checks.firstOrNull { it.first.isBlank() }?.let { return "${it.second} is required" }
        
        val eConnection = fieldText(root, R.id.fieldEConnection)
        if (eConnection.equals("Yes", ignoreCase = true)) {
            val phases = fieldText(root, R.id.fieldEConnectionPhases)
            if (phases.isBlank()) {
                return "How many phases is required"
            }
        }
        
        if (selectedRoadTypes.isEmpty()) return "Road Type is required"
        return null
    }

    private fun validateConclusion(root: View): String? =
        if (conclusionText(root).isBlank()) "Recommendation / Conclusion is required" else null

    private fun submitInspection(root: View) {
        val pid = propertyId
        if (pid == null) {
            android.widget.Toast.makeText(requireContext(),
                "Missing property id", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.isLoggedIn) {
            android.widget.Toast.makeText(requireContext(),
                "Please log in again", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (isSaving) return
        isSaving = true
        val payload = collectPayload(root, pid)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.saveInspection(session.bearerToken, payload)
                if (resp.success) {
                    android.widget.Toast.makeText(requireContext(),
                        "Inspection saved", android.widget.Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    android.widget.Toast.makeText(requireContext(),
                        resp.error ?: "Save failed",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (err: Exception) {
                android.widget.Toast.makeText(requireContext(),
                    err.message ?: "Network error",
                    android.widget.Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * Read every field the form currently exposes back into a save payload.
     * Trailing-unit fields read both the EditText AND the trailing label so
     * `Access Width = "20" + "Feet"` becomes accessibilityWidth/Unit pair.
     * Empty strings are converted to nulls so we don't overwrite earlier
     * web-side values with blanks on a partial save.
     */
    private fun collectPayload(root: View, pid: String): InspectionSaveRequest {
        val landlord = parseDouble(fieldText(root, R.id.fieldLandlordPrice))
        val recommend = parseDouble(fieldText(root, R.id.fieldRecommendPrice))
        val canSell = parseDouble(fieldText(root, R.id.fieldSellPrice))
        val schools = collectAreaEntries(root, R.id.schoolEntries)
        val colleges = collectAreaEntries(root, R.id.collegeEntries)
        val hospitals = collectAreaEntries(root, R.id.hospitalEntries)
        val malls = collectAreaEntries(root, R.id.mallEntries)
        val markets = collectAreaEntries(root, R.id.marketEntries)
        val competitors = collectCompetitors(root)
        return InspectionSaveRequest(
            propertyId = pid,
            customerName = nullIfBlank(fieldText(root, R.id.fieldOwner)),
            surveyNo = nullIfBlank(fieldText(root, R.id.fieldSurvey)),
            siteLocation = nullIfBlank(fieldText(root, R.id.fieldSiteLocation)),
            exactLocation = nullIfBlank(fieldText(root, R.id.fieldExactLocation)),
            landmark = nullIfBlank(fieldText(root, R.id.fieldLandmark)),
            latLong = nullIfBlank(fieldText(root, R.id.fieldMapLink)),
            population = nullIfBlank(fieldText(root, R.id.fieldPopulation)),
            accessibilityWidth = nullIfBlank(fieldText(root, R.id.fieldAccessWidth)),
            accessibilityWidthUnit = nullIfBlank(trailingText(root, R.id.fieldAccessWidth)),
            electricity = nullIfBlank(fieldText(root, R.id.fieldElectricity)),
            eConnectionToLand = nullIfBlank(fieldText(root, R.id.fieldEConnection))
                ?.lowercase()?.takeIf { it == "yes" || it == "no" },
            eConnectionPhases = if (fieldText(root, R.id.fieldEConnection).trim().lowercase() == "yes") {
                nullIfBlank(fieldText(root, R.id.fieldEConnectionPhases))
            } else null,
            telecom = nullIfBlank(fieldText(root, R.id.fieldTelecom))
                ?.lowercase()?.takeIf { it == "yes" || it == "no" },
            railwayStationDistance = nullIfBlank(fieldText(root, R.id.fieldRailway)),
            busStopDistance = nullIfBlank(fieldText(root, R.id.fieldBus)),
            roadType = selectedRoadTypes.toList().takeIf { it.isNotEmpty() },
            // Area-tab nearby places. Only send entries (+ matching Exists flag)
            // when the user added at least one row, so an empty category doesn't
            // wipe values entered on web. The web reads these as {name,distance}.
            schoolEntries = schools,
            schoolExists = if (schools != null) true else null,
            collegeEntries = colleges,
            collegeExists = if (colleges != null) true else null,
            hospitalEntries = hospitals,
            hospitalExists = if (hospitals != null) true else null,
            mallEntries = malls,
            mallExists = if (malls != null) true else null,
            marketEntries = markets,
            marketExists = if (markets != null) true else null,
            presentDemand = presentDemand?.let { listOf(it) },
            futureDemand = futureDemand?.let { listOf(it) },
            targetClients = selectedTargets.toList().takeIf { it.isNotEmpty() },
            landlordPrice = landlord,
            landlordPriceUnit = nullIfBlank(trailingText(root, R.id.fieldLandlordPrice)),
            recommendationPrice = recommend,
            recommendationPriceUnit = nullIfBlank(trailingText(root, R.id.fieldRecommendPrice)),
            priceCanSell = canSell,
            priceCanSellUnit = nullIfBlank(trailingText(root, R.id.fieldSellPrice)),
            conclusion = nullIfBlank(conclusionText(root)),
            competitors = competitors,
        )
    }

    /**
     * Read every competitor card in the Competitors tab into the
     * landCompetitors shape the web list renders. Approval type and price
     * units are lowercased to match the web's stored values. Cards with no
     * data entered are skipped; returns null when there are none so a partial
     * save never wipes competitors entered on web (replaceCompetitors also
     * no-ops on an empty list).
     */
    private fun collectCompetitors(root: View): List<InspectionCompetitor>? {
        val container = root.findViewById<LinearLayout>(R.id.competitorEntries) ?: return null
        val competitors = mutableListOf<InspectionCompetitor>()
        for (i in 0 until container.childCount) {
            val card = container.getChildAt(i)
            val promoter = editText(card, R.id.inputPromoterName)
            val project = editText(card, R.id.inputProjectName)
            val location = editText(card, R.id.inputCompetitorLocation)
            val mapLink = editText(card, R.id.inputCompetitorMapLink)
            val extent = editText(card, R.id.inputExtent)
            val stage = editText(card, R.id.inputCurrentStage)
            val mainAmenity = editText(card, R.id.inputAmenitiesMain)
            val amenityRows = collectAmenityRows(card)
            val distProject = editText(card, R.id.inputDistanceProject)
            val distBus = editText(card, R.id.inputDistanceBus)
            val distRailway = editText(card, R.id.inputDistanceRailway)
            val distPublic = editText(card, R.id.inputDistancePublic)
            val distPrivate = editText(card, R.id.inputDistancePrivate)
            val actual = parseDouble(editText(card, R.id.inputActualPrice))
            val final = parseDouble(editText(card, R.id.inputFinalPrice))
            val approval = approvalSlug(labelText(card, R.id.dropdownApprovalLabel))
            val actualUnit = unitSlug(labelText(card, R.id.actualPriceUnitLabel))
            val finalUnit = unitSlug(labelText(card, R.id.finalPriceUnitLabel))

            val amenities = mainAmenity.ifEmpty { amenityRows.joinToString(", ") }
            val anyData = listOf(
                promoter, project, location, mapLink, extent, stage, amenities,
                distProject, distBus, distRailway, distPublic, distPrivate,
            ).any { it.isNotEmpty() } ||
                amenityRows.isNotEmpty() || actual != null || final != null ||
                approval != null

            if (!anyData) continue
            competitors.add(
                InspectionCompetitor(
                    promoterName = nullIfBlank(promoter),
                    projectName = nullIfBlank(project),
                    location = nullIfBlank(location),
                    latLong = nullIfBlank(mapLink),
                    extentUnits = nullIfBlank(extent),
                    approvalType = approval,
                    amenities = nullIfBlank(amenities),
                    amenitiesList = amenityRows.takeIf { it.isNotEmpty() },
                    currentStage = nullIfBlank(stage),
                    distanceFromProject = nullIfBlank(distProject),
                    distanceFromBusStand = nullIfBlank(distBus),
                    distanceFromRailway = nullIfBlank(distRailway),
                    distanceFromPublic = nullIfBlank(distPublic),
                    distanceFromPrivate = nullIfBlank(distPrivate),
                    actualPrice = actual,
                    actualPriceUnit = actual?.let { actualUnit },
                    finalPrice = final,
                    finalPriceUnit = final?.let { finalUnit },
                ),
            )
        }
        return competitors.takeIf { it.isNotEmpty() }
    }

    private fun collectAmenityRows(card: View): List<String> {
        val list = card.findViewById<LinearLayout>(R.id.amenitiesList) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i).findViewById<EditText>(R.id.inputAmenityName)
                ?.text?.toString().orEmpty().trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun editText(card: View, id: Int): String =
        card.findViewById<EditText>(id)?.text?.toString().orEmpty().trim()

    private fun labelText(card: View, id: Int): String =
        card.findViewById<TextView>(id)?.text?.toString().orEmpty().trim()

    // "None" / blank → null; CMDA/DTCP/Panchayat → server union slug.
    private fun approvalSlug(label: String): String? = when (label.lowercase()) {
        "cmda" -> "cmda"
        "dtcp" -> "dtcp"
        "panchayat" -> "panchayat"
        else -> null
    }

    private fun unitSlug(label: String): String? =
        label.lowercase().takeIf { it.isNotEmpty() }

    /**
     * Read every populated row out of an Area-tab category container
     * (school / college / hospital / mall) into the {name, distance} shape
     * the server and web expect. Rows where both fields are empty are
     * skipped. Returns null when nothing is filled so the partial-save
     * "don't overwrite with blanks" rule holds.
     */
    private fun collectAreaEntries(root: View, containerId: Int): List<InspectionAreaEntry>? {
        val container = root.findViewById<LinearLayout>(containerId) ?: return null
        val entries = mutableListOf<InspectionAreaEntry>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            // Skip the inline "+ Add another" pill — it lives inside the
            // entries container alongside real rows so the affordance
            // floats with the list, but it carries no name/distance.
            if (row.getTag(R.id.areaEntryAddPillTag) == true) continue
            val name = row.findViewById<EditText>(R.id.areaEntryName)
                ?.text?.toString().orEmpty().trim()
            val distance = row.findViewById<EditText>(R.id.areaEntryDistance)
                ?.text?.toString().orEmpty().trim()
            if (name.isNotEmpty() || distance.isNotEmpty()) {
                entries.add(InspectionAreaEntry(name = name, distance = distance))
            }
        }
        return entries.takeIf { it.isNotEmpty() }
    }

    private fun conclusionText(root: View): String =
        root.findViewById<EditText>(R.id.inputConclusion)?.text?.toString().orEmpty().trim()

    /**
     * Push values fetched from the server back into the form widgets so an
     * inspector returning to a partly-filled report sees what they (or the
     * web side) typed earlier, instead of a blank form.
     */
    private fun applyPrefill(root: View, report: InspectionReportData) {
        report.customerName?.let { setFieldText(root, R.id.fieldOwner, it) }
        report.surveyNo?.let { setFieldText(root, R.id.fieldSurvey, it) }
        report.siteLocation?.let { setFieldText(root, R.id.fieldSiteLocation, it) }
        report.exactLocation?.let { setFieldText(root, R.id.fieldExactLocation, it) }
        report.landmark?.let { setFieldText(root, R.id.fieldLandmark, it) }
        report.latLong?.let { setFieldText(root, R.id.fieldMapLink, it) }
        report.population?.let { setFieldText(root, R.id.fieldPopulation, it) }
        report.accessibilityWidth?.let { setFieldText(root, R.id.fieldAccessWidth, it) }
        report.accessibilityWidthUnit?.let { setTrailingText(root, R.id.fieldAccessWidth, it) }
        report.electricity?.let { setFieldText(root, R.id.fieldElectricity, it) }
        report.eConnectionToLand?.let {
            val capitalized = it.replaceFirstChar { c -> c.uppercase() }
            setFieldText(root, R.id.fieldEConnection, capitalized)
            val eConnectionPhasesField = root.findViewById<View>(R.id.fieldEConnectionPhases)
            if (capitalized.equals("Yes", ignoreCase = true)) {
                eConnectionPhasesField.visibility = View.VISIBLE
            } else {
                eConnectionPhasesField.visibility = View.GONE
            }
        }
        report.eConnectionPhases?.let {
            setFieldText(root, R.id.fieldEConnectionPhases, it)
        }
        report.telecom?.let {
            setFieldText(root, R.id.fieldTelecom, it.replaceFirstChar { c -> c.uppercase() })
        }
        report.railwayStationDistance?.let { setFieldText(root, R.id.fieldRailway, it) }
        report.busStopDistance?.let { setFieldText(root, R.id.fieldBus, it) }
        report.landlordPrice?.let { setFieldText(root, R.id.fieldLandlordPrice, formatPrice(it)) }
        report.landlordPriceUnit?.let { setTrailingText(root, R.id.fieldLandlordPrice, it) }
        report.recommendationPrice?.let { setFieldText(root, R.id.fieldRecommendPrice, formatPrice(it)) }
        report.recommendationPriceUnit?.let { setTrailingText(root, R.id.fieldRecommendPrice, it) }
        report.priceCanSell?.let { setFieldText(root, R.id.fieldSellPrice, formatPrice(it)) }
        report.priceCanSellUnit?.let { setTrailingText(root, R.id.fieldSellPrice, it) }
        // Demand chips — single-select per row. The first item server-side
        // wins; UI only supports one anyway.
        report.presentDemand?.firstOrNull()?.let { picked ->
            presentDemand = picked
            applyDemandSelection(root, isPresent = true, picked)
        }
        report.futureDemand?.firstOrNull()?.let { picked ->
            futureDemand = picked
            applyDemandSelection(root, isPresent = false, picked)
        }
        report.targetClients?.let { saved ->
            selectedTargets.clear()
            selectedTargets.addAll(saved)
            applyTargetClientsSelection(root)
        }
        report.roadType?.let { saved ->
            selectedRoadTypes.clear()
            selectedRoadTypes.addAll(saved)
            applyRoadTypeSelection(root)
        }
        report.conclusion?.let {
            root.findViewById<EditText>(R.id.inputConclusion)?.setText(it)
        }
        report.schoolEntries?.let {
            prefillAreaEntries(root, R.id.schoolEntries,
                R.drawable.ic_inspection_field_school, "Enter School Name", "School", it)
        }
        report.collegeEntries?.let {
            prefillAreaEntries(root, R.id.collegeEntries,
                R.drawable.ic_inspection_field_college, "Enter College Name", "College", it)
        }
        report.hospitalEntries?.let {
            prefillAreaEntries(root, R.id.hospitalEntries,
                R.drawable.ic_inspection_field_hospital, "Enter Hospital Name", "Hospital", it)
        }
        report.mallEntries?.let {
            prefillAreaEntries(root, R.id.mallEntries,
                R.drawable.ic_inspection_field_mall, "Enter Mall Name", "Mall", it)
        }
        report.marketEntries?.let {
            prefillAreaEntries(root, R.id.marketEntries,
                R.drawable.ic_inspection_field_market, "Enter Market Name", "Market", it)
        }
    }

    /**
     * Replace a category's rows with the saved entries so a returning
     * inspector (or a web-side edit) shows up filled instead of empty.
     * `categoryLabel` is forwarded so the "+ Add another X" pill at the
     * bottom of the prefilled list matches its category.
     */
    private fun prefillAreaEntries(
        root: View,
        containerId: Int,
        iconRes: Int,
        nameHint: String,
        categoryLabel: String,
        saved: List<InspectionAreaEntry>,
    ) {
        val container = root.findViewById<LinearLayout>(containerId) ?: return
        container.removeAllViews()
        saved.forEach { addAreaEntry(container, iconRes, nameHint, categoryLabel, it.name, it.distance) }
    }

    private fun applyDemandSelection(root: View, isPresent: Boolean, picked: String) {
        val chips = if (isPresent) {
            listOf(R.id.chipPresentVilla to "Villa",
                R.id.chipPresentPlot to "Plot",
                R.id.chipPresentApartments to "Apartments")
        } else {
            listOf(R.id.chipFutureVilla to "Villa",
                R.id.chipFuturePlot to "Plot",
                R.id.chipFutureApartments to "Apartments")
        }
        chips.forEach { (id, key) ->
            styleDemandChip(root.findViewById(id), selected = key == picked)
        }
    }

    private fun applyTargetClientsSelection(root: View) {
        val rows = listOf(
            R.id.rowTargetHighEnd to "Hig End (Above 1Cr)",
            R.id.rowTargetUpperMiddle to "Upper Middle (50L - 1Cr)",
            R.id.rowTargetMiddle to "Middle (30L - 50L)",
            R.id.rowTargetLower to "Lower (30L - 50L)",
            R.id.rowTargetEvs to "EVS Below (20L)",
        )
        rows.forEach { (id, label) ->
            val row = root.findViewById<LinearLayout>(id) ?: return@forEach
            val check = row.findViewById<ImageView>(R.id.targetRowCheck)
            styleTargetRow(row, check, selected = selectedTargets.contains(label))
        }
    }

    private fun applyRoadTypeSelection(root: View) {
        val rows = listOf(
            Triple(R.id.rowRoadMud, R.id.checkRoadMud, "Mud"),
            Triple(R.id.rowRoadThar, R.id.checkRoadThar, "Thar"),
            Triple(R.id.rowRoadConcrete, R.id.checkRoadConcrete, "Concrete"),
            Triple(R.id.rowRoadConcreteBlocks, R.id.checkRoadConcreteBlocks, "Concrete Blocks"),
        )
        rows.forEach { (rowId, checkId, key) ->
            val row = root.findViewById<LinearLayout>(rowId) ?: return@forEach
            val check = root.findViewById<ImageView>(checkId)
            val selected = selectedRoadTypes.contains(key)
            row.setBackgroundResource(
                if (selected) R.drawable.bg_inspection_roadtype_selected
                else R.drawable.bg_inspection_field
            )
            check.setImageResource(
                if (selected) R.drawable.ic_check_circle
                else R.drawable.ic_checkbox_unchecked
            )
        }
    }

    private fun fieldText(root: View, fieldId: Int): String =
        root.findViewById<View>(fieldId)
            ?.findViewById<EditText>(R.id.fieldInput)
            ?.text?.toString().orEmpty().trim()

    private fun setFieldText(root: View, fieldId: Int, value: String) {
        root.findViewById<View>(fieldId)
            ?.findViewById<EditText>(R.id.fieldInput)
            ?.setText(value)
    }

    /** Open the full-screen map centered on the location parsed from [current]
     *  (a "lat,lng" string or a Google Maps URL), letting the inspector view it,
     *  search, and drop/adjust the pin. [onPicked] receives the chosen coords. */
    private fun openLocationMap(current: String?, onPicked: (Double, Double) -> Unit) {
        val coords = parseLatLng(current)
        val sheet = com.manjugroups.m_connect.ui.common.MapPinDropBottomSheet
            .newInstance(coords?.first, coords?.second)
        sheet.setListener { result -> onPicked(result.lat, result.lng) }
        sheet.showOnce(parentFragmentManager, "inspection_map_pin")
    }

    /** Parse coordinates from a "lat,lng" string OR a Google Maps URL
     *  (…@lat,lng…, ?q=lat,lng, or …!3dlat!4dlng…). Null when none is found. */
    private fun parseLatLng(value: String?): Pair<Double, Double>? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        val patterns = listOf(
            Regex("""^\s*(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$"""),
            Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)"""),
            Regex("""[?&]q=(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)"""),
            Regex("""!3d(-?\d{1,3}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
        )
        for (re in patterns) {
            re.find(s)?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) return lat to lng
            }
        }
        return null
    }

    private fun trailingText(root: View, fieldId: Int): String =
        root.findViewById<View>(fieldId)
            ?.findViewById<TextView>(R.id.fieldTrailingText)
            ?.text?.toString().orEmpty().trim()

    private fun setTrailingText(root: View, fieldId: Int, value: String) {
        root.findViewById<View>(fieldId)
            ?.findViewById<TextView>(R.id.fieldTrailingText)
            ?.text = value
    }

    private fun nullIfBlank(s: String?): String? = s?.takeIf { it.isNotBlank() }
    private fun parseDouble(s: String?): Double? =
        s?.replace(",", "")?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun formatPrice(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun bindBasicDetailsFields(root: View) {
        bindField(
            root.findViewById(R.id.fieldOwner),
            label = "Land Owner Name",
            hint = "Enter Name",
            iconRes = R.drawable.ic_inspection_field_owner,
        )
        bindField(
            root.findViewById(R.id.fieldSurvey),
            label = "Survey No",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_survey,
            required = false,
        )
        bindField(
            root.findViewById(R.id.fieldSiteLocation),
            label = "Site Location",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_location_tick,
        )
        bindField(
            root.findViewById(R.id.fieldExactLocation),
            label = "Exact Location",
            hint = "Enter Details",
            iconRes = R.drawable.ic_location_pin,
        )
        bindField(
            root.findViewById(R.id.fieldLandmark),
            label = "Land Mark",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_landmark,
        )
        bindField(
            root.findViewById(R.id.fieldMapLink),
            label = "Google Map Link",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_maplink,
        )
        bindField(
            root.findViewById(R.id.fieldPopulation),
            label = "Populations",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_population,
        )
    }

    private fun bindAccessibilityFields(root: View) {
        val accessWidthField = root.findViewById<View>(R.id.fieldAccessWidth)
        bindField(
            accessWidthField,
            label = "Access Width",
            hint = "Select Access Width",
            iconRes = R.drawable.ic_inspection_field_access,
            trailing = "Feet",
            trailingDropdownOptions = listOf("Feet", "Meter"),
        )
        bindDropdownField(
            root.findViewById(R.id.fieldElectricity),
            label = "Electricity Cable above land",
            hint = "Select",
            iconRes = R.drawable.ic_inspection_field_electricity,
            options = listOf("LT", "HT", "NIL"),
        )
        val eConnectionPhasesField = root.findViewById<View>(R.id.fieldEConnectionPhases)
        eConnectionPhasesField.visibility = View.GONE
        bindDropdownField(
            eConnectionPhasesField,
            label = "How many phases",
            hint = "Select",
            iconRes = R.drawable.ic_inspection_field_electricity,
            options = listOf("1 Phase", "3 Phase"),
        )

        bindDropdownField(
            root.findViewById(R.id.fieldEConnection),
            label = "E-Connection to land",
            hint = "Select",
            iconRes = R.drawable.ic_inspection_field_electricity,
            options = listOf("Yes", "No"),
        ) { picked ->
            if (picked.equals("Yes", ignoreCase = true)) {
                eConnectionPhasesField.visibility = View.VISIBLE
            } else {
                eConnectionPhasesField.visibility = View.GONE
                setFieldText(root, R.id.fieldEConnectionPhases, "")
            }
        }
        bindDropdownField(
            root.findViewById(R.id.fieldTelecom),
            label = "Telecom",
            hint = "Select",
            iconRes = R.drawable.ic_inspection_field_telecom,
            options = listOf("Yes", "No"),
        )
        bindField(
            root.findViewById(R.id.fieldRailway),
            label = "Railway Station Distance",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_railway,
            trailing = "K/m",
            required = false,
        )
        bindField(
            root.findViewById(R.id.fieldBus),
            label = "Bus Stop Distance",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_bus,
            trailing = "K/m",
            required = false,
        )
    }

    /**
     * Binds a field. When [trailingDropdownOptions] is non-null the
     * trailing chevron is shown and tapping the trailing area opens a
     * compact ListPopupWindow anchored to the field; the picked value
     * replaces the trailing label so the user sees their selection.
     */
    private fun bindField(
        fieldRoot: View,
        label: String,
        hint: String,
        iconRes: Int,
        trailing: String? = null,
        trailingDropdownOptions: List<String>? = null,
        required: Boolean = true,
    ) {
        fieldRoot.findViewById<TextView>(R.id.fieldLabel).text = label
        fieldRoot.findViewById<TextView>(R.id.fieldRequiredStar).visibility =
            if (required) View.VISIBLE else View.GONE
        fieldRoot.findViewById<EditText>(R.id.fieldInput).hint = hint
        fieldRoot.findViewById<ImageView>(R.id.fieldIcon).setImageResource(iconRes)
        val container = fieldRoot.findViewById<View>(R.id.fieldTrailingContainer)
        val trailingText = fieldRoot.findViewById<TextView>(R.id.fieldTrailingText)
        val chevron = fieldRoot.findViewById<View>(R.id.fieldTrailingChevron)
        if (trailing == null) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        trailingText.text = trailing
        if (trailingDropdownOptions != null) {
            chevron.visibility = View.VISIBLE
            container.setOnClickListener { anchor ->
                showTrailingDropdown(anchor, trailingDropdownOptions) { picked ->
                    trailingText.text = picked
                }
            }
        } else {
            chevron.visibility = View.GONE
            container.setOnClickListener(null)
            container.isClickable = false
        }
    }

    /**
     * Binds a whole-field value dropdown (e.g. E-Connection / Telecom = Yes/No).
     * The input becomes a read-only display (no keyboard, no caret); tapping
     * anywhere on the field opens a popup of [options] whose pick fills the
     * field. A trailing chevron signals it's a selector.
     */
    private fun bindDropdownField(
        fieldRoot: View,
        label: String,
        hint: String,
        iconRes: Int,
        options: List<String>,
        required: Boolean = true,
        onPicked: ((String) -> Unit)? = null,
    ) {
        fieldRoot.findViewById<TextView>(R.id.fieldLabel).text = label
        fieldRoot.findViewById<TextView>(R.id.fieldRequiredStar).visibility =
            if (required) View.VISIBLE else View.GONE
        fieldRoot.findViewById<ImageView>(R.id.fieldIcon).setImageResource(iconRes)
        val input = fieldRoot.findViewById<EditText>(R.id.fieldInput)
        input.hint = hint
        // Read-only selector: suppress the keyboard/caret and let taps bubble
        // to the open handler instead of starting text entry.
        input.inputType = android.text.InputType.TYPE_NULL
        input.isFocusable = false
        input.isFocusableInTouchMode = false
        input.isCursorVisible = false
        input.keyListener = null

        val container = fieldRoot.findViewById<View>(R.id.fieldTrailingContainer)
        fieldRoot.findViewById<TextView>(R.id.fieldTrailingText).visibility = View.GONE
        fieldRoot.findViewById<View>(R.id.fieldTrailingChevron).visibility = View.VISIBLE
        container.visibility = View.VISIBLE

        val open = View.OnClickListener {
            showTrailingDropdown(fieldRoot, options) { picked ->
                input.setText(picked)
                onPicked?.invoke(picked)
            }
        }
        input.setOnClickListener(open)
        container.setOnClickListener(open)
        fieldRoot.setOnClickListener(open)
    }

    /**
     * Small popup anchored to the trailing-chevron area. Used by the
     * Access Width "Feet / Meter" picker and ready to be reused for
     * any future dropdown field. Width matches the anchor so the popup
     * reads as an extension of the trailing pill.
     */
    private fun showTrailingDropdown(
        anchor: View,
        options: List<String>,
        onPicked: (String) -> Unit,
    ) {
        val ctx = requireContext()
        val popup = androidx.appcompat.widget.ListPopupWindow(ctx).apply {
            anchorView = anchor
            width = (anchor.width.coerceAtLeast(
                (96 * ctx.resources.displayMetrics.density).toInt()
            ))
            setBackgroundDrawable(
                androidx.core.content.ContextCompat.getDrawable(
                    ctx, R.drawable.bg_inspection_field,
                )
            )
            verticalOffset = (4 * ctx.resources.displayMetrics.density).toInt()
            isModal = true
        }
        popup.setAdapter(
            android.widget.ArrayAdapter(
                ctx,
                android.R.layout.simple_list_item_1,
                options,
            )
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            onPicked(options[position])
            popup.dismiss()
        }
        popup.show()
    }

    private fun bindRoadTypeRows(root: View) {
        bindRoadRow(root, R.id.rowRoadMud, R.id.checkRoadMud, "Mud")
        bindRoadRow(root, R.id.rowRoadThar, R.id.checkRoadThar, "Thar")
        bindRoadRow(root, R.id.rowRoadConcrete, R.id.checkRoadConcrete, "Concrete")
        bindRoadRow(
            root, R.id.rowRoadConcreteBlocks, R.id.checkRoadConcreteBlocks,
            "Concrete Blocks",
        )
    }

    private fun bindRoadRow(root: View, rowId: Int, checkId: Int, key: String) {
        val row = root.findViewById<LinearLayout>(rowId)
        val check = root.findViewById<ImageView>(checkId)
        row.setOnClickListener {
            if (selectedRoadTypes.contains(key)) {
                selectedRoadTypes.remove(key)
                row.setBackgroundResource(R.drawable.bg_inspection_field)
                check.setImageResource(R.drawable.ic_checkbox_unchecked)
            } else {
                selectedRoadTypes.add(key)
                row.setBackgroundResource(R.drawable.bg_inspection_roadtype_selected)
                check.setImageResource(R.drawable.ic_check_circle)
            }
        }
    }

    /**
     * Tab switching across all 5 tabs. Also flips the bottom CTA label
     * to "Submit" when the user lands on Competitors (the last tab),
     * "Next" everywhere else. Each tab paints its own active state via
     * [styleInspectionTab]: blue gradient circle bg + white-tinted icon
     * + blue label + visible 2dp indicator; inactive tabs lose the
     * background, get a grey tint, grey label and an invisible indicator.
     */
    private fun bindTabs(root: View) {
        val basic = root.findViewById<View>(R.id.basicTabContent)
        val area = root.findViewById<View>(R.id.areaTabContent)
        val market = root.findViewById<View>(R.id.marketTabContent)
        val conclusions = root.findViewById<View>(R.id.conclusionsTabContent)
        val competitors = root.findViewById<View>(R.id.competitorsTabContent)
        val nextBtn = root.findViewById<TextView>(R.id.btnInspectionNext)

        val basicIcon = root.findViewById<ImageView>(R.id.tabBasicIcon)
        val basicLabel = root.findViewById<TextView>(R.id.tabBasicLabel)
        val basicInd = root.findViewById<View>(R.id.tabBasicIndicator)
        val areaIcon = root.findViewById<ImageView>(R.id.tabAreaIcon)
        val areaLabel = root.findViewById<TextView>(R.id.tabAreaLabel)
        val areaInd = root.findViewById<View>(R.id.tabAreaIndicator)
        val marketIcon = root.findViewById<ImageView>(R.id.tabMarketIcon)
        val marketLabel = root.findViewById<TextView>(R.id.tabMarketLabel)
        val marketInd = root.findViewById<View>(R.id.tabMarketIndicator)
        val concIcon = root.findViewById<ImageView>(R.id.tabConclusionsIcon)
        val concLabel = root.findViewById<TextView>(R.id.tabConclusionsLabel)
        val concInd = root.findViewById<View>(R.id.tabConclusionsIndicator)
        val compIcon = root.findViewById<ImageView>(R.id.tabCompetitorsIcon)
        val compLabel = root.findViewById<TextView>(R.id.tabCompetitorsLabel)
        val compInd = root.findViewById<View>(R.id.tabCompetitorsIndicator)

        fun switchTo(tab: Tab) {
            // Persist what's filled before navigating away from the tab.
            autoSaveDraft()
            activeTab = tab
            basic.visibility = if (tab == Tab.BASIC) View.VISIBLE else View.GONE
            area.visibility = if (tab == Tab.AREA) View.VISIBLE else View.GONE
            market.visibility = if (tab == Tab.MARKET) View.VISIBLE else View.GONE
            conclusions.visibility = if (tab == Tab.CONCLUSIONS) View.VISIBLE else View.GONE
            competitors.visibility = if (tab == Tab.COMPETITORS) View.VISIBLE else View.GONE
            nextBtn.text = if (tab == Tab.COMPETITORS) "Submit" else "Next"

            styleInspectionTab(basicIcon, basicLabel, basicInd, tab == Tab.BASIC)
            styleInspectionTab(areaIcon, areaLabel, areaInd, tab == Tab.AREA)
            styleInspectionTab(marketIcon, marketLabel, marketInd, tab == Tab.MARKET)
            styleInspectionTab(concIcon, concLabel, concInd, tab == Tab.CONCLUSIONS)
            styleInspectionTab(compIcon, compLabel, compInd, tab == Tab.COMPETITORS)
        }
        root.findViewById<View>(R.id.tabBasic).setOnClickListener { switchTo(Tab.BASIC) }
        root.findViewById<View>(R.id.tabArea).setOnClickListener { switchTo(Tab.AREA) }
        root.findViewById<View>(R.id.tabMarket).setOnClickListener { switchTo(Tab.MARKET) }
        root.findViewById<View>(R.id.tabConclusions).setOnClickListener {
            switchTo(Tab.CONCLUSIONS)
        }
        root.findViewById<View>(R.id.tabCompetitors).setOnClickListener {
            switchTo(Tab.COMPETITORS)
        }
    }

    /**
     * Active = blue gradient circle bg, white-tinted glyph, blue label,
     * semibold weight, visible indicator.
     * Inactive = no bg, grey-tinted glyph, grey label, medium weight,
     * INVISIBLE indicator (kept in the layout to preserve vertical
     * footprint, just not drawn).
     */
    private fun styleInspectionTab(
        icon: ImageView,
        label: TextView,
        indicator: View,
        active: Boolean,
    ) {
        val ctx = context ?: return
        if (active) {
            icon.setBackgroundResource(R.drawable.bg_inspection_tab_circle_active)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FFFFFF")
            )
            label.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
            label.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_semibold)
            indicator.visibility = View.VISIBLE
        } else {
            icon.setBackgroundResource(R.drawable.bg_inspection_tab_circle_inactive)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#6A6D78")
            )
            label.setTextColor(android.graphics.Color.parseColor("#6A6D78"))
            label.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_medium)
            indicator.visibility = View.INVISIBLE
        }
    }

    /**
     * Competitors tab — "Create Competitor" inflates a numbered entry
     * card. Each card has its own Approval Type dropdown, two price
     * unit dropdowns (Acre / Ground / Sqft / Cent), and a nested
     * Amenities sub-list (Add / Delete). Deleting an entry renumbers
     * the remaining cards so they always read 1, 2, 3 … in order.
     */
    private fun bindCompetitorsTab(root: View) {
        val entriesContainer = root.findViewById<LinearLayout>(R.id.competitorEntries)
        root.findViewById<View>(R.id.createCompetitor).setOnClickListener {
            inflateCompetitorEntry(entriesContainer)
        }
    }

    private fun inflateCompetitorEntry(
        container: LinearLayout,
        prefill: InspectionCompetitor? = null,
    ) {
        val ctx = requireContext()
        val entry = LayoutInflater.from(ctx)
            .inflate(R.layout.component_competitor_entry, container, false)

        container.addView(entry)
        renumberCompetitors(container)

        entry.findViewById<View>(R.id.competitorDelete).setOnClickListener {
            container.removeView(entry)
            renumberCompetitors(container)
        }

        // View / adjust this competitor's location on the full-screen map. The
        // Google Map Link field holds the coordinates ("lat,lng") the web list
        // reads, so a dropped pin writes back the same shape.
        val compMapInput = entry.findViewById<android.widget.EditText>(R.id.inputCompetitorMapLink)
        entry.findViewById<View>(R.id.btnCompetitorViewMap).setOnClickListener {
            openLocationMap(compMapInput.text?.toString()) { lat, lng ->
                compMapInput.setText("$lat,$lng")
            }
        }

        // Approval Type dropdown (None / CMDA / DTCP / Panchayat). Default to
        // "None" so an untouched card collects as no approval rather than the
        // layout's placeholder.
        val approvalRow = entry.findViewById<View>(R.id.dropdownApproval)
        val approvalLabel = entry.findViewById<TextView>(R.id.dropdownApprovalLabel)
        approvalLabel.text = approvalDisplay(prefill?.approvalType)
        approvalRow.setOnClickListener {
            showTrailingDropdown(
                anchor = approvalRow,
                options = listOf("None", "CMDA", "DTCP", "Panchayat"),
            ) { picked -> approvalLabel.text = picked }
        }

        // Unit dropdowns on the two price fields.
        val unitOptions = listOf("Acre", "Ground", "Sqft", "Cent")
        val actualUnit = entry.findViewById<View>(R.id.actualPriceUnit)
        val actualUnitLabel = entry.findViewById<TextView>(R.id.actualPriceUnitLabel)
        prefill?.actualPriceUnit?.let { actualUnitLabel.text = unitDisplay(it) }
        actualUnit.setOnClickListener {
            showTrailingDropdown(actualUnit, unitOptions) { picked ->
                actualUnitLabel.text = picked
            }
        }
        val finalUnit = entry.findViewById<View>(R.id.finalPriceUnit)
        val finalUnitLabel = entry.findViewById<TextView>(R.id.finalPriceUnitLabel)
        prefill?.finalPriceUnit?.let { finalUnitLabel.text = unitDisplay(it) }
        finalUnit.setOnClickListener {
            showTrailingDropdown(finalUnit, unitOptions) { picked ->
                finalUnitLabel.text = picked
            }
        }

        // Nested Amenities list — "+ Add Amenity" inflates a sub-entry
        // with its own delete button.
        val amenitiesList = entry.findViewById<LinearLayout>(R.id.amenitiesList)
        entry.findViewById<View>(R.id.btnAddAmenity).setOnClickListener {
            addAmenityRow(amenitiesList)
        }

        if (prefill != null) {
            setEntryText(entry, R.id.inputPromoterName, prefill.promoterName)
            setEntryText(entry, R.id.inputProjectName, prefill.projectName)
            setEntryText(entry, R.id.inputCompetitorLocation, prefill.location)
            setEntryText(entry, R.id.inputCompetitorMapLink, prefill.latLong)
            setEntryText(entry, R.id.inputExtent, prefill.extentUnits)
            setEntryText(entry, R.id.inputCurrentStage, prefill.currentStage)
            setEntryText(entry, R.id.inputDistanceProject, prefill.distanceFromProject)
            setEntryText(entry, R.id.inputDistanceBus, prefill.distanceFromBusStand)
            setEntryText(entry, R.id.inputDistanceRailway, prefill.distanceFromRailway)
            setEntryText(entry, R.id.inputDistancePublic, prefill.distanceFromPublic)
            setEntryText(entry, R.id.inputDistancePrivate, prefill.distanceFromPrivate)
            setEntryText(entry, R.id.inputActualPrice, prefill.actualPrice?.let { formatPrice(it) })
            setEntryText(entry, R.id.inputFinalPrice, prefill.finalPrice?.let { formatPrice(it) })
            // Prefer the structured amenity list; fall back to the freeform
            // field so neither shape is lost or duplicated.
            val savedAmenities = prefill.amenitiesList
            if (!savedAmenities.isNullOrEmpty()) {
                savedAmenities.forEach { addAmenityRow(amenitiesList, it) }
            } else {
                setEntryText(entry, R.id.inputAmenitiesMain, prefill.amenities)
            }
        }
    }

    private fun addAmenityRow(amenitiesList: LinearLayout, value: String? = null) {
        val amenity = LayoutInflater.from(requireContext())
            .inflate(R.layout.component_competitor_amenity, amenitiesList, false)
        if (!value.isNullOrEmpty()) {
            amenity.findViewById<EditText>(R.id.inputAmenityName).setText(value)
        }
        amenity.findViewById<View>(R.id.btnAmenityDelete).setOnClickListener {
            amenitiesList.removeView(amenity)
        }
        amenitiesList.addView(amenity)
    }

    private fun setEntryText(card: View, id: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        card.findViewById<EditText>(id)?.setText(value)
    }

    private fun approvalDisplay(slug: String?): String = when (slug?.lowercase()) {
        "cmda" -> "CMDA"
        "dtcp" -> "DTCP"
        "panchayat" -> "Panchayat"
        else -> "None"
    }

    private fun unitDisplay(slug: String): String =
        slug.trim().replaceFirstChar { it.uppercase() }

    /**
     * Rebuild the Competitors tab from the saved set so a returning inspector
     * (or web edit) sees their competitors instead of an empty tab.
     */
    private fun prefillCompetitors(root: View, competitors: List<InspectionCompetitor>) {
        if (competitors.isEmpty()) return
        val container = root.findViewById<LinearLayout>(R.id.competitorEntries) ?: return
        container.removeAllViews()
        competitors.forEach { inflateCompetitorEntry(container, it) }
    }

    /**
     * Re-stamp the "1, 2, 3 …" badge on every competitor card after an
     * insert or delete. Keeps the numbering tight when the user removes
     * a middle card.
     */
    private fun renumberCompetitors(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val card = container.getChildAt(i)
            card.findViewById<TextView>(R.id.competitorNumber)?.text = (i + 1).toString()
        }
    }

    /**
     * Market tab wiring:
     *  • Present/Future Demand — three single-select chips per row
     *    (Villa / Plot / Apartments). Tapping flips that row's selection
     *    via [styleDemandChip].
     *  • Target Clients — five multi-select rows; same flip pattern via
     *    [styleTargetRow].
     *  • Three price fields share the same Acre/Ground/Sqft/Cent unit
     *    dropdown via the existing trailing-dropdown machinery.
     */
    private fun bindMarketTab(root: View) {
        val presentChips = listOf(
            R.id.chipPresentVilla to "Villa",
            R.id.chipPresentPlot to "Plot",
            R.id.chipPresentApartments to "Apartments",
        )
        presentChips.forEach { (id, key) ->
            val chip = root.findViewById<View>(id)
            chip.findViewById<TextView>(R.id.demandChipLabel).text = key
            chip.setOnClickListener {
                presentDemand = key
                presentChips.forEach { (otherId, otherKey) ->
                    styleDemandChip(
                        root.findViewById(otherId),
                        selected = otherKey == key,
                    )
                }
            }
        }
        val futureChips = listOf(
            R.id.chipFutureVilla to "Villa",
            R.id.chipFuturePlot to "Plot",
            R.id.chipFutureApartments to "Apartments",
        )
        futureChips.forEach { (id, key) ->
            val chip = root.findViewById<View>(id)
            chip.findViewById<TextView>(R.id.demandChipLabel).text = key
            chip.setOnClickListener {
                futureDemand = key
                futureChips.forEach { (otherId, otherKey) ->
                    styleDemandChip(
                        root.findViewById(otherId),
                        selected = otherKey == key,
                    )
                }
            }
        }

        bindTargetRow(root, R.id.rowTargetHighEnd, "Hig End (Above 1Cr)")
        bindTargetRow(root, R.id.rowTargetUpperMiddle, "Upper Middle (50L - 1Cr)")
        bindTargetRow(root, R.id.rowTargetMiddle, "Middle (30L - 50L)")
        bindTargetRow(root, R.id.rowTargetLower, "Lower (30L - 50L)")
        bindTargetRow(root, R.id.rowTargetEvs, "EVS Below (20L)")

        val unitOptions = listOf("Acre", "Ground", "Sqft", "Cent")
        bindField(
            root.findViewById(R.id.fieldLandlordPrice),
            label = "Landlord Price (Rs.)",
            hint = "Enter Amount",
            iconRes = R.drawable.ic_inspection_field_pricetag,
            trailing = "Acre",
            trailingDropdownOptions = unitOptions,
        )
        bindField(
            root.findViewById(R.id.fieldRecommendPrice),
            label = "Recommendation Price (Rs.)",
            hint = "Enter Amount",
            iconRes = R.drawable.ic_inspection_field_pricetag,
            trailing = "Acre",
            trailingDropdownOptions = unitOptions,
        )
        bindField(
            root.findViewById(R.id.fieldSellPrice),
            label = "Price can sell (Rs.)",
            hint = "Enter Amount",
            iconRes = R.drawable.ic_inspection_field_pricetag,
            trailing = "Acre",
            trailingDropdownOptions = unitOptions,
        )
    }

    private fun styleDemandChip(chip: View, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_inspection_roadtype_selected
            else R.drawable.bg_inspection_field
        )
        chip.findViewById<ImageView>(R.id.demandChipCheck).setImageResource(
            if (selected) R.drawable.ic_check_circle
            else R.drawable.ic_checkbox_unchecked
        )
    }

    private fun bindTargetRow(root: View, rowId: Int, label: String) {
        val row = root.findViewById<LinearLayout>(rowId)
        row.findViewById<TextView>(R.id.targetRowLabel).text = label
        val check = row.findViewById<ImageView>(R.id.targetRowCheck)
        styleTargetRow(row, check, selected = false)
        row.setOnClickListener {
            if (selectedTargets.contains(label)) {
                selectedTargets.remove(label)
                styleTargetRow(row, check, selected = false)
            } else {
                selectedTargets.add(label)
                styleTargetRow(row, check, selected = true)
            }
        }
    }

    private fun styleTargetRow(row: View, check: ImageView, selected: Boolean) {
        row.setBackgroundResource(
            if (selected) R.drawable.bg_inspection_roadtype_selected
            else R.drawable.bg_inspection_field
        )
        check.setImageResource(
            if (selected) R.drawable.ic_check_circle
            else R.drawable.ic_checkbox_unchecked
        )
    }

    /**
     * Wire each Area-tab "Create X" pill to inflate a new dashed-border
     * entry below it. Each entry has its own delete button which removes
     * the entry from its parent container. The icon shown inside the
     * entry's name field matches the category (school cap, college
     * courthouse, ...).
     *
     * Mirrors the web's per-section UX: arbitrary number of entries per
     * category. The top-of-section "Create X" pill adds the first one;
     * once at least one entry exists, an inline "+ Add another …" pill
     * shows at the bottom of the entries list so the affordance is
     * obvious without having to scroll back to the top to tap Create.
     */
    private fun bindAreaTab(root: View) {
        bindAreaCategory(
            root, R.id.createSchool, R.id.schoolEntries,
            R.drawable.ic_inspection_field_school, "Enter School Name", "School",
        )
        bindAreaCategory(
            root, R.id.createCollege, R.id.collegeEntries,
            R.drawable.ic_inspection_field_college, "Enter College Name", "College",
        )
        bindAreaCategory(
            root, R.id.createHospital, R.id.hospitalEntries,
            R.drawable.ic_inspection_field_hospital, "Enter Hospital Name", "Hospital",
        )
        bindAreaCategory(
            root, R.id.createMall, R.id.mallEntries,
            R.drawable.ic_inspection_field_mall, "Enter Mall Name", "Mall",
        )
        bindAreaCategory(
            root, R.id.createMarket, R.id.marketEntries,
            R.drawable.ic_inspection_field_market, "Enter Market Name", "Market",
        )
    }

    private fun bindAreaCategory(
        root: View,
        createRowId: Int,
        entriesContainerId: Int,
        iconRes: Int,
        nameHint: String,
        categoryLabel: String,
    ) {
        val createRow = root.findViewById<View>(createRowId)
        val entries = root.findViewById<LinearLayout>(entriesContainerId)
        createRow.setOnClickListener { addAreaEntry(entries, iconRes, nameHint, categoryLabel) }
    }

    /**
     * Inflate one Area-tab entry into [entries], optionally pre-filled with a
     * saved name/distance. Shared by the "Create X" button (blank row) and
     * prefill (saved rows) so both paths wire the delete button identically.
     *
     * `categoryLabel` is used for the inline "+ Add another <label>" pill
     * that gets appended after the last entry (e.g. "Add another School").
     * Pass null when category context isn't available (legacy call sites);
     * the pill is skipped in that case and the user falls back to the
     * top-of-section "Create X" button.
     */
    private fun addAreaEntry(
        entries: LinearLayout,
        iconRes: Int,
        nameHint: String,
        categoryLabel: String? = null,
        name: String? = null,
        distance: String? = null,
    ) {
        val entry = LayoutInflater.from(requireContext())
            .inflate(R.layout.component_area_entry, entries, false)
        entry.findViewById<ImageView>(R.id.areaEntryNameIcon).setImageResource(iconRes)
        val nameField = entry.findViewById<EditText>(R.id.areaEntryName)
        nameField.hint = nameHint
        if (!name.isNullOrEmpty()) nameField.setText(name)
        if (!distance.isNullOrEmpty()) {
            entry.findViewById<EditText>(R.id.areaEntryDistance).setText(distance)
        }
        entry.findViewById<View>(R.id.areaEntryDelete).setOnClickListener {
            entries.removeView(entry)
            // After delete, re-pin the "+ Add" pill to the bottom (or
            // hide it if zero entries remain). Without this the pill
            // could end up above a phantom gap or stay around with no
            // entries above it.
            refreshAreaAddPill(entries, iconRes, nameHint, categoryLabel)
        }
        // Insert the entry before any existing "+ Add" pill so the pill
        // always stays at the bottom of the container.
        val pillIdx = (0 until entries.childCount).indexOfFirst { i ->
            entries.getChildAt(i).getTag(R.id.areaEntryAddPillTag) == true
        }
        if (pillIdx >= 0) entries.addView(entry, pillIdx) else entries.addView(entry)
        refreshAreaAddPill(entries, iconRes, nameHint, categoryLabel)
    }

    /**
     * Ensure exactly one "+ Add another <label>" pill sits at the bottom
     * of [entries] when there is at least one entry; remove it otherwise.
     * Idempotent — safe to call from any mutation site (add, delete,
     * prefill). The pill itself is tagged with `areaEntryAddPillTag` so
     * we can distinguish it from real entries on subsequent inserts.
     */
    private fun refreshAreaAddPill(
        entries: LinearLayout,
        iconRes: Int,
        nameHint: String,
        categoryLabel: String?,
    ) {
        // Strip any existing pill so we can reposition it cleanly.
        for (i in entries.childCount - 1 downTo 0) {
            if (entries.getChildAt(i).getTag(R.id.areaEntryAddPillTag) == true) {
                entries.removeViewAt(i)
            }
        }
        if (categoryLabel == null) return
        if (entries.childCount == 0) return
        val pill = LayoutInflater.from(requireContext())
            .inflate(R.layout.component_area_add_pill, entries, false)
        pill.setTag(R.id.areaEntryAddPillTag, true)
        pill.findViewById<TextView>(R.id.tvAreaAddLabel).text = "Add another $categoryLabel"
        pill.setOnClickListener { addAreaEntry(entries, iconRes, nameHint, categoryLabel) }
        entries.addView(pill)
    }

    companion object {
        private const val ARG_PROPERTY_ID = "property_id"
        private const val ARG_DISPLAY_TITLE = "display_title"

        // Lifecycle-independent so a draft save fired from onPause/dismiss
        // completes even as the sheet is destroyed. Not cancelled — these are
        // short one-shot saves that must finish.
        private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun newInstance(propertyId: String, displayTitle: String? = null): SiteInspectionBottomSheet =
            SiteInspectionBottomSheet().apply {
                arguments = bundleOf(
                    ARG_PROPERTY_ID to propertyId,
                    ARG_DISPLAY_TITLE to displayTitle,
                )
            }
    }
}
