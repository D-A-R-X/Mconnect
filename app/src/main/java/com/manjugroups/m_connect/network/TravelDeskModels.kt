package com.manjugroups.m_connect.network

import com.google.gson.annotations.SerializedName

// Data classes for the travel-desk HTTP routes — split out from
// TravelDeskApi.kt so the Retrofit interface and the models are parsed
// independently (avoids a class-resolution quirk we hit when they were
// declared in the same file).

// ── Auth ────────────────────────────────────────────────────────────────
data class TravelDeskSendOtpRequest(
    val phone: String,
)

data class TravelDeskSendOtpResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

data class TravelDeskVerifyOtpRequest(
    val phone: String,
    val otp: String,
)

data class TravelDeskPushRegisterRequest(
    val pushToken: String,
    val platform: String = "android",
)

data class TravelDeskAuthUser(
    @SerializedName("_id") val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val address: String? = null,
    /** "driver" or "agency". Agencies use the travel-desk web, not the app. */
    val role: String? = null,
)

data class TravelDeskVerifyOtpResponse(
    val success: Boolean = false,
    val token: String? = null,
    val user: TravelDeskAuthUser? = null,
    val error: String? = null,
)

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
    // Client identity resolved server-side (lead → CP client → place). Shown as
    // the card's primary line; falls back to "Unknown" on the card when null.
    // NOTE: clientName is only populated once the newer backend is deployed; the
    // `lead` object below is already live on prod (enrichVisit has long returned
    // it), so the card falls back to lead.contactName — the same field the web
    // fleet page reads — until clientName ships.
    val clientName: String? = null,
    val clientPhone: String? = null,
    val lead: TravelDeskLead? = null,
    // LMO — the telecaller who created the visit. Resolved server-side in the
    // trips payload; shown on the admin fleet card in place of the vehicle tag.
    val lmoName: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val status: String? = null,
    val vehicleId: String? = null,
    val project: TravelDeskProject? = null,
    val vehicle: TravelDeskVehicleRef? = null,
    // Present once a trip is allocated — lets the admin track WHO it went to.
    // travelAgency is set for external-agency allocations; absent for the
    // in-house MMS fleet. The travelDesk* stamps drive the progress pill.
    val travelAgency: TravelDeskAgencyRef? = null,
    val travelDeskArrivedAt: Long? = null,
    val travelDeskStartedAt: Long? = null,
    val travelDeskOnSiteAt: Long? = null,
    val travelDeskPickedFromSiteAt: Long? = null,
    val travelDeskEndedAt: Long? = null,
    // Odometer readings + dashboard photos the driver captured at start/end.
    val travelDeskStartKm: Double? = null,
    val travelDeskEndKm: Double? = null,
    val travelDeskStartPhotoIds: List<String> = emptyList(),
    val travelDeskEndPhotoIds: List<String> = emptyList(),
)

data class TravelDeskAgencyRef(
    @SerializedName("_id") val id: String? = null,
    val name: String? = null,
)

/** The lead a site visit is linked to. enrichVisit returns this inline; the
 *  fleet card reads contactName as the client-name fallback (matches web). */
data class TravelDeskLead(
    val contactName: String? = null,
    val mobileNumber: String? = null,
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

data class TravelDeskStartTripRequest(
    val siteVisitId: String,
    /** Fixed client OTP on this backend ("0000"). */
    val otp: String,
    val photoIds: List<String>,
    val startKm: Double? = null,
)

data class TravelDeskEndTripRequest(
    val siteVisitId: String,
    val photoIds: List<String>,
    val endKm: Double? = null,
)

data class TravelDeskStorageResponse(
    val success: Boolean = false,
    val storageId: String? = null,
    val error: String? = null,
)

data class TravelDeskVehicle(
    @SerializedName("_id") val id: String,
    val vehicleNumber: String? = null,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Int? = null,
    val type: String? = null,
    val capacity: Int? = null,
    val defaultDriverName: String? = null,
    val defaultDriverPhone: String? = null,
    val defaultDriverWhatsapp: String? = null,
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

/** External travel agencies the fleet admin can allot a trip to. */
data class TravelDeskAgency(
    @SerializedName("_id") val id: String? = null,
    val name: String? = null,
    val status: String? = null,
    val mobileNumber: String? = null,
)

data class TravelDeskAgenciesResponse(
    val success: Boolean = false,
    val agencies: List<TravelDeskAgency>? = null,
    val data: List<TravelDeskAgency>? = null,
    val error: String? = null,
) {
    val rows: List<TravelDeskAgency>
        get() = agencies ?: data ?: emptyList()
}

data class AllotAgencyRequest(
    val siteVisitId: String,
    val travelAgencyId: String,
)

data class AllocateTripRequest(
    val siteVisitId: String,
    val vehicleId: String,
    val pickupTime: String,
    // Per-trip pricing is no longer captured in the app (the web MFPL assign
    // has none, and the allocate route ignores these) — kept optional with
    // defaults so the request shape stays stable for any agency consumer.
    val pricingMode: String = "km",
    val driverName: String? = null,
    val driverPhone: String? = null,
    // The exact roster driver, when picked from the dropdown — binds the trip
    // by id so a shared phone can't leak it to another driver.
    val travelDeskDriverId: String? = null,
    val kmRate: Double? = null,
    val packageAmount: Double? = null,
)

// ── Vehicles create ─────────────────────────────────────────────────────

data class CreateVehicleRequest(
    val vehicleNumber: String,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Int? = null,
    val type: String? = null,
    val capacity: Int? = null,
    val defaultDriverName: String? = null,
    val defaultDriverPhone: String? = null,
    val defaultDriverWhatsapp: String? = null,
    // Optional override; the agency-scoped endpoint defaults to the caller's
    // own agency when omitted.
    val travelAgencyId: String? = null,
)

data class UpdateVehicleRequest(
    val id: String,
    val vehicleNumber: String? = null,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Int? = null,
    val type: String? = null,
    val capacity: Int? = null,
    val defaultDriverName: String? = null,
    val defaultDriverPhone: String? = null,
    val defaultDriverWhatsapp: String? = null,
    val status: String? = null,
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
