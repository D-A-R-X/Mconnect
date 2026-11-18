package com.manjugroups.m_connect.ui.lens

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Lens face enrollment: the pictures the face model needs (see the Lens
 * "Enroll Person From Photos" doc). Pure logic, no Android types, so every rule
 * is unit-tested.
 *
 * Yaw sign: ML Kit's headEulerAngleY is positive when the face turns toward the
 * right side of the (unmirrored) camera image, which on the front camera is the
 * person's own LEFT. Buckets accept a frame whenever its yaw fits a pending pose,
 * so even a flipped sign on some device still fills every pose; the guided order
 * only drives the prompt.
 */
enum class LensPose(
    val key: String,
    val label: String,
    val required: Boolean,
    /** Yaw the picture ideally has (person's left = positive). */
    val targetYaw: Float,
) {
    STRAIGHT("straight", "Straight", true, 0f),
    SLIGHT_LEFT("slight_left", "Slight left", true, 22f),
    MORE_LEFT("more_left", "More left", false, 47f),
    SLIGHT_RIGHT("slight_right", "Slight right", true, -22f),
    MORE_RIGHT("more_right", "More right", false, -47f);

    companion object {
        /** Guided order: straight, left side, back through centre, right side. */
        val guidedOrder: List<LensPose> = listOf(STRAIGHT, SLIGHT_LEFT, MORE_LEFT, SLIGHT_RIGHT, MORE_RIGHT)
        val required: List<LensPose> = entries.filter { it.required }
        fun fromKey(key: String?): LensPose? = entries.firstOrNull { it.key == key }
    }

    /** Same angle on the other side (straight stays straight). */
    fun mirror(): LensPose = when (this) {
        SLIGHT_LEFT -> SLIGHT_RIGHT
        MORE_LEFT -> MORE_RIGHT
        SLIGHT_RIGHT -> SLIGHT_LEFT
        MORE_RIGHT -> MORE_LEFT
        STRAIGHT -> STRAIGHT
    }
}

/** What the detector saw in one frame (all pixel values in the upright image). */
data class LensFaceSample(
    val faceCount: Int,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val leftEyeOpen: Float?,
    val rightEyeOpen: Float?,
    val smiling: Float?,
    /** Both eyes, nose base and both mouth corners were found. */
    val hasAllLandmarks: Boolean,
    /** Nose x lies between the two eye x's (the Lens alignment rule). */
    val noseBetweenEyes: Boolean,
    val faceLeft: Float,
    val faceTop: Float,
    val faceWidth: Float,
    val faceHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestampMs: Long,
    /** Both eyes and the nose base were found (a turned head often loses a mouth corner). */
    val hasEyesAndNose: Boolean = hasAllLandmarks,
) {
    val centerX: Float get() = faceLeft + faceWidth / 2f
    val centerY: Float get() = faceTop + faceHeight / 2f
}

/** Pixel measurements taken only for frames that passed the geometry checks. */
data class LensPixelStats(
    /** Mean luma 0..255 over the face box. */
    val faceLuma: Double,
    /** Mean luma 0..255 over the whole frame. */
    val frameLuma: Double,
    /** Mean luma of the face's left and right halves (uneven light check). */
    val leftHalfLuma: Double,
    val rightHalfLuma: Double,
    /** Variance of the Laplacian over the face crop (higher = sharper). */
    val sharpness: Double,
)

/** Why a frame can't be used, as the live hint the person sees. */
enum class LensHint(val text: String) {
    NO_FACE("Show your face inside the oval"),
    MULTIPLE_FACES("Only one person in the frame"),
    CENTER_FACE("Move your face to the centre of the oval"),
    MOVE_CLOSER("Move a little closer"),
    MOVE_BACK("Move back a little so your whole head shows"),
    HEAD_LEVEL("Keep your head level"),
    LOOK_AHEAD("Look ahead, not up or down"),
    TURN_LESS("Turn a little less"),
    COVERED("Remove mask, sunglasses or cap"),
    OPEN_EYES("Keep your eyes open"),
    NEUTRAL_FACE("Keep a neutral face, mouth closed"),
    HOLD_STILL("Hold still"),
    TOO_DARK("Too dark. Face a light"),
    TOO_BRIGHT("Too bright. Move out of direct light"),
    BACKLIGHT("Light is behind you. Turn to face the light"),
    UNEVEN_LIGHT("Light is uneven on your face"),
    BLURRY("Hold the phone steady"),
    TURN_OTHER_WAY("Turn the other way"),
}

object LensCapturePolicy {

