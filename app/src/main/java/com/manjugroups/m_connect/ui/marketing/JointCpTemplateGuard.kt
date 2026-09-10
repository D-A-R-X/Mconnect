package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData

/** Selection-only guard. Joint CP authority is resolved and snapshotted by the server. */
object JointCpTemplateGuard {
    fun rejection(primary: StaffData?, partner: StaffData?): String? {
        val primaryId = primary?.id?.trim()
        val partnerId = partner?.id?.trim()
        if (primaryId.isNullOrEmpty() || partnerId.isNullOrEmpty()) {
            return "Select both staff for this Joint CP"
        }
        if (primaryId == partnerId) return "Pick two different staff for a Joint CP"
        return null
    }

    fun canPair(primary: StaffData?, partner: StaffData): Boolean =
        rejection(primary, partner) == null

    /** The create contract sends the second selection separately from assignedStaffId. */
    fun companionIds(primary: StaffData?, partner: StaffData?): List<String>? {
        if (rejection(primary, partner) != null) return null
        return listOf(partner!!.id!!.trim())
    }
}
