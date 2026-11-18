package com.manjugroups.m_connect.ui.home

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.MapServiceApi
import com.manjugroups.m_connect.network.PincodeLookup
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.CoroutineScope

/**
 * "Booking Follow Up" reasons inside the SV outcome sheet's follow-up card
 * (outcome_body_postpone.xml → groupSvFollowUpReasons). Tick the reasons that
 * apply; each ticked reason shows its required fields. Rules live in
 * [SvFollowUpRules] (same as the server).
 */
class SvFollowUpForm(private val root: View, private val scope: CoroutineScope) {

    private data class Place(
        val label: String,
        val pincode: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val placeId: String? = null,
    )

    private val mapApi by lazy { MapServiceApi.create() }

    private val group: View? = root.findViewById(R.id.groupSvFollowUpReasons)

    /** reason key → (checkbox, detail block) */
    private val rows: Map<String, Pair<CheckBox?, View?>> = mapOf(
        "location" to (root.findViewById<CheckBox>(R.id.cbFuLocation) to root.findViewById(R.id.fuDetailLocation)),
        "distance" to (root.findViewById<CheckBox>(R.id.cbFuDistance) to root.findViewById(R.id.fuDetailDistance)),
        "budget" to (root.findViewById<CheckBox>(R.id.cbFuBudget) to root.findViewById(R.id.fuDetailBudget)),
        "loan_eligibility" to (root.findViewById<CheckBox>(R.id.cbFuLoan) to root.findViewById(R.id.fuDetailLoan)),
        "plot_not_available" to (root.findViewById<CheckBox>(R.id.cbFuPlot) to root.findViewById(R.id.fuDetailPlot)),
        "misguided_visit" to (root.findViewById<CheckBox>(R.id.cbFuMisguided) to root.findViewById(R.id.fuDetailMisguided)),
        "hospitality_service" to (root.findViewById<CheckBox>(R.id.cbFuHospitality) to root.findViewById(R.id.fuDetailHospitality)),
    )

    private val tvLocation: TextView? = root.findViewById(R.id.tvFuLocation)
    private val etDistance: EditText? = root.findViewById(R.id.etFuDistance)
    private val tvBudget: TextView? = root.findViewById(R.id.tvFuBudget)
    private val etHandCash: EditText? = root.findViewById(R.id.etFuHandCash)
    private val etPlotArea: EditText? = root.findViewById(R.id.etFuPlotArea)
    private val tvPlotBudget: TextView? = root.findViewById(R.id.tvFuPlotBudget)
    private val etMisguided: EditText? = root.findViewById(R.id.etFuMisguided)
    private val etHospitality: EditText? = root.findViewById(R.id.etFuHospitality)

    private val facingButtons: Map<String, RadioButton?> = mapOf(
        "east" to root.findViewById(R.id.rbFuEast),
        "west" to root.findViewById(R.id.rbFuWest),
        "north" to root.findViewById(R.id.rbFuNorth),
        "south" to root.findViewById(R.id.rbFuSouth),
        "corner" to root.findViewById(R.id.rbFuCorner),
        "parkview" to root.findViewById(R.id.rbFuParkview),
    )

    private var place: Place? = null
    private var preferredBudget: String? = null
    private var plotBudget: String? = null
    private var facing: String? = null

