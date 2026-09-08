package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

class SelectableStaffIdsContractTest {
    @Test
    fun `selectable staff response parses all ids and total`() {
        val response = Gson().fromJson(
            """{
                "success": true,
                "total": 1658,
                "staffIds": ["staff-1", "staff-2"]
            }""".trimIndent(),
            SelectableStaffIdsResponse::class.java,
        )

        assertTrue(response.success)
        assertEquals(1658, response.total)
        assertEquals(listOf("staff-1", "staff-2"), response.staffIds)
    }

    @Test
    fun `selectable staff method uses route and every supported filter`() {
        val method = ApiService::class.java.methods.single { it.name == "getSelectableStaffIds" }

        assertEquals(
            "api/hr/staff/selectable-ids",
            method.getAnnotation(GET::class.java)?.value,
        )
        val queryNames = method.parameterAnnotations
            .flatMap { annotations -> annotations.filterIsInstance<Query>() }
            .map(Query::value)
        assertEquals(
            listOf("status", "role", "designation", "department", "query"),
            queryNames,
        )
    }
}
