package com.manjugroups.m_connect.ui.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensFaceCropTest {

    private fun assertContainsFace(b: LensFaceCrop.Box, l: Float, t: Float, w: Float, h: Float) {
        assertTrue("left ${b.left} > face $l", b.left <= l)
        assertTrue("top ${b.top} > face $t", b.top <= t)
        assertTrue("right ${b.right} < face ${l + w}", b.right >= l + w)
        assertTrue("bottom ${b.bottom} < face ${t + h}", b.bottom >= t + h)
    }

    @Test
    fun centredFaceGetsHeadAndMarginInPortrait() {
        // 960x1280 upright frame, 200x240 face in the middle.
        val b = LensFaceCrop.cropBox(380f, 420f, 200f, 240f, 960, 1280)
        assertEquals(0.8f, b.width.toFloat() / b.height, 0.01f)
        assertContainsFace(b, 380f, 420f, 200f, 240f)
        // Wider than the face by about 1.9x (or more for 4:5).
        assertTrue(b.width >= 380)
        // More room above the face (hair, forehead) than below the chin.
        val above = 420 - b.top
        val below = b.bottom - (420 + 240)
        assertTrue("above $above below $below", above > below)
        assertTrue("chin margin", below > 0)
        // Not the whole frame: no shirt and waist.
        assertTrue(b.height < 1280 * 0.6)
    }

    @Test
    fun boxSlidesInsideTheFrameAtEdges() {
        // Face touching the top-left corner.
        val b = LensFaceCrop.cropBox(0f, 10f, 200f, 240f, 960, 1280)
        assertTrue(b.left >= 0 && b.top >= 0 && b.right <= 960 && b.bottom <= 1280)
        assertContainsFace(b, 0f, 10f, 200f, 240f)
        assertEquals(0.8f, b.width.toFloat() / b.height, 0.01f)

        // Face at the bottom-right corner.
        val c = LensFaceCrop.cropBox(760f, 1040f, 200f, 240f, 960, 1280)
        assertTrue(c.right <= 960 && c.bottom <= 1280)
        assertContainsFace(c, 760f, 1040f, 200f, 240f)
    }

    @Test
    fun bigFaceInSmallFrameNeverCutsTheHead() {
        // Face filling most of a 640x480 landscape frame.
        val b = LensFaceCrop.cropBox(160f, 40f, 320f, 400f, 640, 480)
        assertTrue(b.left >= 0 && b.top >= 0 && b.right <= 640 && b.bottom <= 480)
        assertContainsFace(b, 160f, 40f, 320f, 400f)
    }

    @Test
    fun smallCropsAreUpscaledLargeOnesKeepNativePixels() {
        assertEquals(480 to 600, LensFaceCrop.outputSize(240, 300))
        assertEquals(400 to 500, LensFaceCrop.outputSize(400, 500))
        assertEquals(720 to 900, LensFaceCrop.outputSize(720, 900))
    }
}