    // ── Pose angles (degrees) ──
    const val STRAIGHT_MAX = 5f
    const val SLIGHT_MIN = 10f
    const val SLIGHT_MAX = 20f
    /**
     * "More" poses are real side turns (user, 18 Nov: slight and more looked the
     * same at 22..30). The test phone's log showed people settle at 35..45 for a
     * side turn; past ~45 the nose crosses the far eye and Lens can't align it.
     */
    const val MORE_MAX = 50f
    /** Head tilt toward a shoulder. ML Kit's angles jitter a few degrees per frame. */
    const val LEVEL_MAX = 12f
    /**
     * Capture windows sit inside the doc's ranges with gaps between them, so a
     * head held near a boundary (e.g. 20 degrees) can't fill two poses at once.
     */
    // User's angles (18 Nov): slight 15..30, more 45..50, straight within 5.
    const val SLIGHT_CAPTURE_MIN = 15f
    const val SLIGHT_CAPTURE_MAX = 30f
    /**
     * People stop turning the moment the ring fills, so "more" must start well
     * past "slight" or the two photos look the same (user, 18 Nov). The phone's
     * log showed people settle at 35..45 for a side turn when left to it.
     */
    const val MORE_CAPTURE_MIN = 45f
    /** Every photo must differ from the ones already taken by at least this much yaw. */
    const val MIN_POSE_SEPARATION = 15f
    /** Up/down: a phone held a little low reads ~10 degrees; Lens tolerates mild pitch. */
    const val PITCH_MAX = 15f

    // ── Face size in the sent (upright) image ──
    /** Below this the face embedding is weak: ask the person to come closer. */
    const val MIN_FACE_PX = 150f
    /** The Lens doc's recommended size; frames at or above it score full marks. */
    const val GOOD_FACE_PX = 200f
    /** A face wider than this share of the frame risks cutting the chin/forehead. */
    const val MAX_FACE_SHARE = 0.72f

    // ── Expression ──
    /** ML Kit reads relaxed open eyes as low as ~0.4; closed eyes are ~0.1. */
    const val EYES_OPEN_MIN = 0.4f
    /** The far eye of a turned head reads lower; still clearly open. */
    const val EYES_OPEN_MIN_TURNED = 0.3f
    const val SMILE_MAX = 0.5f

    // ── Light (0..255 luma) ──
    const val LUMA_MIN = 60.0
    const val LUMA_MAX = 210.0
    const val BACKLIGHT_GAP = 70.0
    const val UNEVEN_GAP = 65.0

    // ── Sharpness (Laplacian variance on a ~128 px face crop) ──
    const val SHARP_MIN = 25.0
    /** Floor used after the person has held a good pose for a while on a soft camera. */
    const val SHARP_MIN_RELAXED = 12.0

    /** The pose a yaw belongs to, or null between buckets / past the limit. */
    fun poseFor(yaw: Float): LensPose? {
        val a = abs(yaw)
        return when {
            a <= STRAIGHT_MAX -> LensPose.STRAIGHT
            a < SLIGHT_CAPTURE_MIN -> null
            a <= SLIGHT_CAPTURE_MAX -> if (yaw > 0) LensPose.SLIGHT_LEFT else LensPose.SLIGHT_RIGHT
            a < MORE_CAPTURE_MIN -> null
            a <= MORE_MAX -> if (yaw > 0) LensPose.MORE_LEFT else LensPose.MORE_RIGHT
            else -> null
        }
    }

    /**
     * Whether a frame at [yaw] may fill [pose]: it must be the pose being asked
     * for and differ enough from every photo already taken (yaw by pose).
     */
    fun distinctEnough(yaw: Float, taken: Map<LensPose, Float>, pose: LensPose): Boolean =
        taken.none { (p, y) -> p != pose && abs(yaw - y) < MIN_POSE_SEPARATION }

