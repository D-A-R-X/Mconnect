package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData

/** Fast client guard; the create endpoint repeats this with authoritative IAM data. */
object JointCpTemplateGuard {
    /** Every valid row returned by the active-staff API is selectable here. */
    fun pickerStaff(items: List<StaffData>): List<StaffData> = items
        .filter { !it.id.isNullOrBlank() }
        .distinctBy { it.id!!.trim().lowercase() }

    fun loggedInStaff(items: List<StaffData>, staffId: String?): StaffData? {
        val target = staffId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return pickerStaff(items).firstOrNull { it.id?.trim() == target }
    }

    fun rejection(primary: StaffData?, partner: StaffData?): String? {
        val primaryId = primary?.id?.trim()
        val partnerId = partner?.id?.trim()
        if (primaryId.isNullOrEmpty() || partnerId.isNullOrEmpty()) {
            return "Select both staff for this Joint CP"
        }
        if (primaryId == partnerId) return "Pick two different staff for a Joint CP"

        val primaryTemplateId = primary.iamTemplateId?.trim()?.takeIf(String::isNotEmpty)
        val partnerTemplateId = partner.iamTemplateId?.trim()?.takeIf(String::isNotEmpty)
        if (primaryTemplateId != null && partnerTemplateId != null &&
            primaryTemplateId.equals(partnerTemplateId, ignoreCase = true)
        ) {
            return "Both staff use the same IAM template. Select staff from different template levels."
        }

        val primaryLevel = primary.iamTemplateLevel
        val partnerLevel = partner.iamTemplateLevel
        if (primaryLevel != null && partnerLevel != null && primaryLevel == partnerLevel) {
            return "Both staff have the same Joint CP template level. Select one lower-level and one higher-level staff member."
        }
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
