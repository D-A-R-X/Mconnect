package com.manjugroups.m_connect.ui.home

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ArrivalOtpRequestBody
import com.manjugroups.m_connect.network.ArrivalOtpVerifyBody
import com.manjugroups.m_connect.network.CreateCpVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.JointCpCompleteReviewRequest
import com.manjugroups.m_connect.network.JointCpLocationRequest
import com.manjugroups.m_connect.network.JointCpSubmitReviewRequest
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.StartVisitRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Exercises the complete Joint CP wire journey as two authenticated staff
 * members without creating any production data. MockWebServer receives the
 * real Retrofit requests and returns the same envelopes the deployed API uses.
 */
class JointCpApiJourneyTest {
    private lateinit var server: MockWebServer
    private lateinit var geoApi: GeoTrackApi
    private lateinit var storageApi: ApiService
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        geoApi = retrofit.create(GeoTrackApi::class.java)
        storageApi = retrofit.create(ApiService::class.java)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `joint cp completes from creation through both participant counts`() = runBlocking {
        enqueue(
            """{
                "success":true,
                "id":"cp-local",
                "requestId":"create-joint-local",
                "fieldVisitId":"field-low",
                "cpType":"joint_cp",
                "jointCpCategory":"booking_cp"
            }""",
        )
        val created = geoApi.createCpVisit(
            OWNER_TOKEN,
            "create-joint-local",
            CreateCpVisitRequest(
                clientName = "Disposable Contract Client",
                mobileNumber = "9000000000",
                assignedStaffId = OWNER_ID,
                lmoStaffId = "staff-lmo",
                scheduledDate = ACTIVITY_DATE,
                scheduledTime = "10:30",
                visitAddress = "Local mock address",
                visitLat = OWNER_LAT,
                visitLng = OWNER_LNG,
                projectId = "project-local",
                cpType = "joint_cp",
                jointCpCategory = "booking_cp",
                jointStaffIds = listOf(OWNER_ID, REVIEWER_ID),
            ),
        )
        assertTrue(created.success)
        assertEquals(CP_ID, created.id)
        take("POST", "/api/marketing/clientPlaceVisits/create", OWNER_TOKEN).also { request ->
            assertEquals("create-joint-local", request.getHeader("Idempotency-Key"))
            request.jsonBody().also { body ->
                assertEquals("joint_cp", body.string("cpType"))
                assertEquals(OWNER_ID, body.string("assignedStaffId"))
                assertEquals(
                    listOf(OWNER_ID, REVIEWER_ID),
                    body.getAsJsonArray("jointStaffIds").map { it.asString },
                )
            }
        }

        enqueue(workflowJson(actorRole = "outcome_owner", state = "scheduled"))
        val ownerInitial = geoApi.getJointCpWorkflow(OWNER_TOKEN, CP_ID)
        assertEquals("outcome_owner", ownerInitial.workflow?.actorRole)
        assertEquals("field-low", ownerInitial.visit?.joint?.participants?.first()?.fieldVisitId)
        take("GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=cp-local", OWNER_TOKEN)

        enqueue(workflowJson(actorRole = "reviewer", state = "scheduled"))
        val reviewerInitial = geoApi.getJointCpWorkflow(REVIEWER_TOKEN, CP_ID)
        assertEquals("reviewer", reviewerInitial.workflow?.actorRole)
        assertEquals("field-high", reviewerInitial.visit?.joint?.participants?.last()?.fieldVisitId)
        take("GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=cp-local", REVIEWER_TOKEN)

        enqueue("""{"success":true,"status":"in_progress"}""")
        assertTrue(
            geoApi.startVisit(
                OWNER_TOKEN,
                StartVisitRequest("field-low", OWNER_LAT, OWNER_LNG),
            ).success,
        )
        take("POST", "/api/geotrack/visit/start", OWNER_TOKEN).jsonBody().also { body ->
            assertEquals("field-low", body.string("visitId"))
        }

        enqueue("""{"success":true,"status":"in_progress"}""")
        assertTrue(
            geoApi.startVisit(
                REVIEWER_TOKEN,
                StartVisitRequest("field-high", REVIEWER_LAT, REVIEWER_LNG),
            ).success,
        )
        take("POST", "/api/geotrack/visit/start", REVIEWER_TOKEN).jsonBody().also { body ->
            assertEquals("field-high", body.string("visitId"))
        }

        enqueue(
            workflowJson(
                actorRole = "reviewer",
                state = "in_progress",
                actorReady = true,
                withinRadius = true,
            ),
        )
        val ready = geoApi.markJointCpParticipantReady(
            REVIEWER_TOKEN,
            JointCpLocationRequest(
                id = CP_ID,
                fieldVisitId = "field-high",
                lat = REVIEWER_LAT,
                lng = REVIEWER_LNG,
                accuracyMeters = 6f,
                capturedAt = CAPTURED_AT,
            ),
        )
        assertTrue(ready.workflow?.actorReady == true)
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/joint-participant-ready",
            REVIEWER_TOKEN,
        ).jsonBody().also { body ->
            assertEquals("field-high", body.string("fieldVisitId"))
            assertEquals(CAPTURED_AT, body.get("capturedAt").asLong)
        }

