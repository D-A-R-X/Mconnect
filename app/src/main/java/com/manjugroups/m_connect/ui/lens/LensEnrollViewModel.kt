package com.manjugroups.m_connect.ui.lens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiErrors
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.LensEnrollPhoto
import com.manjugroups.m_connect.network.LensEnrollRequest
import com.manjugroups.m_connect.network.LensEnrollResponse
import com.manjugroups.m_connect.network.LensRetakeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface LensUploadState {
    data object Idle : LensUploadState
    data object Uploading : LensUploadState
    data class Done(val response: LensEnrollResponse) : LensUploadState
    data class Failed(val outcome: LensErrors.Outcome) : LensUploadState
}

data class LensEnrollUiState(
    val reviewing: Boolean = false,
    val photos: Map<LensPose, LensCapturedPhoto> = emptyMap(),
    val skipped: Set<LensPose> = emptySet(),
    /** Retaking one pose from the review screen: go back to review after it. */
    val retakeOnly: LensPose? = null,
    /** Retake of one saved photo: the whole session is just this pose. */
    val single: LensPose? = null,
    val replace: Boolean = false,
    val upload: LensUploadState = LensUploadState.Idle,
    /** Per-pose reasons from the last upload (failed[] list). */
    val failedByPose: Map<LensPose, String> = emptyMap(),
    /** Failures Lens returned without a recognisable pose (shown in the result card). */
    val otherFailures: List<String> = emptyList(),
) {
    /** Next pose to ask for, in guided order. */
    val target: LensPose?
        get() = retakeOnly?.takeIf { it !in photos }
            ?: if (single != null) single.takeIf { it !in photos }
            else LensPose.guidedOrder.firstOrNull { it !in photos && it !in skipped }

    val pending: Set<LensPose>
        get() = (retakeOnly ?: single)?.let { if (it in photos) emptySet() else setOf(it) }
            ?: LensPose.entries.filter { it !in photos && it !in skipped }.toSet()

    val requiredDone: Boolean get() = single?.let { it in photos } ?: LensPose.required.all { it in photos }
    val allDone: Boolean get() = pending.isEmpty()
}

class LensEnrollViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _state = MutableStateFlow(LensEnrollUiState())
    val state: StateFlow<LensEnrollUiState> = _state.asStateFlow()

    private var initialised = false

    /**
     * [single]: retake of one saved photo (only that pose, other poses shown as
     * skipped). [replace]: "Retake all" starts with Replace on; otherwise it
     * never starts on (no silent replace).
     */
    fun init(existingSamples: Int, replace: Boolean, single: LensPose?) {
        if (initialised) return
        initialised = true
        _state.update {
            it.copy(
                replace = replace && single == null,
                single = single,
                skipped = if (single != null) LensPose.entries.filter { p -> p != single }.toSet() else emptySet(),
            )
        }
        if (existingSamples <= 0 && !replace) _state.update { it.copy(replace = false) }
    }

    /** Called from the camera thread. */
    fun onCaptured(photo: LensCapturedPhoto) {
        _state.update { s ->
            if (photo.pose !in s.pending) return@update s
            val photos = s.photos + (photo.pose to photo)
            val next = s.copy(photos = photos, failedByPose = s.failedByPose - photo.pose)
            val finishedRetake = s.retakeOnly != null && s.retakeOnly in photos
            if (finishedRetake) next.copy(retakeOnly = null, reviewing = true)
            else if (next.allDone) next.copy(reviewing = true)
            else next
        }
    }

    fun skip(pose: LensPose) {
        if (pose.required) return
        _state.update { s ->
            val next = s.copy(skipped = s.skipped + pose)
            if (next.allDone && next.requiredDone) next.copy(reviewing = true) else next
        }
    }

    /**
     * Review from the first photo on: missing angles show Capture there (and
     * come back to review); upload still needs the required angles.
     */
    fun review() {
        _state.update { s ->
            when {
                s.photos.isEmpty() -> s
                s.requiredDone -> s.copy(reviewing = true, skipped = s.skipped + s.pending.filter { !it.required })
                else -> s.copy(reviewing = true)
            }
        }
    }

    fun retake(pose: LensPose) {
        _state.update { s ->
            s.copy(
                photos = s.photos - pose,
                skipped = s.skipped - pose,
                retakeOnly = pose,
                reviewing = false,
                upload = LensUploadState.Idle,
            )
        }
    }

    /** Start over: every pose again (after "record again"). */
    fun recordAgain() {
        _state.update { s ->
            LensEnrollUiState(
                replace = s.replace,
                single = s.single,
                skipped = if (s.single != null) s.skipped else emptySet(),
            )
        }
    }

    fun setReplace(replace: Boolean) = _state.update { it.copy(replace = replace) }

    fun clearResult() = _state.update { it.copy(upload = LensUploadState.Idle) }

    fun upload(token: String, staffId: String) {
        val s = _state.value
        if (s.upload is LensUploadState.Uploading || !s.requiredDone) return
        _state.update { it.copy(upload = LensUploadState.Uploading, failedByPose = emptyMap(), otherFailures = emptyList()) }
        viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.Default) {
                    LensPose.guidedOrder.mapNotNull { pose ->
                        val p = s.photos[pose] ?: return@mapNotNull null
                        LensEnrollPhoto(
                            pose = pose.key,
                            mimeType = "image/jpeg",
                            dataBase64 = Base64.encodeToString(fitUnderLimit(p.jpeg), Base64.NO_WRAP),
                        )
                    }
                }
                val response = if (s.single != null && photos.size == 1) {
                    api.retakeLensPhoto(token, LensRetakeRequest(staffId, photos.first()))
                } else {
                    api.enrollLensFaces(token, LensEnrollRequest(staffId, s.replace, photos))
                }
                val body = response.body()
                if (response.isSuccessful && body != null && body.ok != false && body.success != false) {
                    val all = body.failed.orEmpty().map { f ->
                        LensPose.fromKey(f.pose) to (f.message?.takeIf { it.isNotBlank() } ?: LensErrors.photoMessageFor(f.code))
                    }
                    val failed = all.mapNotNull { (pose, msg) -> pose?.let { it to msg } }.toMap()
                    val other = all.filter { it.first == null }.map { it.second }
                    _state.update { it.copy(upload = LensUploadState.Done(body), failedByPose = failed, otherFailures = other) }
                } else {
                    val raw = runCatching { response.errorBody()?.string() }.getOrNull()
                    val outcome = LensErrors.parse(response.code(), raw)
                    _state.update {
                        it.copy(
                            upload = LensUploadState.Failed(outcome),
                            failedByPose = outcome.failed.mapNotNull { f -> f.pose?.let { p -> p to f.message } }.toMap(),
                            otherFailures = outcome.failed.filter { it.pose == null }.map { it.message },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val notice = ApiErrors.noticeFor(e, "Couldn't reach the server.")
                _state.update {
                    it.copy(
                        upload = LensUploadState.Failed(
                            LensErrors.Outcome("NETWORK", notice.text, LensErrors.Action.RETRY, emptyList()),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Keeps each picture under the Lens 2 MB guidance. Lens wants JPEG quality
     * 90+, so an oversized picture is scaled down (the face stays far above
     * the 80 px minimum), never compressed harder. Rare: frames are ~1280 px.
     */
    private fun fitUnderLimit(jpeg: ByteArray): ByteArray {
        if (jpeg.size <= MAX_BYTES) return jpeg
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        var out = jpeg
        repeat(4) {
            if (out.size <= MAX_BYTES) return@repeat
            val scaled = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * 0.85f).toInt(), (bitmap.height * 0.85f).toInt(), true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            out = stream.toByteArray()
        }
        bitmap.recycle()
        return out
    }

    private companion object {
        const val MAX_BYTES = 1_900_000
    }
}
