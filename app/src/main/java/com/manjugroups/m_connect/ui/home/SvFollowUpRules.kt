package com.manjugroups.m_connect.ui.home

/**
 * SV outcome "Booking Follow Up" (stored outcome follow_up): the counselling
 * person ticks the reasons that apply; each ticked reason requires its fields.
 *
 * Same keys, budgets, facings and remark rules as the server
 * (zeus convex/lib/svFollowUp.ts) and iOS (SvFollowUpRules.swift).
 * Pure logic so it is unit-tested.
 */
object SvFollowUpRules {

    data class Option(val key: String, val label: String)

    val REASONS = listOf(
        Option("location", "Location"),
        Option("distance", "Distance"),
        Option("budget", "Preferred Budget"),
        Option("loan_eligibility", "Loan Eligibility issue"),
        Option("plot_not_available", "Preferred Plot Not Available"),
        Option("misguided_visit", "Mis-guided Site Visit"),
        Option("hospitality_service", "Hospitality & Service Issue"),
    )

    val BUDGETS = listOf(
        Option("below_10l", "Below 10 Lacs"),
        Option("10_20l", "10 to 20 Lacs"),
        Option("20_40l", "20 to 40 Lacs"),
        Option("above_40l", "Above 40 Lacs"),
    )

    val FACINGS = listOf(
        Option("east", "East"),
        Option("west", "West"),
        Option("north", "North"),
        Option("south", "South"),
        Option("corner", "Corner"),
        Option("parkview", "Parkview"),
    )

    const val REMARKS_MIN = 250
    const val REMARKS_MAX = 500

    /** What the form holds (numbers as typed). */
    data class Draft(
        val reasons: List<String> = emptyList(),
        val locationLabel: String = "",
        val locationPincode: String? = null,
        val locationLat: Double? = null,
        val locationLng: Double? = null,
        val locationPlaceId: String? = null,
        val distanceKm: String = "",
        val preferredBudget: String? = null,
        val handCashAmount: String = "",
        val plotFacing: String? = null,
        val plotAreaSqft: String = "",
        val plotBudget: String? = null,
        val misguidedRemarks: String = "",
        val hospitalityRemarks: String = "",
    )

    fun normalizeRemarks(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /**
     * True when remarks are filler, not real text: one character dominating
     * ("xxxxx", "……"), a short unit repeated ("ababab", "asdasdasd"), mostly
     * non-letters, a long run of one character, or very few distinct words.
     */
    fun isRepetitiveText(input: String): Boolean {
        val text = normalizeRemarks(input).lowercase()
        if (text.isEmpty()) return false
        val compact = text.replace(" ", "")
        if (compact.isEmpty()) return true

        val letters = compact.count { it.isLetter() }
        if (letters.toDouble() / compact.length < 0.6) return true

        val counts = compact.groupingBy { it }.eachCount()
        val top = counts.values.max()
        if (top.toDouble() / compact.length > 0.4) return true
        if (counts.size <= 3 && compact.length >= 12) return true

        for (unit in 1..6) {
            if (compact.length < unit * 4) break
            val pattern = compact.substring(0, unit)
            var matched = 0
            var i = 0
            while (i + unit <= compact.length) {
                if (compact.regionMatches(i, pattern, 0, unit)) matched += unit
                i += unit
            }
            if (matched.toDouble() / compact.length > 0.7) return true
        }
        if (Regex("(.)\\1{5,}").containsMatchIn(compact)) return true

        val words = text.split(" ").filter { it.isNotEmpty() }
        if (words.size >= 8 && words.toSet().size.toDouble() / words.size < 0.35) return true
        return false
    }

    /** Staff-facing problem with a remarks text, or null when it's fine. */
    fun remarksProblem(input: String): String? {
        val text = normalizeRemarks(input)
        if (text.length < REMARKS_MIN) return "Write at least $REMARKS_MIN characters (${text.length} so far)."
        if (text.length > REMARKS_MAX) return "Keep it within $REMARKS_MAX characters (${text.length} now)."
        if (isRepetitiveText(text)) return "Write real remarks, not repeated characters."
        return null
    }

    private fun number(text: String): Double? = text.replace(",", "").trim().toDoubleOrNull()

    /** First problem, as the message to show, or null when the draft can be saved. */
    fun validate(d: Draft): String? {
        if (d.reasons.isEmpty()) return "Select at least one follow-up reason."
        for (reason in d.reasons) {
            when (reason) {
                "location" -> {
                    if (d.locationLabel.isBlank()) return "Enter the client's preferred location."
                    if (d.locationPincode != null && !d.locationPincode.matches(Regex("^\\d{6}$"))) {
                        return "Pincode must be 6 digits."
                    }
                }
                "distance" -> {
                    val km = number(d.distanceKm)
                    if (km == null || km <= 0 || km > 1000) return "Enter the expected distance in km."
                }
                "budget" -> if (BUDGETS.none { it.key == d.preferredBudget }) return "Choose the preferred budget."
                "loan_eligibility" -> {
                    val amount = number(d.handCashAmount)
                    if (amount == null || amount <= 0) return "Enter how much the client can afford as hand cash."
                }
                "plot_not_available" -> {
                    if (FACINGS.none { it.key == d.plotFacing }) return "Choose the expected facing."
                    val area = number(d.plotAreaSqft)
                    if (area == null || area <= 0) return "Enter the expected plot area."
                    if (BUDGETS.none { it.key == d.plotBudget }) return "Choose the budget for the plot."
                }
                "misguided_visit" -> remarksProblem(d.misguidedRemarks)?.let { return "Mis-guided Site Visit: $it" }
                "hospitality_service" -> remarksProblem(d.hospitalityRemarks)?.let { return "Hospitality & Service Issue: $it" }
                else -> return "Unknown follow-up reason \"$reason\"."
            }
        }
        return null
    }

    /** followUpDetails for the request: only the fields of ticked reasons. */
    fun details(d: Draft): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        val ticked = d.reasons.toSet()
        if ("location" in ticked) {
            out["location"] = linkedMapOf<String, Any>("label" to d.locationLabel.trim()).apply {
                d.locationPincode?.let { put("pincode", it) }
                d.locationLat?.let { put("lat", it) }
                d.locationLng?.let { put("lng", it) }
                d.locationPlaceId?.let { put("placeId", it) }
            }
        }
        if ("distance" in ticked) number(d.distanceKm)?.let { out["distanceKm"] = it }
        if ("budget" in ticked) d.preferredBudget?.let { out["preferredBudget"] = it }
        if ("loan_eligibility" in ticked) number(d.handCashAmount)?.let { out["handCashAmount"] = it }
        if ("plot_not_available" in ticked) {
            out["plot"] = mapOf(
                "facing" to d.plotFacing.orEmpty(),
                "areaSqft" to (number(d.plotAreaSqft) ?: 0.0),
                "budget" to d.plotBudget.orEmpty(),
            )
        }
        if ("misguided_visit" in ticked) out["misguidedRemarks"] = normalizeRemarks(d.misguidedRemarks)
        if ("hospitality_service" in ticked) out["hospitalityRemarks"] = normalizeRemarks(d.hospitalityRemarks)
        return out
    }

    /** Reasons in display order (so the server and web show them consistently). */
    fun ordered(reasons: Collection<String>): List<String> = REASONS.map { it.key }.filter { it in reasons }
}