        enqueue(
            workflowJson(
                actorRole = "outcome_owner",
                state = "in_progress",
                actorReady = true,
                withinRadius = true,
                canRequestOtp = true,
            ),
        )
        val preflight = geoApi.preflightJointCpArrival(
            OWNER_TOKEN,
            JointCpLocationRequest(
                id = CP_ID,
                fieldVisitId = "field-low",
                lat = OWNER_LAT,
                lng = OWNER_LNG,
                accuracyMeters = 5f,
                capturedAt = CAPTURED_AT,
            ),
        )
        assertTrue(preflight.workflow?.isWithinCompletionRadius == true)
        assertTrue(preflight.workflow?.canRequestOtp == true)
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/joint-arrival-preflight",
            OWNER_TOKEN,
        ).jsonBody().also { body ->
            assertEquals("field-low", body.string("fieldVisitId"))
        }

        enqueue(
            """{
                "success":true,
                "contactPhoneMasked":"******0000",
                "distance":5,
                "radius":50
            }""",
        )
        val otpRequest = geoApi.requestArrivalOtp(
            OWNER_TOKEN,
            ArrivalOtpRequestBody("field-low", OWNER_LAT, OWNER_LNG),
        )
        assertTrue(otpRequest.success)
        assertEquals(50, otpRequest.radius)
        take("POST", "/api/geotrack/visit/arrival-otp/request", OWNER_TOKEN).jsonBody().also { body ->
            assertEquals("field-low", body.string("visitId"))
        }

        enqueue("""{"success":true,"storageId":"storage-arrival-local"}""")
        val photo = storageApi.uploadStorageFile(
            OWNER_TOKEN,
            "mobile.generic",
            "joint-cp-arrival.jpg",
            byteArrayOf(1, 2, 3, 4).toRequestBody("image/jpeg".toMediaType()),
        )
        assertTrue(photo.success)
        assertEquals("storage-arrival-local", photo.storageId)
        take("POST", "/api/storage/upload", OWNER_TOKEN).also { request ->
            assertEquals("mobile.generic", request.getHeader("X-Storage-Purpose"))
            assertEquals("joint-cp-arrival.jpg", request.getHeader("X-File-Name"))
            assertEquals("image/jpeg", request.getHeader("Content-Type"))
            assertEquals(4L, request.bodySize)
        }

        enqueue("""{"success":true,"arrivalDistanceFromPlaceMeters":5}""")
        val otpVerify = geoApi.verifyArrivalOtp(
            OWNER_TOKEN,
            ArrivalOtpVerifyBody(
                visitId = "field-low",
                otp = "123456",
                lat = OWNER_LAT,
                lng = OWNER_LNG,
                arrivalPhotoStorageId = "storage-arrival-local",
            ),
        )
        assertTrue(otpVerify.success)
        take("POST", "/api/geotrack/visit/arrival-otp/verify", OWNER_TOKEN).jsonBody().also { body ->
            assertEquals("field-low", body.string("visitId"))
            assertEquals("123456", body.string("otp"))
            assertEquals("storage-arrival-local", body.string("arrivalPhotoStorageId"))
        }

        enqueue("""{"success":true,"status":"in_progress"}""")
        assertTrue(
            geoApi.markClientMet(
                OWNER_TOKEN,
                MarkClientMetRequest(id = CP_ID, clientMet = true),
            ).success,
        )
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/markClientMet",
            OWNER_TOKEN,
        ).jsonBody().also { body ->
            assertEquals(CP_ID, body.string("id"))
            assertTrue(body.get("clientMet").asBoolean)
        }

        enqueue(
            """{
                "success":true,
                "status":"outcome_submitted",
                "outcome":"interested",
                "visit":{"_id":"cp-local","status":"outcome_submitted","outcome":"interested"}
            }""",
        )
        enqueue(
            workflowJson(
                actorRole = "outcome_owner",
                state = "outcome_submitted",
                actorReady = true,
                withinRadius = true,
                outcome = "interested",
                revision = 1,
                canSubmitOutcome = true,
            ),
        )
        val savedOwnerOutcome = geoApi.setCpVisitOutcomeConfirmed(
            token = OWNER_TOKEN,
            request = SetOutcomeRequest(
                id = CP_ID,
                outcome = "interested",
                notes = "Owner discussed the project",
                arrivalPhotoStorageId = "storage-arrival-local",
            ),
            actingStaffId = OWNER_ID,
            jointCp = true,
        )
        assertTrue(savedOwnerOutcome.success)
        take("POST", "/api/marketing/clientPlaceVisits/setOutcome", OWNER_TOKEN).jsonBody().also { body ->
            assertEquals(OWNER_ID, body.string("actingStaffId"))
            assertEquals("interested", body.string("outcome"))
        }
        take("GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=cp-local", OWNER_TOKEN)

        enqueue(
            workflowJson(
                actorRole = "outcome_owner",
                state = "pending_review",
                actorReady = true,
                withinRadius = true,
                outcome = "interested",
                revision = 1,
            ),
        )
        val submitted = geoApi.submitJointCpReview(
            OWNER_TOKEN,
            "submit-review-local",
            JointCpSubmitReviewRequest(
                id = CP_ID,
                fieldVisitId = "field-low",
                lat = OWNER_LAT,
                lng = OWNER_LNG,
                accuracyMeters = 5f,
                capturedAt = CAPTURED_AT,
                arrivalPhotoStorageId = "storage-arrival-local",
                expectedOutcomeRevision = 1,
            ),
        )
        assertEquals("pending_review", submitted.workflow?.state)
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/joint-submit-review",
            OWNER_TOKEN,
        ).also { request ->
            assertEquals("submit-review-local", request.getHeader("Idempotency-Key"))
            assertEquals(1L, request.jsonBody().get("expectedOutcomeRevision").asLong)
        }

        enqueue(
            workflowJson(
                actorRole = "reviewer",
                state = "pending_review",
                actorReady = true,
                withinRadius = true,
                outcome = "interested",
                revision = 1,
                canReview = true,
                canCompleteReview = true,
            ),
        )
        val reviewerReview = geoApi.getJointCpWorkflow(REVIEWER_TOKEN, CP_ID)
        assertTrue(reviewerReview.workflow?.canReview == true)
        assertEquals(1L, reviewerReview.workflow?.outcomeRevision)
        take("GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=cp-local", REVIEWER_TOKEN)

        enqueue(
            """{
                "success":true,
                "status":"pending_review",
                "outcome":"follow_up",
                "visit":{"_id":"cp-local","status":"pending_review","outcome":"follow_up"}
            }""",
        )
        enqueue(
            workflowJson(
                actorRole = "reviewer",
                state = "pending_review",
                actorReady = true,
                withinRadius = true,
                outcome = "follow_up",
                revision = 2,
                canReview = true,
                canCompleteReview = true,
            ),
        )
        val reviewerEdit = geoApi.setCpVisitOutcomeConfirmed(
            token = REVIEWER_TOKEN,
            request = SetOutcomeRequest(
                id = CP_ID,
                outcome = "follow_up",
                notes = "Reviewer updated the agreed follow-up",
                followUpDate = "2099-01-02",
                followUpTime = "11:00",
            ),
            actingStaffId = REVIEWER_ID,
            jointCp = true,
        )
        assertTrue(reviewerEdit.success)
        take("POST", "/api/marketing/clientPlaceVisits/setOutcome", REVIEWER_TOKEN).jsonBody().also { body ->
            assertEquals(REVIEWER_ID, body.string("actingStaffId"))
            assertEquals("follow_up", body.string("outcome"))
        }
        take("GET", "/api/marketing/clientPlaceVisits/joint-workflow?id=cp-local", REVIEWER_TOKEN)

        enqueue(
            workflowJson(
                actorRole = "reviewer",
                state = "pending_review",
                actorReady = true,
                withinRadius = true,
                outcome = "follow_up",
                revision = 2,
                canReview = true,
                canCompleteReview = true,
            ),
        )
        val latest = geoApi.markJointCpParticipantReady(
            REVIEWER_TOKEN,
            JointCpLocationRequest(
                id = CP_ID,
                fieldVisitId = "field-high",
                lat = REVIEWER_LAT,
                lng = REVIEWER_LNG,
                accuracyMeters = 4f,
                capturedAt = CAPTURED_AT + 60_000,
            ),
        )
        assertEquals(2L, latest.workflow?.outcomeRevision)
        assertTrue(latest.workflow?.isWithinCompletionRadius == true)
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/joint-participant-ready",
            REVIEWER_TOKEN,
        ).jsonBody().also { body ->
            assertEquals(CAPTURED_AT + 60_000, body.get("capturedAt").asLong)
        }

        enqueue(completedWorkflowJson())
        val completed = geoApi.completeJointCpReview(
            REVIEWER_TOKEN,
            "complete-review-local",
            JointCpCompleteReviewRequest(
                id = CP_ID,
                expectedOutcomeRevision = 2,
                reviewerRemark = "Reviewed with both staff and client",
            ),
        )
        assertTrue(completed.success)
        assertEquals("completed", completed.workflow?.state)
        assertEquals(setOf(OWNER_ID, REVIEWER_ID), completed.creditedStaffIds?.toSet())
        take(
            "POST",
            "/api/marketing/clientPlaceVisits/joint-complete-review",
            REVIEWER_TOKEN,
        ).also { request ->
            assertEquals("complete-review-local", request.getHeader("Idempotency-Key"))
            request.jsonBody().also { body ->
                assertEquals(2L, body.get("expectedOutcomeRevision").asLong)
                assertEquals("Reviewed with both staff and client", body.string("reviewerRemark"))
            }
        }

        enqueue(completedDetailJson())
        val ownerDetail = geoApi.getCpVisitDetail(OWNER_TOKEN, CP_ID)
        assertEquals("completed", ownerDetail.visit?.effectiveStatus)
        assertTrue(ownerDetail.visit?.joint?.participants.orEmpty().all { it.status == "completed" })
        take("GET", "/api/marketing/clientPlaceVisits/get?id=cp-local", OWNER_TOKEN)

        enqueue(completedDetailJson())
        val reviewerDetail = geoApi.getCpVisitDetail(REVIEWER_TOKEN, CP_ID)
        assertEquals("completed", reviewerDetail.visit?.effectiveStatus)
        assertEquals("Reviewed with both staff and client", reviewerDetail.visit?.joint?.workflow?.reviewerRemark)
        take("GET", "/api/marketing/clientPlaceVisits/get?id=cp-local", REVIEWER_TOKEN)

        enqueue(completedCountJson(OWNER_ID))
        val ownerCount = geoApi.getCompletedCpCount(OWNER_TOKEN, ACTIVITY_DATE, OWNER_ID)
        assertEquals(1, ownerCount.completedCount)
        assertTrue(CP_ID in ownerCount.safeVisitIds)
        take(
            "GET",
            "/api/marketing/clientPlaceVisits/completed-count?date=$ACTIVITY_DATE&staffId=$OWNER_ID",
            OWNER_TOKEN,
        )

        enqueue(completedCountJson(REVIEWER_ID))
        val reviewerCount = geoApi.getCompletedCpCount(REVIEWER_TOKEN, ACTIVITY_DATE, REVIEWER_ID)
        assertEquals(1, reviewerCount.completedCount)
        assertTrue(CP_ID in reviewerCount.safeVisitIds)
        take(
            "GET",
            "/api/marketing/clientPlaceVisits/completed-count?date=$ACTIVITY_DATE&staffId=$REVIEWER_ID",
            REVIEWER_TOKEN,
        )

        assertEquals(23, requests.size)
        assertTrue(
            requests.filter { it.requestUrl?.encodedPath?.contains("arrival-otp") == true }
                .all { it.getHeader("Authorization") == OWNER_TOKEN },
        )
        assertFalse(
            requests.any {
                it.requestUrl?.encodedPath?.endsWith("joint-complete-review") == true &&
                    it.getHeader("Authorization") != REVIEWER_TOKEN
            },
        )
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent()),
        )
    }

    private fun take(method: String, path: String, token: String): RecordedRequest {
        val request = server.takeRequest()
        assertNotNull(request)
        requests += request
        assertEquals(method, request.method)
        assertEquals(path, request.path)
        assertEquals(token, request.getHeader("Authorization"))
        return request
    }

    private fun RecordedRequest.jsonBody(): JsonObject =
        JsonParser.parseString(body.readUtf8()).asJsonObject

    private fun JsonObject.string(name: String): String = get(name).asString

    private fun workflowJson(
        actorRole: String,
        state: String,
        actorReady: Boolean = false,
        withinRadius: Boolean = false,
        outcome: String? = null,
        revision: Long? = null,
        canRequestOtp: Boolean = false,
        canSubmitOutcome: Boolean = false,
        canReview: Boolean = false,
        canCompleteReview: Boolean = false,
    ): String = """{
        "success":true,
        "visit":{
            "_id":"cp-local",
            "assignedStaffId":"staff-low",
            "activityDate":"$ACTIVITY_DATE",
            "status":"$state",
            "effectiveStatus":"$state",
            "cpType":"joint_cp",
            "joint":{
                "participants":[
                    {
                        "staffId":"staff-low",
                        "staffName":"Lower Designation",
                        "fieldVisitId":"field-low",
                        "templateLevel":3,
                        "workflowRole":"outcome_owner",
                        "status":"${if (state == "scheduled") "scheduled" else "in_progress"}"
                    },
                    {
                        "staffId":"staff-high",
                        "staffName":"Higher Designation",
                        "fieldVisitId":"field-high",
                        "templateLevel":7,
                        "workflowRole":"reviewer",
                        "status":"${if (state == "scheduled") "scheduled" else "in_progress"}"
                    }
                ]
            }
        },
        "workflow":{
            "state":"$state",
            "actorRole":"$actorRole",
            "outcomeOwnerStaffId":"staff-low",
            "outcomeOwnerName":"Lower Designation",
            "reviewerStaffId":"staff-high",
            "reviewerName":"Higher Designation",
            "reviewerTemplateName":"Senior Manager",
            "canRequestOtp":$canRequestOtp,
            "canSubmitOutcome":$canSubmitOutcome,
            "canReview":$canReview,
            "canCompleteReview":$canCompleteReview,
            "actorReady":$actorReady,
            "separationMeters":4.7,
            "isWithinCompletionRadius":$withinRadius,
            "requiredRadiusMeters":50.0,
            "outcome":${outcome?.let { "\"$it\"" } ?: "null"},
            "outcomeRevision":${revision ?: "null"}
        }
    }"""

    private fun completedWorkflowJson(): String = """{
        "success":true,
        "visit":{
            "_id":"cp-local",
            "activityDate":"$ACTIVITY_DATE",
            "status":"completed",
            "effectiveStatus":"completed",
            "outcome":"follow_up",
            "cpType":"joint_cp"
        },
        "workflow":{
            "state":"completed",
            "actorRole":"reviewer",
            "outcomeOwnerStaffId":"staff-low",
            "reviewerStaffId":"staff-high",
            "outcome":"follow_up",
            "outcomeRevision":2,
            "reviewerRemark":"Reviewed with both staff and client",
            "creditedStaffIds":["staff-low","staff-high"],
            "completedAt":4070908800000
        },
        "creditedStaffIds":["staff-low","staff-high"]
    }"""

    private fun completedDetailJson(): String = """{
        "success":true,
        "visit":{
            "_id":"cp-local",
            "activityDate":"$ACTIVITY_DATE",
            "status":"completed",
            "effectiveStatus":"completed",
            "outcome":"follow_up",
            "cpType":"joint_cp",
            "completedAt":4070908800000,
            "joint":{
                "participants":[
                    {
                        "staffId":"staff-low",
                        "staffName":"Lower Designation",
                        "fieldVisitId":"field-low",
                        "workflowRole":"outcome_owner",
                        "status":"completed",
                        "completedAt":4070908800000
                    },
                    {
                        "staffId":"staff-high",
                        "staffName":"Higher Designation",
                        "fieldVisitId":"field-high",
                        "workflowRole":"reviewer",
                        "status":"completed",
                        "completedAt":4070908800000
                    }
                ],
                "workflow":{
                    "state":"completed",
                    "outcomeOwnerStaffId":"staff-low",
                    "reviewerStaffId":"staff-high",
                    "outcome":"follow_up",
                    "outcomeRevision":2,
                    "reviewerRemark":"Reviewed with both staff and client",
                    "creditedStaffIds":["staff-low","staff-high"]
                }
            }
        }
    }"""

    private fun completedCountJson(staffId: String): String = """{
        "success":true,
        "date":"$ACTIVITY_DATE",
        "staffId":"$staffId",
        "completedCount":1,
        "visitIds":["cp-local"]
    }"""

    private companion object {
        const val CP_ID = "cp-local"
        const val OWNER_ID = "staff-low"
        const val REVIEWER_ID = "staff-high"
        const val OWNER_TOKEN = "Bearer owner-session"
        const val REVIEWER_TOKEN = "Bearer reviewer-session"
        const val ACTIVITY_DATE = "2099-01-01"
        const val OWNER_LAT = 13.082700
        const val OWNER_LNG = 80.270700
        const val REVIEWER_LAT = 13.082730
        const val REVIEWER_LNG = 80.270730
        const val CAPTURED_AT = 4_070_865_600_000L
    }
}