    init {
        rows.forEach { (_, row) ->
            val (cb, detail) = row
            cb?.setOnCheckedChangeListener { _, checked ->
                detail?.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        // GridLayout isn't a RadioGroup: keep the facings exclusive by hand.
        facingButtons.forEach { (key, button) ->
            button?.setOnClickListener {
                facing = key
                facingButtons.forEach { (k, b) -> b?.isChecked = k == key }
            }
        }
        root.findViewById<View>(R.id.rowFuLocation)?.setOnClickListener { pickLocation() }
        root.findViewById<View>(R.id.rowFuBudget)?.setOnClickListener {
            pickBudget("Preferred budget") { key, label ->
                preferredBudget = key
                tvBudget?.text = label
            }
        }
        root.findViewById<View>(R.id.rowFuPlotBudget)?.setOnClickListener {
            pickBudget("Budget for the plot") { key, label ->
                plotBudget = key
                tvPlotBudget?.text = label
            }
        }
        watchRemarks(etMisguided, root.findViewById(R.id.tvFuMisguidedCount))
        watchRemarks(etHospitality, root.findViewById(R.id.tvFuHospitalityCount))
    }

    fun setVisible(visible: Boolean) {
        group?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    val isVisible: Boolean get() = group?.visibility == View.VISIBLE

    fun draft(): SvFollowUpRules.Draft = SvFollowUpRules.Draft(
        reasons = SvFollowUpRules.ordered(rows.filter { it.value.first?.isChecked == true }.keys),
        locationLabel = place?.label.orEmpty(),
        locationPincode = place?.pincode,
        locationLat = place?.lat,
        locationLng = place?.lng,
        locationPlaceId = place?.placeId,
        distanceKm = etDistance?.text?.toString().orEmpty(),
        preferredBudget = preferredBudget,
        handCashAmount = etHandCash?.text?.toString().orEmpty(),
        plotFacing = facing,
        plotAreaSqft = etPlotArea?.text?.toString().orEmpty(),
        plotBudget = plotBudget,
        misguidedRemarks = etMisguided?.text?.toString().orEmpty(),
        hospitalityRemarks = etHospitality?.text?.toString().orEmpty(),
    )

    private fun pickBudget(title: String, onPicked: (String, String) -> Unit) {
        SearchableSelectionDialog.show(
            context = root.context,
            title = title,
            options = SvFollowUpRules.BUDGETS.map { SearchableOption(it, it.label) },
        ) { option -> onPicked(option.key, option.label) }
    }

    /**
     * Preferred location: type a 6-digit pincode (India Post, same lookup as
     * the booking form) or a place name (map-service suggestions, as in the
     * pin-drop pickers). The typed text itself is always offered too.
     */
    private fun pickLocation() {
        SearchableSelectionDialog.showRemote(
            context = root.context,
            scope = scope,
            title = "Preferred location",
            subtitle = "Type a pincode or an area name",
            searchHint = "Pincode or area name",
            minimumQueryLength = 3,
            idleMessage = "Type at least 3 characters",
            emptyMessage = "No matches. Check the spelling or type the pincode.",
            searchRequest = { query ->
                val q = query.trim()
                val results = mutableListOf<SearchableOption<Place>>()
                if (Regex("^\\d{6}$").matches(q)) {
                    PincodeLookup.lookup(q)?.let { p ->
                        val label = listOfNotNull(p.locality, p.district, p.state)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        if (label.isNotBlank()) {
                            results += SearchableOption(Place(label, pincode = q), label, "Pincode $q")
                        }
                    }
                    results += SearchableOption(Place(q, pincode = q), "Use pincode $q")
                } else {
                    runCatching { mapApi.searchAddress(q, 6).results }.getOrDefault(emptyList()).forEach { r ->
                        val label = r.displayName
                        if (label.isNotBlank()) {
                            results += SearchableOption(
                                Place(
                                    label = listOfNotNull(r.name, r.address).filter { it.isNotBlank() }.distinct().joinToString(", "),
                                    lat = r.location?.lat,
                                    lng = r.location?.lng,
                                    placeId = r.placeId,
                                ),
                                label,
                                r.address?.takeIf { it != label },
                            )
                        }
                    }
                    results += SearchableOption(Place(q), "Use \"$q\"")
                }
                results
            },
        ) { picked ->
            place = picked
            tvLocation?.text = picked.label
        }
    }

    private fun watchRemarks(edit: EditText?, counter: TextView?) {
        edit ?: return
        counter ?: return
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val length = SvFollowUpRules.normalizeRemarks(text).length
                val problem = if (text.isBlank()) null else SvFollowUpRules.remarksProblem(text)
                counter.text = if (problem != null) "$problem  ·  $length/${SvFollowUpRules.REMARKS_MAX}"
                else "$length/${SvFollowUpRules.REMARKS_MAX}"
                counter.setTextColor(Color.parseColor(if (problem != null) "#D92D20" else "#94A3B8"))
            }
        })
    }
}