    /**
     * Geometry + expression checks that need no pixels. Returns the first
     * problem as a hint, or null when the frame is usable so far.
     */
    fun geometryHint(s: LensFaceSample): LensHint? {
        if (s.faceCount == 0) return LensHint.NO_FACE
        if (s.faceCount > 1) return LensHint.MULTIPLE_FACES
        if (abs(s.yaw) > MORE_MAX) return LensHint.TURN_LESS
        if (abs(s.roll) > LEVEL_MAX) return LensHint.HEAD_LEVEL
        if (abs(s.pitch) > PITCH_MAX) return LensHint.LOOK_AHEAD
        // Whole head inside the frame with some space around it.
        val w = s.imageWidth.toFloat()
        val h = s.imageHeight.toFloat()
        if (s.faceWidth > w * MAX_FACE_SHARE) return LensHint.MOVE_BACK
        val marginX = s.faceWidth * 0.08f
        val marginY = s.faceHeight * 0.08f
        if (s.faceLeft < marginX || s.faceTop < marginY ||
            s.faceLeft + s.faceWidth > w - marginX || s.faceTop + s.faceHeight > h - marginY
        ) {
            return if (s.faceWidth > w * 0.55f) LensHint.MOVE_BACK else LensHint.CENTER_FACE
        }
        // Face roughly centred (the oval guide sits in the middle of the preview).
        if (abs(s.centerX / w - 0.5f) > 0.22f || abs(s.centerY / h - 0.46f) > 0.24f) {
            return LensHint.CENTER_FACE
        }
        if (s.faceWidth < MIN_FACE_PX) return LensHint.MOVE_CLOSER
        val a = abs(s.yaw)
        when {
            a < SLIGHT_MIN -> {
                // Straight: every landmark must show; a covered face hides some.
                if (!s.hasAllLandmarks || !s.noseBetweenEyes) return LensHint.COVERED
            }
            a < MORE_CAPTURE_MIN -> {
                // Slight turn: when the eyes and nose are found, the nose must
                // sit between the eyes (the Lens alignment rule).
                if (s.hasEyesAndNose && !s.noseBetweenEyes) return LensHint.TURN_LESS
            }
            // Side turn (more left/right): the far eye is hidden or foreshortened,
            // so no eye or landmark rule; one clear, still, sharp face is enough.
        }
        if (a < MORE_CAPTURE_MIN) {
            val eyesMin = if (a >= SLIGHT_MIN) EYES_OPEN_MIN_TURNED else EYES_OPEN_MIN
            // Unclassified eyes (null) are unknown, not closed. A turned head
            // reads its far eye low, so a slight turn judges the nearer eye.
            val eyes = listOfNotNull(s.leftEyeOpen, s.rightEyeOpen)
            val judged = if (a >= SLIGHT_MIN) eyes.maxOrNull() else eyes.minOrNull()
            if (judged != null && judged < eyesMin) return LensHint.OPEN_EYES
        }
        if ((s.smiling ?: 0f) > SMILE_MAX) return LensHint.NEUTRAL_FACE
        return null
    }

    /** Head-angle window (|yaw|, degrees) a pose captures in. */
    fun windowFor(pose: LensPose): ClosedFloatingPointRange<Float> = when (pose) {
        LensPose.STRAIGHT -> 0f..STRAIGHT_MAX
        LensPose.SLIGHT_LEFT, LensPose.SLIGHT_RIGHT -> SLIGHT_CAPTURE_MIN..SLIGHT_CAPTURE_MAX
        LensPose.MORE_LEFT, LensPose.MORE_RIGHT -> MORE_CAPTURE_MIN..MORE_MAX
    }

    /**
     * How far the head has turned toward [target], 0..1 (1 = inside its window).
     * Turning the wrong way reads 0; overshooting falls back so the bar tells
     * the person to turn back a little. Drives the ring's progress arc.
     */
    fun turnProgress(target: LensPose, yaw: Float): Float {
        val w = windowFor(target)
        if (target == LensPose.STRAIGHT) {
            val a = abs(yaw)
            return if (a <= w.endInclusive) 1f else (1f - (a - w.endInclusive) / 20f).coerceIn(0f, 1f)
        }
        // Positive toward the target's side (person's left = positive yaw).
        val toward = if (target.targetYaw > 0) yaw else -yaw
        return when {
            toward <= 0f -> 0f
            toward < w.start -> toward / w.start
            toward <= w.endInclusive -> 1f
            else -> (1f - (toward - w.endInclusive) / 12f).coerceIn(0.4f, 1f)
        }
    }

    /** Light and sharpness checks for a frame that passed [geometryHint]. */
    fun pixelHint(pose: LensPose, p: LensPixelStats, relaxedSharpness: Boolean): LensHint? {
        if (p.faceLuma < LUMA_MIN) return LensHint.TOO_DARK
        if (p.faceLuma > LUMA_MAX) return LensHint.TOO_BRIGHT
        if (p.frameLuma - p.faceLuma > BACKLIGHT_GAP) return LensHint.BACKLIGHT
        // Only the straight picture can judge left/right evenness; a turned
        // head is naturally darker on its far side.
        if (pose == LensPose.STRAIGHT && abs(p.leftHalfLuma - p.rightHalfLuma) > UNEVEN_GAP) {
            return LensHint.UNEVEN_LIGHT
        }
        val sharpMin = if (relaxedSharpness) SHARP_MIN_RELAXED else SHARP_MIN
        if (p.sharpness < sharpMin) return LensHint.BLURRY
        return null
    }

