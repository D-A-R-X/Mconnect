package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerParseTest {

    private val gson = Gson()

    // pendingExtensionRequest arrives as null / a Boolean (older backends) or
    // as the full extension-request object (newer backends). One object-shaped
    // value used to fail the whole list parse when the field was Boolean?.
    @Test
    fun taskList_parsesAllPendingExtensionRequestShapes() {
        val json = """
            {
              "success": true,
              "scope": "subtree",
              "teamIds": ["s1", "s2"],
              "tasks": [
                {"_id": "t1", "title": "Null shape", "status": "pending", "pendingExtensionRequest": null},
                {"_id": "t2", "title": "False shape", "status": "pending", "pendingExtensionRequest": false},
                {"_id": "t3", "title": "True shape", "status": "pending", "pendingExtensionRequest": true},
                {"_id": "t4", "title": "Object shape", "status": "pending", "pendingExtensionRequest": {
                  "_id": "ext1", "_creationTime": 1752470000000.0, "createdAt": 1752470000000,
                  "taskId": "t4", "status": "pending", "reason": "Need more time",
                  "oldDeadline": "2026-07-14", "requestedDeadline": "2026-07-20",
                  "requestedBy": "s9", "requestedByName": "Someone"
                }},
                {"_id": "t5", "title": "Field absent", "status": "pending"}
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, TaskManagerResponse::class.java)

        assertTrue(parsed.success)
        assertEquals(5, parsed.tasks.size)

        // Same truthy rule TaskManagerFragment uses for the Extension Requests
        // counter: pending when the value is neither null nor false.
        val pendingExtensions = parsed.tasks.count {
            it.pendingExtensionRequest.let { v -> v != null && v != false }
        }
        assertEquals(2, pendingExtensions)
    }
}
