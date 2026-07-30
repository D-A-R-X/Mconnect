package com.manjugroups.m_connect.ui.library.collections

import java.io.Serializable

data class CollectionItem(
    val id: String,
    var bookingName: String,
    var amount: Double,
    var paymentMode: String,
    var refId: String,
    var notes: String,
    var photoPath: String?,
    var dateString: String,
    var status: CollectionStatus,
    var type: CollectionType,
    var remarks: String? = null,
    // True when the collector corrected their own entry (while still pending
    // Accounts) — drives the "Edited" tag.
    var edited: Boolean = false,
    // Convex `_storage` id from the server. Resolved to a signed URL
    // at list-render time via /api/storage/get-url and loaded into
    // the thumbnail with Coil. Null when the executive submitted the
    // collection without a proof attachment.
    var proofStorageId: String? = null,
) : Serializable

enum class CollectionStatus {
    APPROVED, REJECTED, PENDING
}

enum class CollectionType {
    SELF_FINANCE, BANK_LOAN
}