    /**
     * Quality score 0..1 for choosing the best frame of a pose: sharpness,
     * face size, how close the yaw is to the pose's ideal angle, how level the
     * head is, and how clearly the eyes are open.
     */
    fun score(pose: LensPose, s: LensFaceSample, p: LensPixelStats): Double {
        val sharp = (ln(max(p.sharpness, 1.0)) / ln(400.0)).coerceIn(0.0, 1.0)
        val size = ((s.faceWidth - MIN_FACE_PX) / (GOOD_FACE_PX * 1.6f - MIN_FACE_PX)).toDouble().coerceIn(0.0, 1.0)
        val yawErr = abs(s.yaw - pose.targetYaw).toDouble()
        val angle = (1.0 - yawErr / 10.0).coerceIn(0.0, 1.0)
        val level = (1.0 - (abs(s.pitch) / PITCH_MAX + abs(s.roll) / LEVEL_MAX) / 2.0).coerceIn(0.0, 1.0)
        val eyes = min(s.leftEyeOpen ?: 0.7f, s.rightEyeOpen ?: 0.7f).toDouble().coerceIn(0.0, 1.0)
        val light = (1.0 - abs(p.faceLuma - 135.0) / 90.0).coerceIn(0.0, 1.0)
        return 0.34 * sharp + 0.16 * size + 0.18 * angle + 0.12 * level + 0.10 * eyes + 0.10 * light
    }
}

/**
 * Motion detector: a frame counts only when the head has been still for
 * [windowMs] (angles and face centre barely moved), so frames grabbed while
 * the head is turning, which come out blurred and between angles, are never kept.
 *
 * Time-based, not frame-count based: a slow detector (a few frames a second on
 * mid-range phones) still reaches "still" once two samples span the window.
 * The limits sit above ML Kit's normal frame-to-frame jitter (2-4 degrees).
 */
class LensMotionTracker(
    private val windowMs: Long = 400L,
    private val maxAngleDelta: Float = 6f,
    private val maxCenterDrift: Float = 0.05f,
) {
    private data class Point(val t: Long, val yaw: Float, val pitch: Float, val roll: Float, val cx: Float, val cy: Float)

    private val points = ArrayDeque<Point>()

    fun reset() = points.clear()

    /** Adds a sample and returns whether the head has been still over the window. */
    fun update(s: LensFaceSample): Boolean {
        if (s.faceCount != 1) {
            points.clear()
            return false
        }
        val w = s.imageWidth.toFloat().coerceAtLeast(1f)
        points.addLast(Point(s.timestampMs, s.yaw, s.pitch, s.roll, s.centerX / w, s.centerY / w))
        // Keep exactly one anchor at or before the window start, so the oldest
        // point always spans the window however slow the detector is.
        while (points.size >= 2 && s.timestampMs - points[1].t >= windowMs) points.removeFirst()
        if (points.size < 2) return false
        if (s.timestampMs - points.first().t < windowMs) return false
        fun spread(sel: (Point) -> Float): Float {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (p in points) {
                val v = sel(p)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return hi - lo
        }
        return spread { it.yaw } <= maxAngleDelta &&
            spread { it.pitch } <= maxAngleDelta &&
            spread { it.roll } <= maxAngleDelta &&
            spread { it.cx } <= maxCenterDrift &&
            spread { it.cy } <= maxCenterDrift
    }
}

/**
 * Best-frame choice per pose. The first good still frame of a pose opens a
 * short hold window; better frames inside it replace the candidate; when the
 * window ends the pose is captured.
 */
class LensBestFrameSelector<T>(val holdMs: Long = 500L) {
    data class Candidate<T>(val pose: LensPose, val score: Double, val openedAt: Long, val frame: T)

    private val candidates = mutableMapOf<LensPose, Candidate<T>>()

    fun reset() = candidates.clear()
    fun clear(pose: LensPose) = candidates.remove(pose)
    fun candidate(pose: LensPose): Candidate<T>? = candidates[pose]

    /** Offers a good frame; returns true when it became the pose's best one. */
    fun offer(pose: LensPose, score: Double, now: Long, frame: T): Boolean {
        val current = candidates[pose]
        if (current == null) {
            candidates[pose] = Candidate(pose, score, now, frame)
            return true
        }
        if (score > current.score) {
            candidates[pose] = Candidate(pose, score, current.openedAt, frame)
            return true
        }
        return false
    }

    /** Poses whose hold window has ended: their best frame is final. */
    fun ripe(now: Long): List<Candidate<T>> =
        candidates.values.filter { now - it.openedAt >= holdMs }

    fun take(pose: LensPose): Candidate<T>? = candidates.remove(pose)
}
