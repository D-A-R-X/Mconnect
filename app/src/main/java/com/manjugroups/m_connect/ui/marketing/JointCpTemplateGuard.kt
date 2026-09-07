package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData

/** Client-side feedback for the server-owned Joint CP template constraint. */
object JointCpTemplateGuard {
    fun rejection(primary: StaffData?, partner: StaffData?): String? {
        if (primary?.id.isNullOrBlank() || partner?.id.isNullOrBlank()) {
            return "Select both staff for this Joint CP"
        }
        if (primary?.id == partner?.id) return "Pick two different staff for a Joint CP"

        val primaryTemplate = primary?.iamTemplateId?.trim().orEmpty()
        val partnerTemplate = partner?.iamTemplateId?.trim().orEmpty()
        if (primaryTemplate.isEmpty() || partnerTemplate.isEmpty()) {
            return "Both staff need an IAM template before creating a Joint CP"
        }
        if (primaryTemplate == partnerTemplate) {
            val label = primary?.iamTemplateName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: partner?.iamTemplateName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "the same IAM template"
            return "Joint CP partners cannot both use $label"
        }
        val primaryLevel = primary?.iamTemplateLevel
        val partnerLevel = partner?.iamTemplateLevel
        if (primaryLevel == null || partnerLevel == null) {
            return "Both IAM templates need a Joint CP level"
        }
        if (primaryLevel == partnerLevel) {
            return "Joint CP partners cannot be on the same IAM template level"
        }
        val roles = setOf(
            primary?.jointCpWorkflowRole?.trim()?.lowercase(),
            partner?.jointCpWorkflowRole?.trim()?.lowercase(),
        )
        if (roles != setOf("outcome_owner", "reviewer")) {
            return "Choose one outcome owner and one reviewer from their IAM templates"
        }
        return null
    }

    fun canPair(primary: StaffData?, partner: StaffData): Boolean =
        rejection(primary, partner) == null

    /** Server contract requires both unique participants, regardless of UI order. */
    fun participantIds(primary: StaffData?, partner: StaffData?): List<String>? {
        if (rejection(primary, partner) != null) return null
        return listOf(primary!!.id!!.trim(), partner!!.id!!.trim())
    }
}
