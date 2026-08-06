package com.manjugroups.m_connect.ui.marketing.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

class BookingCalcTest {

    @Test
    fun payableSchedule_deductsAdvanceAndAllotmentExactlyOnce() {
        val payable = BookingCalc.payableChain(
            totalPayable = 1_000_000.0,
            bankLoanAmount = 200_000.0,
            advanceAmount = 100_000.0,
            conversionCredit = 0.0,
        )

        assertEquals(800_000.0, payable.customerPayableAmount, 0.0)
        assertEquals(700_000.0, payable.customerBalanceAfterAdvance, 0.0)
        assertEquals(
            500_000.0,
            BookingCalc.outstandingAfterAllotment(
                customerPayableAmount = payable.customerPayableAmount,
                advanceAmount = 100_000.0,
                conversionCredit = 0.0,
                allotmentDueAmount = 200_000.0,
            ),
            0.0,
        )
    }

    @Test
    fun standardSchedule_usesOnlyTheOutstandingAfterAllotment() {
        val schedule = BookingCalc.standardSchedule(
            outstandingAfterAllotment = 500_000.0,
            secondPaymentAmount = 300_000.0,
            thirdPaymentAmount = 150_000.0,
        )

        assertEquals(200_000.0, schedule.thirdPaymentAutoAmount, 0.0)
        assertEquals(50_000.0, schedule.remainingAfterThirdPayment, 0.0)
        assertEquals(true, schedule.needsThirdPayment)
        assertEquals(true, schedule.needsFourthPayment)
    }

    @Test
    fun exchangeLoanAndConversionCredits_followWebDeductionOrder() {
        val gross = BookingCalc.grossTotalPayable(
            agreedAmount = 1_000_000.0,
            registrationCharges = 50_000.0,
            gstApplicable = true,
            gstAmount = 25_000.0,
            documentCharges = 10_000.0,
            pattaCharges = 5_000.0,
            otherChargesApplicable = true,
            otherCharges = 10_000.0,
        )
        val exchangeBalance = BookingCalc.exchangeBalancePayable(gross, 300_000.0)
        val total = BookingCalc.totalPayable("EXCHANGE", gross, exchangeBalance)
        val payable = BookingCalc.payableChain(
            totalPayable = total,
            bankLoanAmount = BookingCalc.bankLoanAmount("B", 200_000.0),
            advanceAmount = 50_000.0,
            conversionCredit = 40_000.0,
        )

        assertEquals(1_100_000.0, gross, 0.0)
        assertEquals(800_000.0, total, 0.0)
        assertEquals(600_000.0, payable.customerPayableAmount, 0.0)
        assertEquals(510_000.0, payable.customerBalanceAfterAdvance, 0.0)
        assertEquals(710_000.0, payable.balanceAmount, 0.0)
    }

    @Test
    fun standardSchedule_doesNotRequireUnusedRows() {
        val paidBySecond = BookingCalc.standardSchedule(
            outstandingAfterAllotment = 250_000.0,
            secondPaymentAmount = 250_000.0,
            thirdPaymentAmount = 0.0,
        )

        assertEquals(false, paidBySecond.needsThirdPayment)
        assertEquals(false, paidBySecond.needsFourthPayment)
        assertEquals(0.0, paidBySecond.remainingAfterThirdPayment, 0.0)
    }
}
