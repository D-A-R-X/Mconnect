package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData

/** Client-side feedback for the server-owned Joint CP template constraint. */
object JointCpTemplateGuard {
    data class Assignment(
        val outcomeOwner: StaffData,
        val reviewer: StaffData,
    )

    fun rejection(primary: StaffData?, partner: StaffData?): String? {
        if (primary?.id.isNullOrBlank() || partner?.id.isNullOrBlank()) {
            return "Select both staff for this Joint CP"
        }
        if (primary?.id == partner?.id) return "Pick two different staff for a Joint CP"

        val primaryLevel = primary?.iamTemplateLevel
        val partnerLevel = partner?.iamTemplateLevel
        if (primaryLevel == null || partnerLevel == null) {
            return "Joint CP level is missing for one of these staff. Ask admin to update the IAM template"
        }
        if (primaryLevel == partnerLevel) {
            return "Both staff have the same designation level. Select one higher-level and one lower-level staff member"
        }
        return null
    }

    fun canPair(primary: StaffData?, partner: StaffData): Boolean =
        rejection(primary, partner) == null

    /** Lower admin IAM level owns OTP/outcome; higher level reviews and completes. */
    fun assignment(primary: StaffData?, partner: StaffData?): Assignment? {
        if (rejection(primary, partner) != null) return null
        return if (primary!!.iamTemplateLevel!! < partner!!.iamTemplateLevel!!) {
            Assignment(outcomeOwner = primary, reviewer = partner)
        } else {
            Assignment(outcomeOwner = partner, reviewer = primary)
        }
    }

    /** Server contract receives owner first and reviewer second, regardless of UI order. */
    fun participantIds(primary: StaffData?, partner: StaffData?): List<String>? {
        val assignment = assignment(primary, partner) ?: return null
        return listOf(
            assignment.outcomeOwner.id!!.trim(),
            assignment.reviewer.id!!.trim(),
        )
    }
}
