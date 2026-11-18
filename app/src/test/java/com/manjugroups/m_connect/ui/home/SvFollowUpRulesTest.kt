package com.manjugroups.m_connect.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvFollowUpRulesTest {

    private val real =
        "The client said the pickup driver reached the venue forty minutes late and did not answer calls. " +
            "At the site nobody offered water or a seat, and the brochure printouts were missing, so the family " +
            "left unhappy before seeing the plots they asked about. They want a call from the manager first."

    @Test
    fun repetitiveTextIsRejected() {
        listOf(
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "yyyyyyyyyyyy yyyyyyyy yyyyyyy",
            "abababababababababababababab",
            "asdasdasdasdasdasdasdasdasd",
            "......................................",
            "1234567890 1234567890 1234567890",
            "good good good good good good good good good good good",
        ).forEach { assertTrue(it, SvFollowUpRules.isRepetitiveText(it)) }
        assertFalse(SvFollowUpRules.isRepetitiveText(real))
    }

    @Test
    fun remarksLengthAndRepetition() {
        assertEquals("Write at least 250 characters (5 so far).", SvFollowUpRules.remarksProblem("short"))
        assertNull(SvFollowUpRules.remarksProblem("$real   \n  "))
        assertTrue(SvFollowUpRules.remarksProblem(real + real)!!.startsWith("Keep it within 500 characters"))
        assertEquals(
            "Write real remarks, not repeated characters.",
            SvFollowUpRules.remarksProblem("ab".repeat(150)),
        )
    }

    @Test
    fun eachTickedReasonNeedsItsFields() {
        val r = SvFollowUpRules
        assertEquals("Select at least one follow-up reason.", r.validate(SvFollowUpRules.Draft()))
        assertEquals("Enter the expected distance in km.", r.validate(SvFollowUpRules.Draft(reasons = listOf("distance"))))
        assertEquals(
            "Choose the budget for the plot.",
            r.validate(SvFollowUpRules.Draft(reasons = listOf("plot_not_available"), plotFacing = "east", plotAreaSqft = "1200")),
        )
        assertEquals(
            "Mis-guided Site Visit: Write real remarks, not repeated characters.",
            r.validate(SvFollowUpRules.Draft(reasons = listOf("misguided_visit"), misguidedRemarks = "x".repeat(300))),
        )
        assertNull(
            r.validate(
                SvFollowUpRules.Draft(
                    reasons = listOf("location", "budget", "loan_eligibility", "hospitality_service"),
                    locationLabel = "Kodambakkam, Chennai",
                    locationPincode = "600024",
                    preferredBudget = "20_40l",
                    handCashAmount = "2,50,000",
                    hospitalityRemarks = real,
                ),
            ),
        )
    }

    @Test
    fun detailsKeepOnlyTickedReasons() {
        val d = SvFollowUpRules.Draft(
            reasons = listOf("distance", "plot_not_available"),
            distanceKm = "12.5",
            preferredBudget = "below_10l",
            plotFacing = "corner",
            plotAreaSqft = "1200",
            plotBudget = "10_20l",
        )
        assertEquals(
            mapOf(
                "distanceKm" to 12.5,
                "plot" to mapOf("facing" to "corner", "areaSqft" to 1200.0, "budget" to "10_20l"),
            ),
            SvFollowUpRules.details(d),
        )
        assertEquals(listOf("location", "budget"), SvFollowUpRules.ordered(setOf("budget", "location")))
    }
}
