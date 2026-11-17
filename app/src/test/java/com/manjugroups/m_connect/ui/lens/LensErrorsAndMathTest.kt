package com.manjugroups.m_connect.ui.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensErrorsAndMathTest {

    // ── Error mapping ──

    @Test
    fun serverSentenceIsShownWithTheRightAction() {
        val o = LensErrors.parse(
            422,
            """{"success":false,"code":"PHOTOS_SHOW_DIFFERENT_PEOPLE","error":"Record again alone in the frame.",
               "failed":[{"pose":"more_left","code":"NOT_SAME_PERSON","message":"This photo doesn't match the others."},
                         {"pose":null,"code":"NO_FACE"}]}""",
        )
        assertEquals("PHOTOS_SHOW_DIFFERENT_PEOPLE", o.code)
        assertEquals("Record again alone in the frame.", o.message)
        assertEquals(LensErrors.Action.RETAKE, o.action)
        assertEquals(2, o.failed.size)
        assertEquals(LensPose.MORE_LEFT, o.failed[0].pose)
        assertEquals(null, o.failed[1].pose)
        assertEquals(LensErrors.photoMessageFor("NO_FACE"), o.failed[1].message)
    }

    @Test
    fun anotherPersonsMatchNeverEchoesTheServerText() {
        val o = LensErrors.parse(409, """{"code":"FACE_MATCHES_ANOTHER_PERSON","error":"Matches MG0999 Someone Else"}""")
        assertEquals(LensErrors.messageFor("FACE_MATCHES_ANOTHER_PERSON"), o.message)
        assertFalse(o.message.contains("MG0999"))
        assertEquals(LensErrors.Action.CONTACT_ADMIN, o.action)
    }

    @Test
    fun statusFallbacksWhenTheBodyIsUnreadable() {
        assertEquals(LensErrors.Action.RETRY, LensErrors.parse(503, "<html>busy</html>").action)
        assertEquals("LENS_NOT_READY", LensErrors.parse(503, null).code)
        assertEquals(LensErrors.Action.RETRY, LensErrors.parse(500, """{"code":"SERVER_ERROR"}""").action)
        assertEquals(LensErrors.Action.CONTACT_ADMIN, LensErrors.parse(403, "").action)
        assertEquals(LensErrors.Action.CONTACT_ADMIN, LensErrors.parse(502, """{"code":"LENS_REJECTED"}""").action)
        assertEquals(LensErrors.Action.RETAKE, LensErrors.parse(400, """{"code":"INVALID_REQUEST"}""").action)
        assertEquals(LensErrors.messageFor("NO_EMPLOYEE_CODE"), LensErrors.parse(400, """{"code":"NO_EMPLOYEE_CODE"}""").message)
    }

    @Test
    fun everyDocumentedCodeHasItsOwnSentence() {
        val generic = LensErrors.messageFor("SOMETHING_ELSE")
        listOf(
            "FACE_MATCHES_ANOTHER_PERSON", "FACE_DOES_NOT_MATCH_EXISTING_ENROLLMENT", "NO_USABLE_FACE",
            "PHOTOS_SHOW_DIFFERENT_PEOPLE", "NO_EMPLOYEE_CODE", "STAFF_NOT_FOUND", "FORBIDDEN", "INVALID_REQUEST",
            "LENS_NOT_CONFIGURED", "LENS_REJECTED", "LENS_NOT_READY",
        ).forEach { assertTrue(it, LensErrors.messageFor(it) != generic) }
        val photoGeneric = LensErrors.photoMessageFor("X")
        listOf(
            "NO_FACE", "BLURRY_OR_POOR_QUALITY_FACE", "FACE_TOO_SMALL", "NOT_SAME_PERSON",
            "TOO_SIMILAR_TO_ANOTHER_PERSON", "INVALID_IMAGE",
        ).forEach { assertTrue(it, LensErrors.photoMessageFor(it) != photoGeneric) }
    }

    // ── Image math ──

    @Test
    fun lumaAndHalves() {
        assertEquals(255, LensImageMath.luma(0xFFFFFFFF.toInt()))
        assertEquals(0, LensImageMath.luma(0xFF000000.toInt()))
        // 4x1: left half dark, right half bright.
        val (l, r) = LensImageMath.halves(intArrayOf(10, 20, 200, 210), 4, 1)
        assertEquals(15.0, l, 0.001)
        assertEquals(205.0, r, 0.001)
        assertEquals(110.0, LensImageMath.mean(intArrayOf(10, 20, 200, 210)), 0.001)
    }

    @Test
    fun laplacianSeparatesSharpFromFlat() {
        val w = 16
        val h = 16
        val flat = IntArray(w * h) { 128 }
        val checker = IntArray(w * h) { i -> if (((i % w) + (i / w)) % 2 == 0) 30 else 220 }
        assertEquals(0.0, LensImageMath.laplacianVariance(flat, w, h), 0.0001)
        assertTrue(LensImageMath.laplacianVariance(checker, w, h) > 1_000.0)
        assertEquals(0.0, LensImageMath.laplacianVariance(IntArray(4), 2, 2), 0.0)
    }
}
