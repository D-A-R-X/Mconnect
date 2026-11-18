package com.manjugroups.m_connect.ui.lens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A final, encoded picture for one pose. */
class LensCapturedPhoto(
    val pose: LensPose,
    val jpeg: ByteArray,
    val score: Double,
    val width: Int,
    val height: Int,
    val faceWidthPx: Int,
)

/** What the live screen shows for the latest frame. */
data class LensLiveState(
    val hint: LensHint?,
    /** Pose the head is in right now (null between buckets / no face). */
    val poseNow: LensPose?,
    val yaw: Float,
    val faceFound: Boolean,
    val still: Boolean,
    /** A good frame for this pose is being held (ring segment fills). */
    val holding: LensPose?,
    /** One-line readout of what the detector sees (shown in the Lens Test build). */
    val debug: String? = null,
    /** Pose being asked for and its progress 0..1: turning (0..0.75) then the still hold (0.75..1). */
    val target: LensPose? = null,
    val progress: Float = 0f,
)

/**
 * The on-device capture pipeline (camera analysis thread):
 * ML Kit face detection → geometry/expression checks → motion detector →
 * light and sharpness on the face pixels → best still frame per pose → JPEG.
 */
class LensCaptureEngine(
    private val listener: Listener,
) : ImageAnalysis.Analyzer {

    interface Listener {
        fun onLiveState(state: LensLiveState)
        fun onCaptured(photo: LensCapturedPhoto)
    }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // ACCURATE: its head angles are steadier and its landmarks hold on a
            // turned face. The stillness check is time-based, so the slower
            // frame rate no longer blocks capture; yaw is smoothed below.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build(),
    )

    private class Frame(val bitmap: Bitmap, val faceWidth: Int, val yaw: Float)

    private val motion = LensMotionTracker()
    private val selector = LensBestFrameSelector<Frame>()
    /** When a pose first had a still, well-framed face (relaxes sharpness later). */
    private val goodSince = mutableMapOf<LensPose, Long>()
    private var lastFrameAt = 0L
    private var fps = 0f
    private var lastStats: LensPixelStats? = null
    private var lastLogAt = 0L
    /** Smoothed yaw (exponential average) for pose choice and the progress arc. */
    private var smoothYaw: Float? = null

    /** Lens Test build: one line per frame appended here for tuning. */
    @Volatile var frameLog: java.io.File? = null

    /** Yaw each captured pose was taken at (separation check). */
    private val takenYaw = java.util.concurrent.ConcurrentHashMap<LensPose, Float>()

    /** Poses still wanted; set from the UI thread. */
    @Volatile var pending: Set<LensPose> = emptySet()

    /** Paused while the review screen is up or the camera is closing. */
    @Volatile var paused: Boolean = false

    @Volatile private var closed = false

    /** Camera switched: drop the stillness history and any half-held frames. */
    fun resetPoseState() {
        motion.reset()
        selector.reset()
        goodSince.clear()
    }

    fun resetPose(pose: LensPose) {
        selector.clear(pose)
        goodSince.remove(pose)
    }

    fun close() {
        closed = true
        runCatching { detector.close() }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            if (paused || closed) return
            val media = image.image ?: return
            val rotation = image.imageInfo.rotationDegrees
            val faces = try {
                Tasks.await(detector.process(InputImage.fromMediaImage(media, rotation)), 2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                return
            }
            if (paused || closed) return
            val now = SystemClock.elapsedRealtime()
            if (lastFrameAt > 0) {
                val inst = 1000f / (now - lastFrameAt).coerceAtLeast(1)
                fps = if (fps == 0f) inst else fps * 0.8f + inst * 0.2f
            }
            lastFrameAt = now
            val upright = rotation % 180 == 0
            val w = if (upright) image.width else image.height
            val h = if (upright) image.height else image.width
            val raw = toSample(faces, w, h, now)
            val still = motion.update(raw)
            // Smooth only the yaw used for buckets/progress; jitter of a few
            // degrees per frame otherwise flips a head between poses.
            smoothYaw = if (raw.faceCount != 1) null else smoothYaw?.let { it * 0.5f + raw.yaw * 0.5f } ?: raw.yaw
            val sample = if (raw.faceCount == 1) raw.copy(yaw = smoothYaw ?: raw.yaw) else raw
            val geometry = LensCapturePolicy.geometryHint(sample)
            val pose = if (geometry == null) LensCapturePolicy.poseFor(sample.yaw) else null
            val wanted = pending

            // Only the pose being asked for (guided order) can capture, and only
            // from a head angle clearly different from the photos already taken.
            val target = LensPose.guidedOrder.firstOrNull { it in wanted }
            val taken = takenYaw.filterKeys { it !in wanted }
            var hint: LensHint? = geometry
            if (hint == null && pose != null && target != null && pose != target &&
                target != LensPose.STRAIGHT && pose == target.mirror()
            ) {
                hint = LensHint.TURN_OTHER_WAY
            }
            if (hint == null && pose != null && pose == target &&
                LensCapturePolicy.distinctEnough(sample.yaw, taken, pose)
            ) {
                if (!still) {
                    hint = LensHint.HOLD_STILL
                } else {
                    val since = goodSince.getOrPut(pose) { now }
                    val relaxed = now - since > RELAX_AFTER_MS
                    val face = faces.first()
                    val bitmap = uprightBitmap(image, rotation)
                    if (bitmap != null) {
                        val stats = pixelStats(bitmap, face.boundingBox)
                        lastStats = stats
                        hint = LensCapturePolicy.pixelHint(pose, stats, relaxed)
                        if (hint == null) {
                            val score = LensCapturePolicy.score(pose, sample, stats)
                            selector.offer(pose, score, now, Frame(bitmap, face.boundingBox.width(), sample.yaw))
                        }
                    }
                }
            }

            // Poses whose hold window ended: encode and hand over.
            for (candidate in selector.ripe(now)) {
                selector.take(candidate.pose) ?: continue
                goodSince.remove(candidate.pose)
                if (candidate.pose !in pending) continue
                takenYaw[candidate.pose] = candidate.frame.yaw
                val jpeg = encode(candidate.frame.bitmap)
                listener.onCaptured(
                    LensCapturedPhoto(
                        pose = candidate.pose,
                        jpeg = jpeg,
                        score = candidate.score,
                        width = candidate.frame.bitmap.width,
                        height = candidate.frame.bitmap.height,
                        faceWidthPx = candidate.frame.faceWidth,
                    ),
                )
            }

            val holding = LensPose.guidedOrder.firstOrNull { it in pending && selector.candidate(it) != null }
            val turn = if (target != null && sample.faceCount == 1) LensCapturePolicy.turnProgress(target, sample.yaw) else 0f
            val held = target?.let { selector.candidate(it) }?.let {
                ((now - it.openedAt).toFloat() / selector.holdMs).coerceIn(0f, 1f)
            }
            val progress = if (held != null) 0.75f + 0.25f * held else 0.75f * turn
            frameLog?.let { f ->
                runCatching {
                    f.appendText("${now} target=${target?.key} hint=${hint ?: "ok"} pose=${pose?.key} rawYaw=${"%.1f".format(raw.yaw)} prog=${"%.2f".format(progress)} ${debugLine(sample, still, lastStats).replace('\n', ' ')}\n")
                }
            }
            if (com.manjugroups.m_connect.BuildConfig.DEBUG && now - lastLogAt >= 300) {
                lastLogAt = now
                android.util.Log.d("LensCapture", "hint=${hint ?: "ok"} pose=${pose ?: "-"} ${debugLine(sample, still, lastStats).replace('\n', ' ')}")
            }
            listener.onLiveState(
                LensLiveState(
                    hint = hint,
                    poseNow = pose,
                    yaw = sample.yaw,
                    faceFound = sample.faceCount > 0,
                    still = still,
                    holding = holding,
                    debug = debugLine(sample, still, lastStats),
                    target = target,
                    progress = progress,
                ),
            )
        } finally {
            image.close()
        }
    }

    private fun toSample(faces: List<Face>, w: Int, h: Int, now: Long): LensFaceSample {
        val face = faces.firstOrNull()
        if (face == null || faces.size != 1) {
            return LensFaceSample(
                faceCount = faces.size, yaw = 0f, pitch = 0f, roll = 0f,
                leftEyeOpen = null, rightEyeOpen = null, smiling = null,
                hasAllLandmarks = false, noseBetweenEyes = false,
                faceLeft = 0f, faceTop = 0f, faceWidth = 0f, faceHeight = 0f,
                imageWidth = w, imageHeight = h, timestampMs = now,
            )
        }
        val box = face.boundingBox
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val all = leftEye != null && rightEye != null && nose != null && mouthL != null && mouthR != null
        val eyesAndNose = leftEye != null && rightEye != null && nose != null
        val between = eyesAndNose && nose!!.x > min(leftEye!!.x, rightEye!!.x) && nose.x < max(leftEye.x, rightEye.x)
        return LensFaceSample(
            faceCount = 1,
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            roll = face.headEulerAngleZ,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            smiling = face.smilingProbability,
            hasAllLandmarks = all,
            noseBetweenEyes = between,
            hasEyesAndNose = eyesAndNose,
            faceLeft = box.left.toFloat(),
            faceTop = box.top.toFloat(),
            faceWidth = box.width().toFloat(),
            faceHeight = box.height().toFloat(),
            imageWidth = w,
            imageHeight = h,
            timestampMs = now,
        )
    }

    /** Upright pixels (rotation applied, never mirrored): what Lens receives. */
    private fun uprightBitmap(image: ImageProxy, rotation: Int): Bitmap? = try {
        val raw = image.toBitmap()
        if (rotation == 0) {
            raw
        } else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it !== raw) raw.recycle()
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Light and sharpness on a ~128 px crop of the inner face. */
    private fun pixelStats(bitmap: Bitmap, box: Rect): LensPixelStats {
        // Inner face: trims hair/background edges that would inflate sharpness.
        val insetX = (box.width() * 0.15f).roundToInt()
        val insetY = (box.height() * 0.15f).roundToInt()
        val left = (box.left + insetX).coerceIn(0, bitmap.width - 2)
        val top = (box.top + insetY).coerceIn(0, bitmap.height - 2)
        val right = (box.right - insetX).coerceIn(left + 2, bitmap.width)
        val bottom = (box.bottom - insetY).coerceIn(top + 2, bitmap.height)
        val cw = right - left
        val ch = bottom - top
        val scale = min(1f, CROP_PX / cw.toFloat())
        val sw = max(3, (cw * scale).roundToInt())
        val sh = max(3, (ch * scale).roundToInt())
        val crop = Bitmap.createBitmap(bitmap, left, top, cw, ch)
        val small = if (sw == cw && sh == ch) crop else Bitmap.createScaledBitmap(crop, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== crop) small.recycle()
        if (crop !== bitmap) crop.recycle()
        val gray = LensImageMath.toLuma(px)
        val (l, r) = LensImageMath.halves(gray, sw, sh)

        // Whole-frame light (backlight check) on a tiny thumbnail.
        val thumb = Bitmap.createScaledBitmap(bitmap, 48, max(1, 48 * bitmap.height / bitmap.width), true)
        val tp = IntArray(thumb.width * thumb.height)
        thumb.getPixels(tp, 0, thumb.width, 0, 0, thumb.width, thumb.height)
        if (thumb !== bitmap) thumb.recycle()

        return LensPixelStats(
            faceLuma = LensImageMath.mean(gray),
            frameLuma = LensImageMath.mean(LensImageMath.toLuma(tp)),
            leftHalfLuma = l,
            rightHalfLuma = r,
            sharpness = LensImageMath.laplacianVariance(gray, sw, sh),
        )
    }

    private fun debugLine(s: LensFaceSample, still: Boolean, p: LensPixelStats?): String {
        fun f(v: Float?) = if (v == null) "-" else "%.2f".format(v)
        val px = p?.let { "sharp %.0f  light %.0f/%.0f".format(it.sharpness, it.faceLuma, it.frameLuma) } ?: "sharp -  light -"
        return "yaw %.0f  pitch %.0f  roll %.0f  face %.0fpx  eyes %s/%s  smile %s\n%s  %s  %.1f fps".format(
            s.yaw, s.pitch, s.roll, s.faceWidth, f(s.leftEyeOpen), f(s.rightEyeOpen), f(s.smiling),
            px, if (still) "still" else "moving", fps,
        )
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(512 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    companion object {
        private const val CROP_PX = 128f
        private const val JPEG_QUALITY = 92
        /** After this long in a good still pose, accept a slightly softer frame. */
        private const val RELAX_AFTER_MS = 1_500L
    }
}
