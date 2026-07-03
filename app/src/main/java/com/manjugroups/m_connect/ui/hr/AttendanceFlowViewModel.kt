package com.manjugroups.m_connect.ui.hr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.TrackingBootstrapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AttendanceFlowState(
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    /** True once authoritative attendance has been fetched from the server
     *  at least once. Distinguishes the initial/default state (nothing
     *  loaded yet, every flag still at its default) from a genuine "not
     *  clocked in" server result. UI must not act destructively on the
     *  empty default — e.g. wiping a persisted on-duty session before we
     *  actually know today's clock state, which lost the on-duty trip on
     *  every cold start / relaunch. */
    val hasLoaded: Boolean = false,
    /** True only while an open session exists right now. Drives the live
     *  ticker and the blue "You're Clocked In" / "Clocked Out" header on
     *  the Attendance tab. Flips back to false after every clock-out. */
    val isClockedIn: Boolean = false,
    /** True for the rest of the day once the user has punched in at least
     *  once (whether the current session is open or already closed). Used
     *  by feature surfaces — CP cards, trip start, GeoTrack — that must
     *  not block work just because the user happens to be mid-break after
     *  a clock-out. Mirrors `AttendanceTrackingGate.isClockedInForToday`. */
    val hasClockedInToday: Boolean = false,
    val todayMinutes: Int = 0,
    val todayHours: String = "00:00 Hrs",
    val latestTotalHours: String = "00:00:00 hrs",
    val latestRange: String = "--",
    /** ISO timestamp of the first punch-in today, used to drive a live ticker. */
    val firstPunchInIso: String? = null,
    /** ISO timestamp of the most recent punch-out today. Surfaced on the
     *  attendance card so the operator sees both ends of today's session
     *  without scrolling into the history list below. */
    val lastPunchOutIso: String? = null,
    /** Sum of today's already-closed session minutes. While clocked-in we
     *  still tick `now - firstPunchIn` for live display, but on punch-out
     *  this becomes the source of truth. */
    val closedTodayMinutes: Int = 0,
    val payPeriodLabel: String = "",
    val payPeriodMinutes: Int = 0,
    val payPeriodHours: String = "00:00 Hrs",
)

enum class PunchMode {
    PUNCH_IN,
    PUNCH_OUT,
}

sealed interface AttendanceFlowEvent {
    data class Loading(val mode: PunchMode) : AttendanceFlowEvent
    /** Optimistic — emitted as soon as input validation passes, before upload completes. */
    data class Success(val mode: PunchMode, val message: String) : AttendanceFlowEvent
    /** Pre-flight validation error (no submission attempted). */
    data class Error(val mode: PunchMode, val message: String) : AttendanceFlowEvent
    /** Background upload/punch failed after optimistic Success was already emitted. */
    data class SubmissionFailed(val mode: PunchMode, val message: String) : AttendanceFlowEvent
}

