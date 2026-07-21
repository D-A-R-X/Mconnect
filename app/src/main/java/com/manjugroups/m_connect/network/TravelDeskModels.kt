package com.manjugroups.m_connect.network

import com.google.gson.annotations.SerializedName

// Data classes for the travel-desk HTTP routes — split out from
// TravelDeskApi.kt so the Retrofit interface and the models are parsed
// independently (avoids a class-resolution quirk we hit when they were
// declared in the same file).

data class TravelDeskProject(
    @SerializedName("_id") val id: String,
    val name: String? = null,
)

data class TravelDeskVehicleRef(
    @SerializedName("_id") val id: String,
    val vehicleNumber: String? = null,
    val type: String? = null,
    val capacity: Int? = null,
)

data class TravelDeskTrip(
    // Nullable: Gson populates via unsafe allocation and will happily write
    // null into a non-null Kotlin field, so a payload whose shape differs
    // (e.g. a date-grouped wrapper) produced an id that was null despite the
    // type, and threw at the first non-null use. Callers must skip id-less rows.
    @SerializedName("_id") val id: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val pickupAddress: String? = null,
    val pickupTime: String? = null,
    val expectedAttendeeCount: Int? = null,
    val vehiclePreference: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val status: String? = null,
    val vehicleId: String? = null,
    val project: TravelDeskProject? = null,
    val vehicle: TravelDeskVehicleRef? = null,
)

/**
 * A trip as the agency's own driver sees it. Separate from
 * [TravelDeskTrip] because the driver payload carries the travel-desk
 * lifecycle timestamps and the derived `phase` that drives the action
 * button, which the agency-side list does not.
 */
data class TravelDeskDriverTrip(
    // Nullable for the same Gson reason as TravelDeskTrip — skip id-less rows.
    @SerializedName("_id") val id: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val pickupAddress: String? = null,
    val pickupTime: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val travelDeskArrivedAt: Long? = null,
    val travelDeskStartedAt: Long? = null,
    val travelDeskOnSiteAt: Long? = null,
    val travelDeskPickedFromSiteAt: Long? = null,
    val travelDeskEndedAt: Long? = null,
    val phase: String? = null,
    val canOperateToday: Boolean? = null,
    val project: TravelDeskProject? = null,
    val vehicle: TravelDeskVehicleRef? = null,
)

data class TravelDeskDriverTripsResponse(
    val success: Boolean = false,
    val trips: List<TravelDeskDriverTrip>? = null,
    val error: String? = null,
) {
    val rows: List<TravelDeskDriverTrip>
        get() = trips ?: emptyList()
}

data class TravelDeskDriverTripRequest(
    val siteVisitId: String,
)

data class TravelDeskVehicle(
    @SerializedName("_id") val id: String,
    val vehicleNumber: String? = null,
    val type: String? = null,
    val capacity: Int? = null,
    val defaultDriverName: String? = null,
    val defaultDriverPhone: String? = null,
    val status: String? = null,
)

data class TravelDeskTripsResponse(
    val success: Boolean = false,
    val data: List<TravelDeskTrip>? = null,
    val trips: List<TravelDeskTrip>? = null,
    val error: String? = null,
) {
    val rows: List<TravelDeskTrip>
        get() = trips ?: data ?: emptyList()
}

data class TravelDeskVehiclesResponse(
    val success: Boolean = false,
    val data: List<TravelDeskVehicle>? = null,
    val vehicles: List<TravelDeskVehicle>? = null,
    val error: String? = null,
) {
    val rows: List<TravelDeskVehicle>
        get() = vehicles ?: data ?: emptyList()
}

data class TravelDeskAllocateResponse(
    val success: Boolean = false,
    val error: String? = null,
)

data class AllocateTripRequest(
    val siteVisitId: String,
    val vehicleId: String,
    val pickupTime: String,
    val pricingMode: String,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val kmRate: Double? = null,
    val packageAmount: Double? = null,
)

// ── Vehicles create ─────────────────────────────────────────────────────

data class CreateVehicleRequest(
    val vehicleNumber: String,
    val type: String? = null,
    val capacity: Int? = null,
    val defaultDriverName: String? = null,
    val defaultDriverPhone: String? = null,
    // Optional override; the agency-scoped endpoint defaults to the caller's
    // own agency when omitted.
    val travelAgencyId: String? = null,
)

// ── Drivers ─────────────────────────────────────────────────────────────

data class TravelDeskDriver(
    @SerializedName("_id") val id: String,
    val name: String,
    // Nullable: the internal fleetDrivers table allows a driver with no phone,
    // and Gson writes null straight through a non-null declaration.
    val phone: String? = null,
    val address: String? = null,
    val status: String = "active",
)

data class TravelDeskDriversResponse(
    val success: Boolean = false,
    val drivers: List<TravelDeskDriver>? = null,
    val data: List<TravelDeskDriver>? = null,
    val error: String? = null,
) {
    val rows: List<TravelDeskDriver>
        get() = drivers ?: data ?: emptyList()
}

data class CreateDriverRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
)

data class UpdateDriverRequest(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    val address: String? = null,
)

data class SetDriverStatusRequest(
    val id: String,
    val status: String, // "active" | "inactive"
)

// ── Generic responses ──────────────────────────────────────────────────

data class TravelDeskCreateResponse(
    val success: Boolean = false,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val error: String? = null,
)

data class TravelDeskSimpleResponse(
    val success: Boolean = false,
    val error: String? = null,
)
