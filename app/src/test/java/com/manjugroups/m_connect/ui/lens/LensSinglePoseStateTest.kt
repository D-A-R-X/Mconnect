package com.manjugroups.m_connect.ui.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retake of one saved photo: the session asks only for that pose. */
class LensSinglePoseStateTest {

    private fun photo(pose: LensPose) = LensCapturedPhoto(pose, ByteArray(4), 0.8, 480, 600, 200)

    @Test
    fun onlyThatPoseIsAskedFor() {
        val pose = LensPose.MORE_LEFT
        val state = LensEnrollUiState(single = pose, skipped = LensPose.entries.filter { it != pose }.toSet())
        assertEquals(pose, state.target)
        assertEquals(setOf(pose), state.pending)
        assertFalse(state.requiredDone)

        val done = state.copy(photos = mapOf(pose to photo(pose)))
        assertNull(done.target)
        assertTrue(done.pending.isEmpty())
        // An optional pose alone is enough for a single retake.
        assertTrue(done.requiredDone)
        assertTrue(done.allDone)
    }

    @Test
    fun fullEnrollmentStillNeedsTheRequiredPoses() {
        val state = LensEnrollUiState(photos = mapOf(LensPose.STRAIGHT to photo(LensPose.STRAIGHT)))
        assertFalse(state.requiredDone)
        assertEquals(LensPose.SLIGHT_LEFT, state.target)
    }
}
