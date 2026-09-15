package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.TodayVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row-view reuse for the CP Visits list.
 *
 * Reported as "page is crashing while scrolling". The list is a LinearLayout of
 * inflated cards, and the cache was keyed by object identity and invalidated
 * whenever `allVisits` was replaced — which loading another page always does,
 * because the append rebuilds the list via distinctBy + sortedWith. So every
 * scroll that fetched a page re-inflated the entire grown window on the main
 * thread: 20 cards, then 40, then 60. That is the same main-thread inflation
 * stall that previously presented as a crash on the Home visit cards.
 */
class CpRowCachePolicyTest {

    private fun visit(
        id: String = "fv_1",
        cpId: String? = "cp_1",
        status: String = "scheduled",
    ) = TodayVisit(
        id = id,
        clientPlaceId = "place_1",
        scheduledDate = "2026-09-15",
        status = status,
        clientPlaceVisitId = cpId,
    )

    @Test
    fun `a row keeps its key when the list is rebuilt around it`() {
        // distinctBy + sortedWith produce a new LIST, and may produce equal-but-
        // distinct instances. The key must not care.
        val first = visit()
        val second = visit()
        assertEquals(CpRowCachePolicy.keyOf(first), CpRowCachePolicy.keyOf(second))
    }

    @Test
    fun `the CP visit id is preferred, with the field visit id as fallback`() {
        assertEquals("cp_1", CpRowCachePolicy.keyOf(visit()))
        assertEquals("fv_9", CpRowCachePolicy.keyOf(visit(id = "fv_9", cpId = null)))
        // Blank must not become the key for every row without a CP id.
        assertEquals("fv_9", CpRowCachePolicy.keyOf(visit(id = "fv_9", cpId = "   ")))
    }

    @Test
    fun `an unchanged row is reused`() {
        // This is the fix: after loading page 2, page 1's rows must NOT be
        // rebuilt.
        assertTrue(CpRowCachePolicy.canReuse(visit(), visit()))
    }

    @Test
    fun `a row whose data changed is rebuilt`() {
        // Reuse is value-checked, so completing a visit still redraws its card.
        assertFalse(CpRowCachePolicy.canReuse(visit(status = "scheduled"), visit(status = "completed")))
    }

    @Test
    fun `nothing cached means build`() {
        assertFalse(CpRowCachePolicy.canReuse(null, visit()))
    }

    @Test
    fun `live keys cover exactly the matched rows`() {
        val matched = listOf(visit(cpId = "cp_1"), visit(cpId = "cp_2"), visit(cpId = "cp_3"))
        assertEquals(setOf("cp_1", "cp_2", "cp_3"), CpRowCachePolicy.liveKeys(matched))
    }

    @Test
    fun `live keys collapse duplicates rather than growing`() {
        val matched = listOf(visit(cpId = "cp_1"), visit(cpId = "cp_1"))
        assertEquals(setOf("cp_1"), CpRowCachePolicy.liveKeys(matched))
    }

    @Test
    fun `an empty match set evicts everything`() {
        // A filter that matches nothing must not leave the cache holding views.
        assertTrue(CpRowCachePolicy.liveKeys(emptyList()).isEmpty())
    }
}
