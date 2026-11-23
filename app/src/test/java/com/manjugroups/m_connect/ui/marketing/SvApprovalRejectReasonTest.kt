package com.manjugroups.m_connect.ui.marketing

import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.UpdateDailyTaskStatusRequest
import com.manjugroups.m_connect.ui.common.RejectWithReasonBottomSheet
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** SV approval Reject needs a reason, like the web handoff dialog. */
class SvApprovalRejectReasonTest {

    @Test fun `blank or whitespace reasons are not accepted`() {
        assertNull(RejectWithReasonBottomSheet.requiredReason(null))
        assertNull(RejectWithReasonBottomSheet.requiredReason(""))
        assertNull(RejectWithReasonBottomSheet.requiredReason("   \n\t "))
        assertEquals("Client not ready", RejectWithReasonBottomSheet.requiredReason("  Client not ready \n"))
    }

    @Test fun `reject sends the reason and approve sends none`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true}"""))
            }

            api.updateDailyTaskStatus("Bearer local-fixture", UpdateDailyTaskStatusRequest("task-1", "cancelled", "Client not ready"))
            val reject = server.takeRequest()
            assertEquals("/api/dailyTasks/updateStatus", reject.path)
            val rejectJson = JsonParser.parseString(reject.body.readUtf8()).asJsonObject
            assertEquals("cancelled", rejectJson["status"].asString)
            assertEquals("Client not ready", rejectJson["reason"].asString)

            api.updateDailyTaskStatus("Bearer local-fixture", UpdateDailyTaskStatusRequest("task-1", "completed"))
            val approveJson = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("completed", approveJson["status"].asString)
            assertFalse(approveJson.has("reason"))
        } finally {
            server.shutdown()
        }
    }
}