class AttendanceFlowViewModel(
    private val api: ApiService = ApiService.create(),
    private val geoApi: GeoTrackApi = GeoTrackApi.create(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AttendanceFlowState())
    val uiState: StateFlow<AttendanceFlowState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AttendanceFlowEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AttendanceFlowEvent> = _events

    fun loadTodayAttendance(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val todayResp = api.getMyAttendanceToday(token)
                val dayResp = runCatching { api.getDaySessions(token) }.getOrNull()

                val attendance = if (todayResp.success) todayResp.attendance else null
                val totalMinutes = attendance?.totalMinutes ?: 0

                val firstPunchIn = dayResp?.firstPunchIn ?: attendance?.firstPunchIn
                val lastPunchOut = dayResp?.lastPunchOut ?: attendance?.lastPunchOut
                val hasOpenSession = attendance?.hasOpenSession == true ||
                    dayResp?.hasOpenSession == true
                // `isClockedIn` reflects whether an open session exists right
                // now — it drives the live "today" ticker and the clocked-in
                // header. It is NOT used to gate the Clock Out button anymore:
                // per the one-time-Clock-In rule the button stays "Clock Out"
                // all day, and the server now accepts a Clock Out with no open
                // session by re-stamping the day's last punch-out (the last
                // tap wins, locked at midnight) instead of rejecting it.
                val isClockedInForUi = hasOpenSession
                val hasClockedInTodayForUi =
                    AttendanceTrackingGate.isClockedInForToday(firstPunchIn, hasOpenSession)
                val range = buildRangeLabel(firstPunchIn, lastPunchOut, isClockedInForUi)

                val aggregateMinutes = dayResp?.cumulativeMinutes ?: totalMinutes

                // Pay period = current calendar month sum
                val (periodLabel, periodMinutes) = loadCurrentMonthSummary(token)

                _uiState.value = AttendanceFlowState(
                    isLoading = false,
                    isSubmitting = false,
                    hasLoaded = true,
                    isClockedIn = isClockedInForUi,
                    hasClockedInToday = hasClockedInTodayForUi,
                    todayMinutes = totalMinutes,
                    todayHours = formatMinutesForToday(totalMinutes),
                    latestTotalHours = formatMinutesForPeriod(aggregateMinutes),
                    latestRange = range,
                    firstPunchInIso = firstPunchIn,
                    lastPunchOutIso = lastPunchOut,
                    closedTodayMinutes = totalMinutes,
                    payPeriodLabel = periodLabel,
                    payPeriodMinutes = periodMinutes,
                    payPeriodHours = formatMinutesForToday(periodMinutes),
                )
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Fetches the current calendar month's daily attendance and returns
     * (humanLabel, summedMinutes). Falls back to today-only on any failure.
     */
    private suspend fun loadCurrentMonthSummary(token: String): Pair<String, Int> {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = java.util.Calendar.getInstance(tz)
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val from = ymd.format(cal.time)
        val firstDate = cal.time
        cal.set(
            java.util.Calendar.DAY_OF_MONTH,
            cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        )
        val to = ymd.format(cal.time)
        val lastDate = cal.time

        val labelFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
            timeZone = tz
        }
        val label = "Paid Period ${labelFmt.format(firstDate)} - ${labelFmt.format(lastDate)}"

        val summed = try {
            val resp = api.getMyAttendance(token, fromDate = from, toDate = to)
            resp.records.sumOf { it.totalMinutes ?: 0 }
        } catch (_: Exception) {
            0
        }
        return label to summed
    }

    fun punchIn(
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
        context: Context? = null,
        remarks: String? = null,
    ) {
        submitPunch(
            mode = PunchMode.PUNCH_IN,
            token = token,
            latitude = latitude,
            longitude = longitude,
            address = address,
            selfieFile = selfieFile,
            deviceId = deviceId,
            context = context,
            remarks = remarks,
        )
    }

    fun punchOut(
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
        context: Context? = null,
        remarks: String? = null,
    ) {
        submitPunch(
            mode = PunchMode.PUNCH_OUT,
            token = token,
            latitude = latitude,
            longitude = longitude,
            address = address,
            selfieFile = selfieFile,
            deviceId = deviceId,
            context = context,
            remarks = remarks,
        )
    }


    private fun submitPunch(
        mode: PunchMode,
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
        context: Context?,
        remarks: String? = null,
    ) {
        if (selfieFile == null || !selfieFile.exists()) {
            viewModelScope.launch {
                _events.emit(AttendanceFlowEvent.Error(mode, "Selfie photo is required."))
            }
            return
        }
        if (latitude == null || longitude == null) {
            viewModelScope.launch {
                _events.emit(AttendanceFlowEvent.Error(mode, "Valid GPS location is required."))
            }
            return
        }

        // We used to emit Success optimistically before the API call to make
        // the UI feel instant. That's wrong for punch: when the server rejects
        // (e.g. HTTP 500 "No active punch-in found for today"), the user
        // still saw a "Clock out successful" sheet because the early Success
        // had already fired. Now we wait for the actual response and only
        // emit Success when the server confirms it.
        val previousState = _uiState.value
        _uiState.update {
            it.copy(
                isSubmitting = true,
                isClockedIn = mode == PunchMode.PUNCH_IN,
                // Once the user has punched in today, hasClockedInToday is
                // sticky — clock-outs do NOT reset it. A fresh PUNCH_IN of
                // course flips it on. This matches the one-time-Clock-In
                // rule used everywhere outside the live ticker.
                hasClockedInToday = it.hasClockedInToday || mode == PunchMode.PUNCH_IN,
            )
        }

        viewModelScope.launch {
            _events.emit(AttendanceFlowEvent.Loading(mode))

            try {
                val compressed = withContext(Dispatchers.IO) {
                    runCatching { compressSelfie(selfieFile) }.getOrDefault(selfieFile)
                }
                val storageId = uploadSelfie(token, compressed)
                if (storageId.isNullOrBlank()) {
                    rollbackOptimistic(previousState)
                    _events.emit(
                        AttendanceFlowEvent.SubmissionFailed(
                            mode,
                            "Failed to upload selfie. Please try again.",
                        ),
                    )
                    return@launch
                }

                val request = PunchRequest(
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    photo = storageId,
                    deviceId = deviceId,
                    source = "mobile",
                    remarks = remarks?.takeIf { it.isNotBlank() },
                )

                val response = if (mode == PunchMode.PUNCH_IN) {
                    api.punchIn(token, request)
                } else {
                    api.punchOut(token, request)
                }

                if (!response.success) {
                    rollbackOptimistic(previousState)
                    _events.emit(
                        AttendanceFlowEvent.SubmissionFailed(
                            mode,
                            response.error ?: "Punch request failed.",
                        ),
                    )
                    return@launch
                }

                context?.let { ctx ->
                    val appCtx = ctx.applicationContext
                    if (mode == PunchMode.PUNCH_OUT) {
                        // Clock-out closes the GeoTrack window immediately. Don't
                        // route through the server bootstrap on punch-out — it can
                        // momentarily still report shouldTrack and let post-clock-out
                        // travel leak onto the map. Flip shouldTrackNow off (blocks a
                        // START_STICKY restart) and stop the service. We deliberately
                        // KEEP activeTrackingSessionId so the service's final sync can
                        // still flush the in-window tail captured since the last cycle;
                        // no new points are captured because stop() removes the
                        // location updates immediately. The service's own clock-in
                        // gate is the backstop for non-app clock-outs (biometric/
                        // midnight) and for any bootstrap-driven restart.
                        SessionManager(appCtx).shouldTrackNow = false
                        GeoTrackService.stop(appCtx)
                    } else {
                        val bootstrap = response.trackingBootstrap ?: runCatching {
                            geoApi.getTrackingBootstrap(token, deviceId ?: SessionManager(ctx).trackingDeviceId).data
                        }.getOrNull()
                        applyTrackingBootstrap(
                            context = appCtx,
                            bootstrap = bootstrap,
                            attendanceActive = true,
                        )
                    }
                }

                _uiState.update { it.copy(isSubmitting = false) }
                _events.emit(
                    AttendanceFlowEvent.Success(
                        mode,
                        if (mode == PunchMode.PUNCH_IN) "Punched in successfully." else "Punched out successfully.",
                    ),
                )
                loadTodayAttendance(token)
            } catch (e: Exception) {
                rollbackOptimistic(previousState)
                val message = extractHttpErrorMessage(e) ?: e.message
                    ?: "Network error while submitting punch."
                _events.emit(AttendanceFlowEvent.SubmissionFailed(mode, message))
                // If the server says there's no active punch-in for today, the
                // client's `isClockedIn` is stale (e.g. session was auto-closed
                // overnight, or never opened). Pull the truth from the server
                // so the dashboard button flips back to "Clock In".
                if (message.contains("No active punch-in", ignoreCase = true)) {
                    loadTodayAttendance(token)
                }
            }
        }
    }

    /**
     * Convex returns its handler-thrown errors as HTTP 5xx with a JSON body
     * like `{ "code": "...", "message": "No active punch-in found for today" }`.
     * Retrofit gives us an HttpException — try to surface that nested message
     * instead of the generic "HTTP 500" Retrofit toString.
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        // Prefer parsing the standard {message:..., error:...} fields without
        // pulling in extra deps. Fall through to the raw body if parsing fails.
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val msg = obj.get("message")?.asString
                ?: obj.get("error")?.asString
            msg?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun rollbackOptimistic(previous: AttendanceFlowState) {
        _uiState.update {
            it.copy(
                isSubmitting = false,
                isClockedIn = previous.isClockedIn,
                hasClockedInToday = previous.hasClockedInToday,
            )
        }
    }

    private suspend fun uploadSelfie(token: String, file: File): String? {
        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaType())
            val response = api.uploadStorageFile(token, requestBody)
            response.storageId
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-encodes the selfie to a max 1080px JPEG at quality 80 with EXIF rotation baked in.
     * Typical 3-5MB camera output drops to ~150-300KB → 10x faster upload on slow networks.
     * Falls back to the original file if anything goes wrong.
     */
    private fun compressSelfie(source: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return source

        val maxEdge = 1080
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / sample > maxEdge * 2 || h / sample > maxEdge * 2) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return source

        val rotated = applyExifRotation(source, decoded)

        val scale = minOf(
            1f,
            maxEdge.toFloat() / rotated.width.toFloat(),
            maxEdge.toFloat() / rotated.height.toFloat(),
        )
        val finalBitmap = if (scale < 1f) {
            val scaledW = (rotated.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (rotated.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, scaledW, scaledH, true).also {
                if (it !== rotated) rotated.recycle()
            }
        } else rotated

        val out = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        finalBitmap.recycle()

        val target = File(source.parentFile, "punch_compressed_${System.currentTimeMillis()}.jpg")
        FileOutputStream(target).use { it.write(out.toByteArray()) }
        return target
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val degrees = try {
            val exif = ExifInterface(source.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun applyTrackingBootstrap(
        context: Context,
        bootstrap: TrackingBootstrapData?,
        attendanceActive: Boolean,
    ) {
        GeoTrackBootstrapSync.apply(context, bootstrap, allowPromptConsent = attendanceActive)
    }

    companion object {
        internal fun formatMinutesForToday(totalMinutes: Int): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format(Locale.US, "%02d:%02d Hrs", hours, minutes)
        }

        internal fun formatMinutesForPeriod(totalMinutes: Int): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format(Locale.US, "%02d:%02d:00 hrs", hours, minutes)
        }

        internal fun buildRangeLabel(
            firstPunchIn: String?,
            lastPunchOut: String?,
            hasOpenSession: Boolean,
        ): String {
            val inLabel = firstPunchIn?.let { formatIsoToTime(it) } ?: "--"
            val outLabel = when {
                lastPunchOut != null -> formatIsoToTime(lastPunchOut)
                hasOpenSession -> "--"
                else -> "--"
            }
            return "$inLabel - $outLabel"
        }

        internal fun shouldTreatAsClockedIn(
            firstPunchIn: String?,
            hasOpenSession: Boolean,
        ): Boolean {
            return AttendanceTrackingGate.isClockedInForToday(firstPunchIn, hasOpenSession)
        }

        internal fun formatIsoToTime(iso: String): String {
            val millis = parseMillis(iso) ?: return "--"
            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return formatter.format(Date(millis))
        }

        private fun parseMillis(iso: String): Long? {
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US)
                    return fmt.parse(iso)?.time
                } catch (_: Exception) {
                    // try next format
                }
            }
            return try {
                val fallback = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fallback.timeZone = TimeZone.getTimeZone("UTC")
                fallback.parse(iso.substringBefore("Z"))?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
