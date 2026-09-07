package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JointCpTemplateGuardTest {
    private fun staff(
        id: String,
        template: String?,
        workflowRole: String? = null,
        level: Int? = null,
    ) = StaffData(
        id = id,
        name = id,
        phone = null,
        role = null,
        designation = null,
        status = "active",
        employeeId = null,
        department = null,
        iamTemplateId = template,
        iamTemplateLevel = level,
        jointCpWorkflowRole = workflowRole,
    )

    @Test fun `different templates are accepted`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo", "outcome_owner", 40),
            staff("b", "gm", "reviewer", 70),
        ))
    }

    @Test fun `same template is rejected regardless of staff id`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo", "outcome_owner", 40),
            staff("b", "bdo", "outcome_owner", 40),
        ))
    }

    @Test fun `missing template is rejected`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", null, "outcome_owner", 40),
            staff("b", "gm", "reviewer", 70),
        ))
    }

    @Test fun `two reviewer templates are rejected`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", "gm", "reviewer", 70),
            staff("b", "sm", "reviewer", 60),
        ))
    }

    @Test fun `different templates on the same level are rejected`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo-east", "outcome_owner", 40),
            staff("b", "gm-temp", "reviewer", 40),
        ))
    }

    @Test fun `valid pair produces exactly both participant ids`() {
        val owner = staff("owner", "owner-template", "outcome_owner", 40)
        val reviewer = staff("reviewer", "review-template", "reviewer", 70)

        assertEquals(listOf("owner", "reviewer"), JointCpTemplateGuard.participantIds(owner, reviewer))
    }
}
