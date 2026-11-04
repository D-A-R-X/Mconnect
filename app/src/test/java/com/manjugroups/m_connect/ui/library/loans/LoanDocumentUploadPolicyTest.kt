package com.manjugroups.m_connect.ui.library.loans

import com.manjugroups.m_connect.ui.library.loans.LoanDocumentUploadPolicy.Kind
import com.manjugroups.m_connect.ui.library.loans.LoanDocumentUploadPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanDocumentUploadPolicyTest {

    @Test fun stageDecidesWhereUploadsGo() {
        // 4 Nov report: a new case is at Loan Initiation, where the server
        // refuses to attach ("can only be changed during collection or verification").
        assertEquals(Mode.ON_SUBMIT, LoanDocumentUploadPolicy.mode("loan_initiation"))
        assertEquals(Mode.ATTACH, LoanDocumentUploadPolicy.mode("document_collection"))
        assertEquals(Mode.ATTACH, LoanDocumentUploadPolicy.mode("Document_Verification"))
        assertEquals(Mode.LOCKED, LoanDocumentUploadPolicy.mode("bank_allocation"))
        assertEquals(Mode.LOCKED, LoanDocumentUploadPolicy.mode("disbursement"))
        // Older servers don't send a stage: attach, falling back on refusal.
        assertEquals(Mode.ATTACH, LoanDocumentUploadPolicy.mode(null))
        assertEquals(Mode.ATTACH, LoanDocumentUploadPolicy.mode(" "))
    }

    @Test fun recognisesTheStageRefusal() {
        assertTrue(LoanDocumentUploadPolicy.isStageRefusal("Documents can only be changed during collection or verification."))
        assertTrue(LoanDocumentUploadPolicy.isStageRefusal("FORBIDDEN: Documents can only be changed during collection or verification."))
        assertFalse(LoanDocumentUploadPolicy.isStageRefusal("This loan document collection is assigned to another staff member."))
        assertFalse(LoanDocumentUploadPolicy.isStageRefusal(null))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test fun sniffsTheRealFileType() {
        assertEquals(Kind.PDF, LoanDocumentUploadPolicy.sniff(ascii("%PDF-1.7\n")))
        assertEquals(Kind.IMAGE, LoanDocumentUploadPolicy.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(Kind.IMAGE, LoanDocumentUploadPolicy.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals(Kind.IMAGE, LoanDocumentUploadPolicy.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        // HEIC from an iPhone or a recent Android gallery.
        assertEquals(Kind.IMAGE, LoanDocumentUploadPolicy.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypheic")))
        assertEquals(Kind.IMAGE, LoanDocumentUploadPolicy.sniff(bytes(0, 0, 0, 0x1C) + ascii("ftypmif1")))
        // Not a document: a video, a zip, an empty file.
        assertEquals(Kind.OTHER, LoanDocumentUploadPolicy.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypmp42")))
        assertEquals(Kind.OTHER, LoanDocumentUploadPolicy.sniff(ascii("PK\u0003\u0004")))
        assertEquals(Kind.OTHER, LoanDocumentUploadPolicy.sniff(ByteArray(0)))
    }
}
