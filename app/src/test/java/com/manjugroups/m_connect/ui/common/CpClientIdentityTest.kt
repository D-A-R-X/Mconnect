package com.manjugroups.m_connect.ui.common

import com.manjugroups.m_connect.network.CpVisitClient
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.CpVisitLead
import com.manjugroups.m_connect.network.CpVisitPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class CpClientIdentityTest {
    @Test
    fun currentVisitLeadWinsOverOlderClientAndPlaceNames() {
        val visit = CpVisitDetail(
            lead = CpVisitLead(contactName = "Divakar", mobileNumber = "918807588547"),
            client = CpVisitClient(clientName = "Sivakumar", mobileNumber = "918807588547"),
            clientPlace = CpVisitPlace(name = "Sivakumar", contactPhone = "918807588547"),
        )

        assertEquals("Divakar", visit.preferredCpClientName())
        assertEquals("918807588547", visit.preferredCpClientPhone())
    }

    @Test
    fun clientMasterRemainsFallbackWhenVisitLeadNameIsBlank() {
        val visit = CpVisitDetail(
            lead = CpVisitLead(contactName = "  "),
            client = CpVisitClient(clientName = "Sivakumar"),
        )

        assertEquals("Sivakumar", visit.preferredCpClientName())
    }
}
