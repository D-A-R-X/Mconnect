package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.StaffData
import com.google.gson.Gson
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

    @Test fun `staff api effective template metadata is decoded`() {
        val direct = Gson().fromJson(
            """{
                "_id":"staff-a",
                "name":"Staff A",
                "iamTemplateId":"template-a",
                "iamTemplateName":"Sales Level 3",
                "iamTemplateLevel":3
            }""".trimIndent(),
            StaffData::class.java,
        )
        val aliases = Gson().fromJson(
            """{
                "id":"staff-b",
                "permissionTemplateId":"template-b",
                "permissionTemplateName":"Sales Level 4",
                "designationLevel":4
            }""".trimIndent(),
            StaffData::class.java,
        )

        assertEquals("template-a", direct.iamTemplateId)
        assertEquals("Sales Level 3", direct.iamTemplateName)
        assertEquals(3, direct.iamTemplateLevel)
        assertEquals("template-b", aliases.iamTemplateId)
        assertEquals("Sales Level 4", aliases.iamTemplateName)
        assertEquals(4, aliases.iamTemplateLevel)
    }

    @Test fun `same effective template id is rejected even if levels differ`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", " Sales ", level = 40),
            staff("b", "sales", level = 70),
        ))
    }

    @Test fun `missing picker template metadata is deferred to create api`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", null, level = null),
            staff("b", "gm", level = 70),
        ))
    }

    @Test fun `create payload sends only companion id beside assigned staff`() {
        assertEquals(
            listOf("b"),
            JointCpTemplateGuard.companionIds(
                staff("a", "sales", level = 40),
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

    @Test fun `equal effective template levels are rejected`() {
        assertNotNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo-east", "outcome_owner", 40),
            staff("b", "gm-temp", "reviewer", 40),
        ))
    }

    @Test fun `missing picker template level is deferred to create api`() {
        assertNull(JointCpTemplateGuard.rejection(
            staff("a", "bdo-east", level = null),
            staff("b", "gm-temp", level = 70),
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

    @Test fun `staff picker includes every role and removes only invalid duplicate rows`() {
        val office = staff("office", "office-template", level = 20).copy(role = "office-staff")
        val field = staff("field", "field-template", level = 30).copy(role = "field-staff")
        val admin = staff("admin", "admin-template", level = 80).copy(role = "super-admin")
        val duplicate = office.copy(name = "Duplicate")
        val missingId = office.copy(id = "")

        assertEquals(
            listOf("office", "field", "admin"),
            JointCpTemplateGuard.pickerStaff(
                listOf(office, field, admin, duplicate, missingId),
            ).map { it.id },
        )
    }

    @Test fun `logged in staff prefill resolves by stable staff id`() {
        val current = staff("staff-current", "sales-three", level = 3)
        val other = staff("staff-other", "sales-four", level = 4)

        assertEquals(
            current,
            JointCpTemplateGuard.loggedInStaff(listOf(other, current), " staff-current "),
        )
        assertNull(JointCpTemplateGuard.loggedInStaff(listOf(other), "staff-current"))
    }

}
