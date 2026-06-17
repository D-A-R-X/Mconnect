package com.manjugroups.m_connect.ui.library.collections

import java.io.Serializable

data class CollectionItem(
    val id: String,
    val bookingName: String,
    val amount: Double,
    val paymentMode: String,
    val refId: String,
    val notes: String,
    val photoPath: String?,
    val dateString: String,
    val status: CollectionStatus,
    val type: CollectionType
) : Serializable

enum class CollectionStatus {
    APPROVED, REJECTED, PENDING
}

enum class CollectionType {
    SELF_FINANCE, BANK_LOAN
}
