package com.manjugroups.m_connect.ui.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCapturePolicyTest {

    /** A good, centred straight face in a 960 x 1280 portrait frame. */
    private fun sample(
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        faces: Int = 1,
        eyes: Float? = 0.95f,
        smile: Float? = 0.05f,
        landmarks: Boolean = true,
        noseBetween: Boolean = true,
        width: Float = 340f,
        left: Float = (960 - 340) / 2f,
        top: Float = 400f,
        t: Long = 0L,
    ) = LensFaceSample(
        faceCount = faces, yaw = yaw, pitch = pitch, roll = roll,
        leftEyeOpen = eyes, rightEyeOpen = eyes, smiling = smile,
        hasAllLandmarks = landmarks, noseBetweenEyes = noseBetween,
        faceLeft = left, faceTop = top, faceWidth = width, faceHeight = width * 1.2f,
        imageWidth = 960, imageHeight = 1280, timestampMs = t,
    )

    private val goodPixels = LensPixelStats(
        faceLuma = 130.0, frameLuma = 120.0, leftHalfLuma = 128.0, rightHalfLuma = 132.0, sharpness = 180.0,
    )

    // ── Pose buckets ──

    @Test
    fun yawMapsToTheDocumentedBuckets() {
        assertEquals(LensPose.STRAIGHT, LensCapturePolicy.poseFor(0f))
        assertEquals(LensPose.STRAIGHT, LensCapturePolicy.poseFor(-5f))
        assertNull("between straight and slight", LensCapturePolicy.poseFor(7f))
        assertEquals(LensPose.SLIGHT_LEFT, LensCapturePolicy.poseFor(15f))
        assertEquals(LensPose.SLIGHT_RIGHT, LensCapturePolicy.poseFor(-12f))
        assertEquals(LensPose.MORE_LEFT, LensCapturePolicy.poseFor(25f))
        assertEquals(LensPose.MORE_RIGHT, LensCapturePolicy.poseFor(-30f))
        assertNull("past 30 degrees is never used", LensCapturePolicy.poseFor(31f))
        assertNull("gap between slight and more: no double capture at 20", LensCapturePolicy.poseFor(20.5f))
        assertNull(LensCapturePolicy.poseFor(-45f))
    }

    @Test
    fun aPoseMustDifferFromPhotosAlreadyTaken() {
        val taken = mapOf(LensPose.SLIGHT_LEFT to 18f)
        assertFalse("too close to slight left", LensCapturePolicy.distinctEnough(23f, taken, LensPose.MORE_LEFT))
        assertTrue(LensCapturePolicy.distinctEnough(27f, taken, LensPose.MORE_LEFT))
        assertTrue("its own retake is fine", LensCapturePolicy.distinctEnough(18f, taken, LensPose.SLIGHT_LEFT))
    }

    @Test
    fun requiredPosesAreStraightAndBothSlightTurns() {
        assertEquals(
            setOf(LensPose.STRAIGHT, LensPose.SLIGHT_LEFT, LensPose.SLIGHT_RIGHT),
            LensPose.required.toSet(),
        )
        assertEquals(LensPose.STRAIGHT, LensPose.guidedOrder.first())
        assertEquals(LensPose.SLIGHT_LEFT, LensPose.fromKey("slight_left"))
        assertNull(LensPose.fromKey("side"))
    }

    // ── Geometry / expression ──

    @Test
    fun goodFramePassesGeometry() {
        assertNull(LensCapturePolicy.geometryHint(sample()))
        assertNull(LensCapturePolicy.geometryHint(sample(yaw = 15f)))
    }

    @Test
    fun faceCountRules() {
        assertEquals(LensHint.NO_FACE, LensCapturePolicy.geometryHint(sample(faces = 0)))
        assertEquals(LensHint.MULTIPLE_FACES, LensCapturePolicy.geometryHint(sample(faces = 2)))
    }

    @Test
    fun headMustBeLevelAndNotTurnedTooFar() {
        assertEquals(LensHint.TURN_LESS, LensCapturePolicy.geometryHint(sample(yaw = 35f)))
        assertEquals(LensHint.HEAD_LEVEL, LensCapturePolicy.geometryHint(sample(roll = 14f)))
        assertEquals(LensHint.LOOK_AHEAD, LensCapturePolicy.geometryHint(sample(pitch = -18f)))
    }

    @Test
    fun faceSizeAndFraming() {
        assertEquals(LensHint.MOVE_CLOSER, LensCapturePolicy.geometryHint(sample(width = 120f, left = 420f)))
        assertEquals(LensHint.MOVE_BACK, LensCapturePolicy.geometryHint(sample(width = 760f, left = 100f, top = 100f)))
        assertEquals(LensHint.CENTER_FACE, LensCapturePolicy.geometryHint(sample(left = 20f)))
    }

    @Test
    fun coveredFaceAndExpression() {
        assertEquals(LensHint.COVERED, LensCapturePolicy.geometryHint(sample(landmarks = false)))
        // A strongly turned head that hides the nose/eye alignment is told to turn less.
        assertEquals(LensHint.TURN_LESS, LensCapturePolicy.geometryHint(sample(yaw = 26f, noseBetween = false)))
        assertEquals(LensHint.OPEN_EYES, LensCapturePolicy.geometryHint(sample(eyes = 0.2f)))
        // Unclassified eyes (common on a turned head) are unknown, not closed.
        assertNull(LensCapturePolicy.geometryHint(sample(eyes = null)))
        assertEquals(LensHint.NEUTRAL_FACE, LensCapturePolicy.geometryHint(sample(smile = 0.8f)))
        // A turned head that loses a mouth corner / far eye in the detector is still usable.
        assertNull(LensCapturePolicy.geometryHint(sample(yaw = 15f, landmarks = false)))
        // The far eye of a turned head may read lower and is still accepted.
        assertNull(LensCapturePolicy.geometryHint(sample(yaw = 15f, eyes = 0.5f)))
    }

    // ── Light and sharpness ──

    @Test
    fun lightAndSharpnessChecks() {
        assertNull(LensCapturePolicy.pixelHint(LensPose.STRAIGHT, goodPixels, relaxedSharpness = false))
        assertEquals(
            LensHint.TOO_DARK,
            LensCapturePolicy.pixelHint(LensPose.STRAIGHT, goodPixels.copy(faceLuma = 40.0), false),
        )
        assertEquals(
            LensHint.TOO_BRIGHT,
            LensCapturePolicy.pixelHint(LensPose.STRAIGHT, goodPixels.copy(faceLuma = 230.0), false),
        )
        assertEquals(
            LensHint.BACKLIGHT,
            LensCapturePolicy.pixelHint(LensPose.STRAIGHT, goodPixels.copy(faceLuma = 90.0, frameLuma = 200.0), false),
        )
        val uneven = goodPixels.copy(leftHalfLuma = 70.0, rightHalfLuma = 170.0)
        assertEquals(LensHint.UNEVEN_LIGHT, LensCapturePolicy.pixelHint(LensPose.STRAIGHT, uneven, false))
        assertNull("a turned head is naturally uneven", LensCapturePolicy.pixelHint(LensPose.SLIGHT_LEFT, uneven, false))
        val soft = goodPixels.copy(sharpness = 18.0)
        assertEquals(LensHint.BLURRY, LensCapturePolicy.pixelHint(LensPose.STRAIGHT, soft, false))
        assertNull("relaxed floor after a long good hold", LensCapturePolicy.pixelHint(LensPose.STRAIGHT, soft, true))
    }

    @Test
    fun scorePrefersSharperAndBetterAngledFrames() {
        val base = LensCapturePolicy.score(LensPose.STRAIGHT, sample(), goodPixels)
        val sharper = LensCapturePolicy.score(LensPose.STRAIGHT, sample(), goodPixels.copy(sharpness = 380.0))
        val offAngle = LensCapturePolicy.score(LensPose.STRAIGHT, sample(yaw = 4.5f), goodPixels)
        assertTrue(sharper > base)
        assertTrue(offAngle < base)
        assertTrue(base in 0.0..1.0)
    }

    // ── Motion detector ──

    @Test
    fun stillHeadOnlyAfterTheWindow() {
        val m = LensMotionTracker(windowMs = 300)
        assertFalse(m.update(sample(t = 0)))
        assertFalse(m.update(sample(t = 100)))
        assertFalse(m.update(sample(t = 200)))
        assertTrue(m.update(sample(t = 320)))
        assertTrue(m.update(sample(t = 420)))
    }

    @Test
    fun slowDetectorStillReachesStill() {
        // A few frames a second (mid-range phone): two samples spanning the window are enough.
        val m = LensMotionTracker(windowMs = 400)
        assertFalse(m.update(sample(t = 0)))
        assertTrue(m.update(sample(t = 450, yaw = 2f)))
        assertTrue(m.update(sample(t = 900, yaw = -1f)))
    }

    @Test
    fun turningHeadIsNeverStill() {
        val m = LensMotionTracker(windowMs = 300)
        var still = false
        for (i in 0..10) still = still || m.update(sample(yaw = i * 2.5f, t = i * 60L))
        assertFalse(still)
    }

    @Test
    fun movingFaceIsNotStillAndLosingTheFaceResets() {
        val m = LensMotionTracker(windowMs = 300)
        m.update(sample(t = 0))
        m.update(sample(t = 150, left = 330f))
        assertFalse(m.update(sample(t = 320, left = 200f)))
        m.reset()
        m.update(sample(t = 400))
        assertFalse(m.update(sample(faces = 0, t = 500)))
        assertFalse("window restarts after the face was lost", m.update(sample(t = 600)))
    }

    // ── Best frame ──

    @Test
    fun bestFrameWinsAfterTheHoldWindow() {
        val s = LensBestFrameSelector<String>(holdMs = 700)
        assertTrue(s.offer(LensPose.STRAIGHT, 0.5, 0, "a"))
        assertTrue(s.offer(LensPose.STRAIGHT, 0.7, 200, "b"))
        assertFalse(s.offer(LensPose.STRAIGHT, 0.6, 300, "c"))
        assertTrue("not ripe yet", s.ripe(600).isEmpty())
        val ripe = s.ripe(700)
        assertEquals(1, ripe.size)
        assertEquals("b", ripe.first().frame)
        assertEquals("b", s.take(LensPose.STRAIGHT)?.frame)
        assertNull(s.candidate(LensPose.STRAIGHT))
    }
}
