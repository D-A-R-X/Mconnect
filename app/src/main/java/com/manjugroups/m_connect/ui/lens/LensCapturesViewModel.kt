package com.manjugroups.m_connect.ui.lens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiErrors
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.LensCaptureItem
import com.manjugroups.m_connect.network.LensDeleteRequest
import com.manjugroups.m_connect.network.LensEnrollmentInfo
import com.manjugroups.m_connect.network.LensPendingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LensCapturesUiState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val captures: List<LensCaptureItem> = emptyList(),
    val enrollment: LensEnrollmentInfo? = null,
    val error: String? = null,
    /** "send", "all" or a captureId while that action runs. */
    val busy: String? = null,
) {
    /** Newest live photo of each pose (the server lists newest first). */
    fun photoFor(pose: LensPose): LensCaptureItem? = captures.firstOrNull { it.pose == pose.key }
    val waiting: Int get() = captures.count { it.lensStatus != "enrolled" }
}

/** HR > Lens Enrollment > a staff member's saved photos: Send to Lens, delete. */
class LensCapturesViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _state = MutableStateFlow(LensCapturesUiState())
    val state: StateFlow<LensCapturesUiState> = _state.asStateFlow()

    fun load(token: String, staffId: String, silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = !it.loaded, error = null) }
        viewModelScope.launch {
            try {
                val res = api.getLensCaptures(token, staffId)
                val body = res.body()
                if (res.isSuccessful && body != null && body.success != false) {
                    _state.update {
                        it.copy(
                            loading = false,
                            loaded = true,
                            captures = body.captures.orEmpty().filter { c -> !c.captureId.isNullOrBlank() },
                            enrollment = body.enrollment,
                            error = null,
                        )
                    }
                } else {
                    val message = LensErrors.parse(res.code(), runCatching { res.errorBody()?.string() }.getOrNull()).message
                    _state.update { it.copy(loading = false, error = message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ApiErrors.noticeFor(e, "Couldn't load the photos.").text) }
            }
        }
    }

    /** Sends the waiting photos to Lens (no re-recording). [onDone] gets (success, message). */
    fun sendPending(token: String, staffId: String, onDone: (Boolean, String) -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "send") }
        viewModelScope.launch {
            val (ok, message) = try {
                val res = api.sendPendingLensPhotos(token, LensPendingRequest(staffId))
                val body = res.body()
                if (res.isSuccessful && body?.success != false) {
                    val notUsed = body?.failed.orEmpty().size
                    true to buildString {
                        append("Sent to Lens: ${body?.addedSamples ?: 0} added, ${body?.faceSamples ?: 0} photos for the cameras.")
                        if (notUsed > 0) append(" $notUsed not used.")
                    }
                } else {
                    false to LensErrors.parse(res.code(), runCatching { res.errorBody()?.string() }.getOrNull()).message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to ApiErrors.noticeFor(e, "Couldn't send the photos to Lens.").text
            }
            _state.update { it.copy(busy = null) }
            onDone(ok, message)
            load(token, staffId, silent = true)
        }
    }

    /** Deletes one saved photo ([captureId]) or all of them (null). Never touches Lens. */
    fun delete(token: String, staffId: String, captureId: String?, onDone: (Boolean, String) -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = captureId ?: "all") }
        viewModelScope.launch {
            val (ok, message) = try {
                val request = if (captureId != null) LensDeleteRequest(captureId = captureId)
                else LensDeleteRequest(staffId = staffId, all = true)
                val res = api.deleteLensCaptures(token, request)
                val body = res.body()
                if (res.isSuccessful && body?.success != false) {
                    val n = body?.deleted ?: 0
                    true to if (n == 1) "Photo deleted." else "$n photos deleted."
                } else {
                    false to LensErrors.parse(res.code(), runCatching { res.errorBody()?.string() }.getOrNull()).message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to ApiErrors.noticeFor(e, "Couldn't delete the photo.").text
            }
            _state.update { it.copy(busy = null) }
            onDone(ok, message)
            load(token, staffId, silent = true)
        }
    }
}
