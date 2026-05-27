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
import com.manjugroups.m_connect.network.InspectionReportData
import com.manjugroups.m_connect.network.InspectionSaveRequest
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
        session = SessionManager(requireContext())
        bindBasicDetailsFields(view)
        bindAccessibilityFields(view)
        bindRoadTypeRows(view)
        bindAreaTab(view)
        bindMarketTab(view)
        bindCompetitorsTab(view)
        bindTabs(view)

        val nextBtn = view.findViewById<TextView>(R.id.btnInspectionNext)
        nextBtn.setOnClickListener {
            if (activeTab != Tab.COMPETITORS) {
                // "Next" steps the tab forward without touching the API.
                advanceToNextTab(view)
            } else {
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
                }
            } catch (_: Exception) {
                // Silent — first-time inspections legitimately have no report
                // yet, and network blips shouldn't block the user from
                // entering one. The submit path will surface a real error.
            }
        }
    }

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
            telecom = nullIfBlank(fieldText(root, R.id.fieldTelecom)),
            railwayStationDistance = nullIfBlank(fieldText(root, R.id.fieldRailway)),
            busStopDistance = nullIfBlank(fieldText(root, R.id.fieldBus)),
            roadType = selectedRoadTypes.toList().takeIf { it.isNotEmpty() },
            presentDemand = presentDemand?.let { listOf(it) },
            futureDemand = futureDemand?.let { listOf(it) },
            targetClients = selectedTargets.toList().takeIf { it.isNotEmpty() },
            landlordPrice = landlord,
            landlordPriceUnit = nullIfBlank(trailingText(root, R.id.fieldLandlordPrice)),
            recommendationPrice = recommend,
            recommendationPriceUnit = nullIfBlank(trailingText(root, R.id.fieldRecommendPrice)),
            priceCanSell = canSell,
            priceCanSellUnit = nullIfBlank(trailingText(root, R.id.fieldSellPrice)),
        )
    }

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
            setFieldText(root, R.id.fieldEConnection, it.replaceFirstChar { c -> c.uppercase() })
        }
        report.telecom?.let { setFieldText(root, R.id.fieldTelecom, it) }
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
        bindField(
            root.findViewById(R.id.fieldElectricity),
            label = "Electricity Cable above land",
            hint = "Yes",
            iconRes = R.drawable.ic_inspection_field_electricity,
        )
        bindField(
            root.findViewById(R.id.fieldEConnection),
            label = "E-Connection to land",
            hint = "Yes",
            iconRes = R.drawable.ic_inspection_field_electricity,
        )
        bindField(
            root.findViewById(R.id.fieldTelecom),
            label = "Telecom",
            hint = "Yes",
            iconRes = R.drawable.ic_inspection_field_telecom,
        )
        bindField(
            root.findViewById(R.id.fieldRailway),
            label = "Railway Station Distance",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_railway,
            trailing = "K/m",
        )
        bindField(
            root.findViewById(R.id.fieldBus),
            label = "Bus Stop Distance",
            hint = "Enter Details",
            iconRes = R.drawable.ic_inspection_field_bus,
            trailing = "K/m",
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
    ) {
        fieldRoot.findViewById<TextView>(R.id.fieldLabel).text = label
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
            icon.background = null
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

    private fun inflateCompetitorEntry(container: LinearLayout) {
        val ctx = requireContext()
        val entry = LayoutInflater.from(ctx)
            .inflate(R.layout.component_competitor_entry, container, false)

        container.addView(entry)
        renumberCompetitors(container)

        entry.findViewById<View>(R.id.competitorDelete).setOnClickListener {
            container.removeView(entry)
            renumberCompetitors(container)
        }

        // Approval Type dropdown (None / CMDA / DTCP / Panchayat).
        val approvalRow = entry.findViewById<View>(R.id.dropdownApproval)
        val approvalLabel = entry.findViewById<TextView>(R.id.dropdownApprovalLabel)
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
        actualUnit.setOnClickListener {
            showTrailingDropdown(actualUnit, unitOptions) { picked ->
                actualUnitLabel.text = picked
            }
        }
        val finalUnit = entry.findViewById<View>(R.id.finalPriceUnit)
        val finalUnitLabel = entry.findViewById<TextView>(R.id.finalPriceUnitLabel)
        finalUnit.setOnClickListener {
            showTrailingDropdown(finalUnit, unitOptions) { picked ->
                finalUnitLabel.text = picked
            }
        }

        // Nested Amenities list — "+ Add Amenity" inflates a sub-entry
        // with its own delete button.
        val amenitiesList = entry.findViewById<LinearLayout>(R.id.amenitiesList)
        entry.findViewById<View>(R.id.btnAddAmenity).setOnClickListener {
            val amenity = LayoutInflater.from(ctx)
                .inflate(R.layout.component_competitor_amenity, amenitiesList, false)
            amenity.findViewById<View>(R.id.btnAmenityDelete).setOnClickListener {
                amenitiesList.removeView(amenity)
            }
            amenitiesList.addView(amenity)
        }
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
     */
    private fun bindAreaTab(root: View) {
        bindAreaCategory(
            root, R.id.createSchool, R.id.schoolEntries,
            R.drawable.ic_inspection_field_school, "Enter School Name",
        )
        bindAreaCategory(
            root, R.id.createCollege, R.id.collegeEntries,
            R.drawable.ic_inspection_field_college, "Enter College Name",
        )
        bindAreaCategory(
            root, R.id.createHospital, R.id.hospitalEntries,
            R.drawable.ic_inspection_field_hospital, "Enter Hospital Name",
        )
        bindAreaCategory(
            root, R.id.createMall, R.id.mallEntries,
            R.drawable.ic_inspection_field_mall, "Enter Mall Name",
        )
        bindAreaCategory(
            root, R.id.createMarket, R.id.marketEntries,
            R.drawable.ic_inspection_field_market, "Enter Market Name",
        )
    }

    private fun bindAreaCategory(
        root: View,
        createRowId: Int,
        entriesContainerId: Int,
        iconRes: Int,
        nameHint: String,
    ) {
        val createRow = root.findViewById<View>(createRowId)
        val entries = root.findViewById<LinearLayout>(entriesContainerId)
        createRow.setOnClickListener {
            val entry = LayoutInflater.from(requireContext())
                .inflate(R.layout.component_area_entry, entries, false)
            entry.findViewById<ImageView>(R.id.areaEntryNameIcon).setImageResource(iconRes)
            entry.findViewById<EditText>(R.id.areaEntryName).hint = nameHint
            entry.findViewById<View>(R.id.areaEntryDelete).setOnClickListener {
                entries.removeView(entry)
            }
            entries.addView(entry)
        }
    }

    companion object {
        private const val ARG_PROPERTY_ID = "property_id"
        private const val ARG_DISPLAY_TITLE = "display_title"

        fun newInstance(propertyId: String, displayTitle: String? = null): SiteInspectionBottomSheet =
            SiteInspectionBottomSheet().apply {
                arguments = bundleOf(
                    ARG_PROPERTY_ID to propertyId,
                    ARG_DISPLAY_TITLE to displayTitle,
                )
            }
    }
}
