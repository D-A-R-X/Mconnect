package com.manjugroups.m_connect.ui.marketing.bookings

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Booking pricing / schedule calculation engine — a verbatim port of the web
 * `booking-new-page.tsx` derived values (§3 of the port spec), `lib/bookingGst`,
 * and the payable chain. Money uses [Math.round]-equivalent rounding
 * (`roundToLong`). Kept UI-free so it can be unit-tested and reused.
 *
 * The server (convex bookings.create) re-derives the immutable *AtBooking
 * snapshots and the EXCHANGE balance regardless, but the app must replicate
 * these for on-screen display and to send `agreedAmount` / `balanceAmount` /
 * charges for a non-exchange booking.
 */
object BookingCalc {

    fun num(value: String?): Double {
        val v = value?.trim()?.replace(",", "").orEmpty()
        val d = v.toDoubleOrNull() ?: 0.0
        return if (d.isFinite()) d else 0.0
    }

    private fun money(value: Double): Double = value.roundToLong().toDouble()

    /** agreedAmount = bookingCost − specialConsideration (undefined if no cost). */
    fun agreedAmount(bookingCost: String?, specialConsideration: String?): Double? {
        val cost = bookingCost?.trim()?.replace(",", "")
        if (cost.isNullOrBlank() || cost.toDoubleOrNull() == null) return null
        return num(bookingCost) - num(specialConsideration)
    }

    /**
     * GST (lib/bookingGst): round((agreedAmount − guidelineValue) × gstPercent/100),
     * only when the taxable base is positive.
     */
    fun gstAmount(agreedAmount: Double?, guidelineValue: Double, gstPercent: Double?): Double {
        val agreed = agreedAmount ?: 0.0
        val pct = gstPercent ?: 0.0
        val taxable = agreed - guidelineValue
        if (taxable <= 0 || pct <= 0) return 0.0
        return money(taxable * pct / 100.0)
    }

    /**
     * grossTotalPayableAmount = agreed + registration + (gst if applicable)
     *   + document + patta + (other if applicable).
     */
    fun grossTotalPayable(
        agreedAmount: Double?,
        registrationCharges: Double,
        gstApplicable: Boolean,
        gstAmount: Double,
        documentCharges: Double,
        pattaCharges: Double,
        otherChargesApplicable: Boolean,
        otherCharges: Double,
    ): Double {
        return (agreedAmount ?: 0.0) +
            registrationCharges +
            (if (gstApplicable) gstAmount else 0.0) +
            documentCharges +
            pattaCharges +
            (if (otherChargesApplicable) otherCharges else 0.0)
    }

    /** EXCHANGE only: max(gross − oldRegisteredValue, 0). */
    fun exchangeBalancePayable(gross: Double, exchangeOldRegisteredValue: Double): Double =
        max(gross - exchangeOldRegisteredValue, 0.0)

    /** totalPayable = exchange balance for EXCHANGE, else the gross total. */
    fun totalPayable(bookingType: String?, gross: Double, exchangeBalancePayable: Double): Double =
        if (bookingType == "EXCHANGE") exchangeBalancePayable else gross

    fun bankLoanAmount(customerPaymentCategory: String?, loanAmountRequested: Double): Double =
        if (customerPaymentCategory == "B") loanAmountRequested else 0.0

    data class PayableChain(
        val customerPayableAmount: Double,
        val customerBalanceAfterAdvance: Double,
        val balanceAmount: Double,
    )

    /**
     * customerPayable = max(totalPayable − bankLoan, 0)
     * customerBalanceAfterAdvance = max(customerPayable − advance − conversionCredit, 0)
     * balanceAmount = max(totalPayable − advance − conversionCredit, 0)
     */
    fun payableChain(
        totalPayable: Double,
        bankLoanAmount: Double,
        advanceAmount: Double,
        conversionCredit: Double,
    ): PayableChain {
        val customerPayable = max(totalPayable - bankLoanAmount, 0.0)
        val customerBalanceAfterAdvance =
            max(customerPayable - advanceAmount - conversionCredit, 0.0)
        val balanceAmount = max(totalPayable - advanceAmount - conversionCredit, 0.0)
        return PayableChain(customerPayable, customerBalanceAfterAdvance, balanceAmount)
    }

    /** Self-cash (category A) minimum allotment: max(configured − advance, 0). */
    fun minimumAllotmentAmount(
        customerPaymentCategory: String?,
        configuredAllotmentAmount: Double,
        advanceAmount: Double,
    ): Double = if (customerPaymentCategory == "A") {
        max(configuredAllotmentAmount - advanceAmount, 0.0)
    } else {
        configuredAllotmentAmount
    }

    /** outstanding after allotment = max(customerPayable − advance − conversion − allotment, 0). */
    fun outstandingAfterAllotment(
        customerPayableAmount: Double,
        advanceAmount: Double,
        conversionCredit: Double,
        allotmentDueAmount: Double,
    ): Double = max(
        customerPayableAmount - advanceAmount - conversionCredit - allotmentDueAmount,
        0.0,
    )

    // ── Balance payment schedule ────────────────────────────────────────────
    // Regular=30, Flexi=60, Special=180 days (PAYMENT_PLAN_DAYS).
    fun planDays(plan: String?): Int = when (plan) {
        "Regular" -> 30
        "Special" -> 180
        else -> 60 // Flexi (default)
    }

    data class StandardSchedule(
        val thirdPaymentAutoAmount: Double,
        val needsThirdPayment: Boolean,
        val remainingAfterThirdPayment: Double,
        val needsFourthPayment: Boolean,
    )

    /**
     * Standard 2nd/3rd/4th clamping (spec §3): the running total never exceeds
     * outstandingAfterAllotment.
     */
    fun standardSchedule(
        outstandingAfterAllotment: Double,
        secondPaymentAmount: Double,
        thirdPaymentAmount: Double,
    ): StandardSchedule {
        val paidBySecond = outstandingAfterAllotment > 0 &&
            secondPaymentAmount >= outstandingAfterAllotment
        val thirdAuto = max(outstandingAfterAllotment - secondPaymentAmount, 0.0)
        val needsThird = !paidBySecond && thirdAuto > 0
        val remainingAfterThird =
            max(outstandingAfterAllotment - secondPaymentAmount - thirdPaymentAmount, 0.0)
        val needsFourth = needsThird && thirdPaymentAmount > 0 && remainingAfterThird > 0
        return StandardSchedule(thirdAuto, needsThird, remainingAfterThird, needsFourth)
    }

    /** Sum of dynamic (Flexi / self-cash) rows. */
    fun paymentRowsTotal(rows: List<Double>): Double = rows.sum()

    /**
     * Self-cash balancing final row: the last row absorbs
     * max(outstanding − earlierTotal, 0) so the schedule always totals
     * exactly the outstanding amount (lib/booking-payment-schedule).
     */
    fun rebalanceFinalRow(outstanding: Double, earlierRowsTotal: Double): Double =
        max(outstanding - earlierRowsTotal, 0.0)
}
