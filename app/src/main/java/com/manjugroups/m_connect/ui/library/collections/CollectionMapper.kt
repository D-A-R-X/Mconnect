package com.manjugroups.m_connect.ui.library.collections

import com.manjugroups.m_connect.network.CustomerCollectionRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Maps a server `CustomerCollectionRow` onto the UI `CollectionItem`
 * the existing adapter (and both fragments) already consume.
 *
 * The two screens were originally backed by hardcoded mock data; this
 * keeps the UI contract untouched while letting the fragments swap in
 * the real backend feed.
 *
 * Status mapping:
 *   - server  `approved`               → APPROVED
 *   - server  `rejected`               → REJECTED
 *   - server  `pending_accounts` AND
 *             `clarification_requested` → PENDING
 *
 * The mobile UI doesn't yet distinguish "clarification requested" from
 * "pending" — both render as a pending pill. If/when Accounts needs a
 * dedicated state for clarification we'll add it as a third status.
 *
 * Type mapping uses the booking's `customerPaymentCategory`:
 *   - `loan` → BANK_LOAN
 *   - everything else (`cash_in_hand`, null, unknown) → SELF_FINANCE
 *
 * This replaces the "if bookingName contains Plot 12" hack the initial
 * mock fragments used.
 */
object CollectionMapper {

    /** Rendered in the device's own timezone, which is what the user reads. */
    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US)

    // The server stores these as new Date().toISOString() — a UTC instant. The
    // 'Z' in the pattern is QUOTED, so it matches the letter Z rather than
    // meaning "UTC", and without an explicit timeZone SimpleDateFormat parses
    // in the device's zone. That read 05:35 UTC as 05:35 IST and displayed it
    // as 05:35 AM, when the collection was actually made at 11:05 AM — the
    // 5:30 shift field staff reported. Parsing as UTC and formatting locally
    // gives the real local time.
    private val isoNoMillisFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val isoMillisFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    // Deliberately NOT UTC: a bare "yyyy-MM-dd" is a calendar date, not an
    // instant. Parsing it as UTC would render it as 5:30 AM local instead of
    // midnight on the day it names.
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun map(row: CustomerCollectionRow): CollectionItem {
        return CollectionItem(
            id = row.id,
            bookingName = buildBookingLabel(row),
            amount = row.amount,
            paymentMode = humanPaymentMode(row.paymentMode),
            refId = row.transactionReference.orEmpty(),
            notes = row.notes.orEmpty(),
            photoPath = null, // proof lives in Convex storage, not on disk
            dateString = formatTimestamp(row.createdAt ?: row.collectionDate),
            status = mapStatus(row.verificationStatus),
            type = mapType(row.customerPaymentCategory),
            remarks = row.verificationNotes,
            proofStorageId = row.proofStorageId,
            edited = !row.collectorEditedAt.isNullOrBlank(),
            collectedByStaffId = row.collectedByStaffId,
        )
    }

    private fun buildBookingLabel(row: CustomerCollectionRow): String {
        val site = row.projectName.orEmpty().trim()
        val plot = row.plotNo.orEmpty().trim()
        val client = row.customerName.orEmpty().trim()
        // Mirror the existing "Site - Plot" pattern the mock used so
        // the list rows keep the same visual rhythm. Fall back through
        // client name → booking ref if the case lookup gave us nothing.
        return when {
            site.isNotBlank() && plot.isNotBlank() -> "$site - Plot $plot"
            site.isNotBlank() -> site
            client.isNotBlank() && plot.isNotBlank() -> "$client - Plot $plot"
            client.isNotBlank() -> client
            else -> row.bookingRefNo ?: "Booking ${row.bookingId.takeLast(6)}"
        }
    }

    private fun humanPaymentMode(mode: String): String = when (mode.lowercase(Locale.US)) {
        "cash" -> "Cash"
        "upi" -> "UPI"
        "neft" -> "NEFT"
        "rtgs" -> "RTGS"
        "cheque" -> "Cheque"
        "dd" -> "DD"
        "bank" -> "Bank Transfer"
        else -> mode.replaceFirstChar { it.uppercase() }
    }

    fun mapStatus(server: String): CollectionStatus = when (server.lowercase(Locale.US)) {
        "approved" -> CollectionStatus.APPROVED
        "rejected" -> CollectionStatus.REJECTED
        "pending_accounts", "clarification_requested" -> CollectionStatus.PENDING
        else -> CollectionStatus.PENDING
    }

    fun mapType(category: String?): CollectionType = when (category?.lowercase(Locale.US)) {
        "loan" -> CollectionType.BANK_LOAN
        else -> CollectionType.SELF_FINANCE
    }

    private fun formatTimestamp(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parsed = tryParse(raw, isoMillisFormat)
            ?: tryParse(raw, isoNoMillisFormat)
            ?: tryParse(raw, dateOnlyFormat)
        return if (parsed != null) displayDateFormat.format(parsed) else raw
    }

    private fun tryParse(raw: String, fmt: SimpleDateFormat): Date? = try {
        fmt.parse(raw)
    } catch (_: Exception) {
        null
    }
}
