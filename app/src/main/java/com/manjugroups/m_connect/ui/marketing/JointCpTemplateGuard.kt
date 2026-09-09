package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData

/** Client-side feedback for the server-owned Joint CP designation hierarchy. */
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
        // The compact staff picker may temporarily omit hierarchy metadata.
        // Only reject a pair when the client can prove the levels are equal;
        // the create endpoint resolves both staff from the authoritative
        // designation table and rejects genuinely incomplete hierarchy data.
        if (primaryLevel == null || partnerLevel == null) return null
        if (primaryLevel == partnerLevel) {
            return "Both staff have the same designation level. Select one higher-level and one lower-level staff member"
        }
        return null
    }

    fun canPair(primary: StaffData?, partner: StaffData): Boolean =
        rejection(primary, partner) == null

    /** Lower designation level owns OTP/outcome; higher level reviews and completes. */
    fun assignment(primary: StaffData?, partner: StaffData?): Assignment? {
        if (rejection(primary, partner) != null) return null
        val primaryLevel = primary?.iamTemplateLevel ?: return null
        val partnerLevel = partner?.iamTemplateLevel ?: return null
        return if (primaryLevel < partnerLevel) {
            Assignment(outcomeOwner = primary, reviewer = partner)
        } else {
            Assignment(outcomeOwner = partner, reviewer = primary)
        }
    }

    /** Server contract receives owner first and reviewer second, regardless of UI order. */
    fun participantIds(primary: StaffData?, partner: StaffData?): List<String>? {
        if (rejection(primary, partner) != null) return null
        val assignment = assignment(primary, partner)
        return if (assignment != null) {
            listOf(
                assignment.outcomeOwner.id!!.trim(),
                assignment.reviewer.id!!.trim(),
            )
        } else {
            // Preserve both picker IDs when hierarchy metadata is absent. The
            // backend reorders them authoritatively during creation.
            listOf(primary!!.id!!.trim(), partner!!.id!!.trim())
        }
    }
}
