package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JointCpTemplateGuardTest {
    private val gson = Gson()

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

    @Test fun `different levels are accepted without preassigned workflow roles`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo", level = 40),
            staff("b", "gm", level = 70),
        ))
    }

    @Test fun `same template id is accepted when admin levels differ`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "sales", level = 40),
            staff("b", "sales", level = 70),
        ))
    }

    @Test fun `missing admin level is rejected`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", null, level = null),
            staff("b", "gm", level = 70),
        ))
    }

    @Test fun `legacy workflow role strings do not reject different levels`() {
        assertNull(JointCpTemplateGuard.rejection(
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

    @Test fun `participant ids are owner first even when senior staff was picked first`() {
        val senior = staff("senior", "sm", level = 70)
        val junior = staff("junior", "bdo", level = 40)

        assertEquals(
            listOf("junior", "senior"),
            JointCpTemplateGuard.participantIds(senior, junior),
        )
    }

    @Test fun `lower level owns outcome regardless of picker order`() {
        val senior = staff("senior", "gm", level = 76)
        val junior = staff("junior", "bdo", level = 47)

        val assignment = JointCpTemplateGuard.assignment(senior, junior)

        assertEquals("junior", assignment?.outcomeOwner?.id)
        assertEquals("senior", assignment?.reviewer?.id)
    }

    @Test fun `same staff is rejected`() {
        val person = staff("same", "sales", level = 47)
        assertNotNull(JointCpTemplateGuard.rejection(person, person))
    }

    @Test fun `coarse role level is never treated as joint cp hierarchy`() {
        val decoded = gson.fromJson(
            """{"_id":"staff","name":"BDO","roleLevel":20}""",
            StaffData::class.java,
        )

        assertNull(decoded.iamTemplateLevel)
    }

    @Test fun `designation level alias is accepted as joint cp hierarchy`() {
        val decoded = gson.fromJson(
            """{"_id":"staff","name":"BDO","designationLevel":3}""",
            StaffData::class.java,
        )

        assertEquals(3, decoded.iamTemplateLevel)
    }
}
