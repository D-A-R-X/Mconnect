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

    @Test fun `different levels are accepted for server assignment`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo", level = 40),
            staff("b", "gm", level = 70),
        ))
    }

    @Test fun `same template id is deferred to server authority`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "sales", level = 40),
            staff("b", "sales", level = 70),
        ))
    }

    @Test fun `missing picker metadata is deferred to authoritative server validation`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", null, level = null),
            staff("b", "gm", level = 70),
        ))
    }

    @Test fun `create payload sends only companion id beside assigned staff`() {
        assertEquals(
            listOf("b"),
            JointCpTemplateGuard.companionIds(
                staff("a", "sales", level = null),
                staff("b", "gm", level = 70),
            ),
        )
    }

    @Test fun `legacy workflow role strings never control creation`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "gm", "reviewer", 70),
            staff("b", "sm", "reviewer", 60),
        ))
    }

    @Test fun `equal picker levels are deferred to effective template resolver`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo-east", "outcome_owner", 40),
            staff("b", "gm-temp", "reviewer", 40),
        ))
    }

    @Test fun `picker order remains selection order and does not assign authority`() {
        val senior = staff("senior", "sm", level = 70)
        val junior = staff("junior", "bdo", level = 40)

        assertEquals(
            listOf("junior"),
            JointCpTemplateGuard.companionIds(senior, junior),
        )
    }

    @Test fun `same staff is rejected`() {
        val person = staff("same", "sales", level = 47)
        assertNotNull(JointCpTemplateGuard.rejection(person, person))
    }

}
