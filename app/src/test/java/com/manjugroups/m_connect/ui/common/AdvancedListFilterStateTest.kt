package com.manjugroups.m_connect.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedListFilterStateTest {

    @Test
    fun activeCount_countsEachSelectedOptionAndDateRangeOnce() {
        val state = AdvancedListFilterSheet.State(
            selected = mapOf(
                "status" to setOf("completed"),
                "staff" to setOf("staff-a", "staff-b"),
            ),
            fromDate = "2026-09-01",
            toDate = "2026-09-02",
        )

        assertEquals(4, state.activeCount())
    }

    @Test
    fun value_returnsSingleSelectionAndMissingCategoryIsEmpty() {
        val state = AdvancedListFilterSheet.State(
            selected = mapOf("status" to setOf("approved")),
        )

        assertEquals("approved", state.value("status"))
        assertEquals(emptySet<String>(), state.values("staff"))
        assertNull(state.value("staff"))
    }

    @Test
    fun emptyDateAndSelections_areNotActive() {
        val state = AdvancedListFilterSheet.State(fromDate = "", toDate = "")

        assertEquals(0, state.activeCount())
    }
}
