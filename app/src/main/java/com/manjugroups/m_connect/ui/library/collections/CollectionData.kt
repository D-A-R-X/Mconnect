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
    var remarks: String? = null
) : Serializable

enum class CollectionStatus {
    APPROVED, REJECTED, PENDING
}

enum class CollectionType {
    SELF_FINANCE, BANK_LOAN
}
