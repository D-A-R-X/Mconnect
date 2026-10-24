package com.manjugroups.m_connect.network

import com.google.gson.annotations.SerializedName

/**
 * A cab request (web Fleet > Cab Requests). Every field but the id is
 * nullable: one unexpected value must not fail the whole list parse.
 */
data class CabRequest(
    @SerializedName("_id") val id: String,
    val staffId: String? = null,
    val staffName: String? = null,
    val employeeId: String? = null,
    val purpose: String? = null,
    val fromLocation: String? = null,
    val toLocation: String? = null,
    /** YYYY-MM-DD */
    val requestedDate: String? = null,
    /** HH:mm */
    val requestedTime: String? = null,
    val passengerCount: Double? = null,
    val vehiclePreference: String? = null,
    val notes: String? = null,
    /** pending | approved | rejected | assigned | completed | cancelled */
    val status: String? = null,
    val reportingToId: String? = null,
    val reportingToName: String? = null,
    val approvedByName: String? = null,
    val rejectionReason: String? = null,
    val vehicleNumber: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val createdAt: Double? = null,
)

data class CabRequestListResponse(
    val success: Boolean = false,
    val requests: List<CabRequest>? = null,
    val error: String? = null,
)

data class CabRequestActionResponse(
    val success: Boolean = false,
    val error: String? = null,
)

data class CreateCabRequestBody(
    val purpose: String,
    val fromLocation: String,
    val toLocation: String,
    val requestedDate: String,
    val requestedTime: String? = null,
    val passengerCount: Int? = null,
    val notes: String? = null,
)

data class CabRequestIdBody(val id: String)

data class RejectCabRequestBody(val id: String, val reason: String)

data class AssignCabRequestBody(
    val id: String,
    val vehicleId: String,
    val driverName: String? = null,
    val driverPhone: String? = null,
)
