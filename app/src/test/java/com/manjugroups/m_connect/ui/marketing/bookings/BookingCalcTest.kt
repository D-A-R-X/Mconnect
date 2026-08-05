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
}
