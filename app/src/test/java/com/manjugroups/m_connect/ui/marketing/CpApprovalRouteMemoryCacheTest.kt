package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpApprovalRouteData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpApprovalRouteMemoryCacheTest {
    @Test
    fun `retains recent complete route and evicts least recently used entry`() {
        val cache = CpApprovalRouteMemoryCache(2)
        val first = CpApprovalRouteData(id = "first")
        val second = CpApprovalRouteData(id = "second")
        val third = CpApprovalRouteData(id = "third")

        cache.put("first", first)
        cache.put("second", second)
        assertEquals(first, cache.get("first"))
        cache.put("third", third)

        assertNull(cache.get("second"))
        assertEquals(first, cache.get("first"))
        assertEquals(third, cache.get("third"))
    }
}
