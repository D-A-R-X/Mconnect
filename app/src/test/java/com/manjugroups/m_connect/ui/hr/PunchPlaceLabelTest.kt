package com.manjugroups.m_connect.ui.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PunchPlaceLabelTest {

    @Test
    fun `area and city make a two part name`() {
        assertEquals(
            "Ekkattuthangal, Chennai",
            PunchPlaceLabel.shortName("Ekkattuthangal", "Chennai", "Chennai District"),
        )
    }

    @Test
    fun `city alone when there is no area`() {
        assertEquals("Chennai", PunchPlaceLabel.shortName(null, "Chennai", "Chennai"))
    }

    @Test
    fun `district stands in for a missing city`() {
        assertEquals(
            "Sriperumbudur, Kancheepuram",
            PunchPlaceLabel.shortName("Sriperumbudur", null, "Kancheepuram"),
        )
    }

    @Test
    fun `same area and city are not repeated`() {
        assertEquals("Coimbatore", PunchPlaceLabel.shortName("coimbatore", "Coimbatore", null))
    }

    @Test
    fun `postcodes plus codes and blanks are dropped`() {
        assertEquals("Chennai", PunchPlaceLabel.shortName("600032", "Chennai", null))
        assertEquals("Chennai", PunchPlaceLabel.shortName("2WX4+2M", "  Chennai ", null))
        assertNull(PunchPlaceLabel.shortName(" ", null, ""))
        assertNull(PunchPlaceLabel.shortName(null, null, null))
    }

    @Test
    fun `very long parts are shortened`() {
        val name = PunchPlaceLabel.shortName("A".repeat(50), "Chennai", null)!!
        assertEquals("A".repeat(31) + "…, Chennai", name)
    }

    @Test
    fun `overlay falls back to compact coordinates`() {
        assertEquals("13.04434, 80.19848", PunchPlaceLabel.overlayText(null, 13.044340, 80.198482))
        assertEquals("13.04434, 80.19848", PunchPlaceLabel.overlayText(" ", 13.044340, 80.198482))
        assertEquals("Location unavailable", PunchPlaceLabel.overlayText(null, null, 80.0))
        assertEquals("Guindy, Chennai", PunchPlaceLabel.overlayText("Guindy, Chennai", 13.0, 80.2))
    }
}
