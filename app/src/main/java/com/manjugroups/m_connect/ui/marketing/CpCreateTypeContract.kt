package com.manjugroups.m_connect.ui.marketing

internal data class CpCreateTypeContract(
    val cpType: String,
    val jointCpCategory: String?,
) {
    fun matches(actualCpType: String?, actualJointCpCategory: String?): Boolean {
        if (actualCpType?.trim() != cpType) return false
        return if (cpType == JOINT_CP) {
            actualJointCpCategory?.trim() == jointCpCategory
        } else {
            true
        }
    }

    companion object {
        private const val JOINT_CP = "joint_cp"
        private val purposes = setOf(
            "sv_cum_cp",
            "new_client_cp",
            "booking_cp",
            "collection_cp",
            "old_client",
            "gift_distribution",
            "other_cp",
        )

        fun from(selectedPurpose: String?, isJoint: Boolean): CpCreateTypeContract? {
            val purpose = selectedPurpose?.trim()?.takeIf { it in purposes } ?: return null
            return if (isJoint) {
                CpCreateTypeContract(cpType = JOINT_CP, jointCpCategory = purpose)
            } else {
                CpCreateTypeContract(cpType = purpose, jointCpCategory = null)
            }
        }
    }
}
