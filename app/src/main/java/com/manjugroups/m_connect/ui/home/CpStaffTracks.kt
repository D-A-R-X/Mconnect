package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.geotrack.TrackCleaner
import com.manjugroups.m_connect.network.CpStaffTrackDto
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SnappedRoute
import com.manjugroups.m_connect.network.TimelinePoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The CP trip map's view of the staff who are ON a CP (both on a Joint CP):
 * their latest heartbeat fix, and the path + distance they travelled for this
 * trip. Read-only display data; nothing here touches the trip, outcome,
 * punch or GeoTrack service flows.
 */
data class CpStaffTrack(
    val staffId: String,
    val name: String,
    val isPrimary: Boolean,
    /** Server-assigned Joint CP route colour ("#RRGGBB"), when there is one. */
    val routeColor: String?,
    /**
     * The travelled path as separate pieces, each drawn as its own polyline.
     * Pieces are never joined: a gap (no signal, overnight, a GPS jump) is
     * left open instead of being bridged by a straight line.
     */
    val segments: List<Segment>,
    /** Distance along the drawn segments only (or the recorded trip distance). */
    val distanceMeters: Double?,
    val live: LiveFix?,
    val pathState: PathState,
    /** The trip has started and is not completed yet. */
    val tripInProgress: Boolean,
    /** Trip window, for "since 22 Nov" when it spans more than one day. */
    val windowStartMs: Long?,
    val windowEndMs: Long?,
    /** Segments come from the geo service's road-matched route. */
    val roadMatched: Boolean = false,
) {
    data class LiveFix(
        val lat: Double,
        val lng: Double,
        val lastSeenMs: Long,
        val trackingActive: Boolean,
        /** Motion read from this fix; shown only through [CpStaffTrackPolicy.motionToShow]. */
        val motion: Motion? = null,
    )

    enum class Motion(val label: String) {
        MOVING("Moving"),
        WALKING("Walking"),
        IDLE("Idle"),
    }

    data class Segment(val points: List<TrackCleaner.Point>, val dashed: Boolean = false)

    val hasPath: Boolean get() = segments.any { it.points.size >= 2 }

    enum class PathState {
        /** At least one segment with two or more fixes is drawn. */
        SHOWN,
        /** The trip has not started, so there is no trip path yet. */
        NOT_STARTED,
        /** Started, but no fixes were recorded in the trip window. */
        NO_POINTS,
        /** This viewer may not read the path on this server version. */
        NOT_AVAILABLE,
    }
}

/** A staff member known to be on the CP, from the CP detail payload. */
data class CpInvolvedStaff(
    val staffId: String,
    val name: String?,
    val isPrimary: Boolean,
    val routeColor: String?,
    val startedAt: Long?,
    val completedAt: Long?,
    val recordedDistanceMeters: Double?,
)

/** One road-matched piece from the geo service's snapped-route, decoded. */
data class CpSnapPiece(
    val kind: String?,
    val startMs: Long?,
    val endMs: Long?,
    val points: List<TrackCleaner.Point>,
)

/** Pure rules, kept free of Android types so they are unit-tested on the JVM. */
object CpStaffTrackPolicy {

    /** A heartbeat this old is shown as "last seen", not live. */
    const val LIVE_FRESH_MS = 10 * 60_000L

    /** A silence this long between fixes that also moved this far is a gap, not a road. */
    const val GAP_MS = 10 * 60_000L
    const val GAP_MIN_METERS = 300.0

    /** Faster than this between two fixes is a jump, never a drawn line. */
    const val MAX_SEGMENT_SPEED_MPS = 55.0

    /** Snapped-route is read per IST day; long-open trips read only the latest days. */
    const val MAX_SNAP_DAYS = 4

    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    data class Window(val start: Long, val end: Long, val open: Boolean)

    /**
     * Trip window: trip start to completion, or to [now] while it runs.
     * Null before the trip starts.
     */
    fun window(startedAt: Long?, completedAt: Long?, now: Long): Window? {
        if (startedAt == null || startedAt <= 0L) return null
        val finished = completedAt != null && completedAt >= startedAt
        return Window(startedAt, if (finished) completedAt!! else maxOf(startedAt, now), open = !finished)
    }

    /**
     * Whether a heartbeat fix belongs on this map: tracking is running right
     * now (so the staff member is inside their clock-in → clock-out window),
     * or the fix was recorded during this trip. An old fix from another duty
     * is never presented as the staff member's location.
     */
    fun liveFixVisible(lat: Double?, lng: Double?, lastSeenMs: Long?, trackingActive: Boolean, window: Window?): Boolean {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return false
        if (lat == 0.0 && lng == 0.0) return false
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
        if (lastSeenMs == null || lastSeenMs <= 0L) return false
        if (trackingActive) return true
        return window != null && lastSeenMs in window.start..window.end
    }

    /**
     * The geo service only lets admins and attendance.liveTracking holders
     * read another staff member's route; everyone else silently gets their
     * OWN route back. So the direct read is only used where it is safe.
     */
    fun canReadRouteDirectly(staffId: String, viewerStaffId: String?, viewerCanViewAllTracking: Boolean): Boolean =
        viewerCanViewAllTracking || (!viewerStaffId.isNullOrBlank() && staffId == viewerStaffId)

    /** Viewer is one of the staff on this CP (their own phone location matters). */
    fun viewerIsInvolved(viewerStaffId: String?, involvedStaffIds: Collection<String>): Boolean =
        !viewerStaffId.isNullOrBlank() && involvedStaffIds.any { it == viewerStaffId }

    /** Fixes inside the window, cleaned (spikes, jumps and stays removed). */
    fun cleanPath(fixes: List<TrackCleaner.Fix>, window: Window): List<TrackCleaner.Point> =
        TrackCleaner.clean(
            fixes.filter { it.atMs != null && it.atMs in window.start..window.end }
                .sortedBy { it.atMs },
        )

    /**
     * Splits a cleaned path wherever the line between two fixes would not be
     * real travel: a long silence that also moved (overnight, no signal), or
     * an impossible speed. Each piece is drawn on its own; pieces with fewer
     * than two fixes are dropped.
     */
    fun splitAtGaps(points: List<TrackCleaner.Point>): List<List<TrackCleaner.Point>> {
        val pieces = mutableListOf<List<TrackCleaner.Point>>()
        var current = mutableListOf<TrackCleaner.Point>()
        for (point in points) {
            val previous = current.lastOrNull()
            if (previous != null && isBreak(previous, point)) {
                if (current.size >= 2) pieces += current
                current = mutableListOf()
            }
            current += point
        }
        if (current.size >= 2) pieces += current
        return pieces
    }

    private fun isBreak(a: TrackCleaner.Point, b: TrackCleaner.Point): Boolean {
        val meters = TrackCleaner.distanceM(a.lat, a.lng, b.lat, b.lng)
        val aAt = a.atMs
        val bAt = b.atMs
        if (aAt == null || bAt == null) return meters > 1_000.0
        val dt = bAt - aAt
        if (dt <= 0L) return meters > GAP_MIN_METERS
        if (dt > GAP_MS && meters > GAP_MIN_METERS) return true
        return meters / (dt / 1000.0) > MAX_SEGMENT_SPEED_MPS
    }

    fun rawSegments(fixes: List<TrackCleaner.Fix>, window: Window): List<CpStaffTrack.Segment> =
        splitAtGaps(cleanPath(fixes, window)).map { CpStaffTrack.Segment(it) }

    /**
     * Road-matched pieces that overlap the trip window. "gap" pieces are the
     * service's own bridge over missing data, so they are not drawn; an
     * "estimated" piece is drawn dashed.
     */
    fun snappedSegments(pieces: List<CpSnapPiece>, window: Window): List<CpStaffTrack.Segment> =
        pieces
            .filter { it.kind != "gap" && it.points.size >= 2 }
            .filter { (it.startMs ?: Long.MIN_VALUE) <= window.end && (it.endMs ?: Long.MAX_VALUE) >= window.start }
            .sortedBy { it.startMs ?: 0L }
            .map { CpStaffTrack.Segment(it.points, dashed = it.kind == "estimated") }

    /** Distance along the drawn segments only; never across a gap between them. */
    fun segmentsDistanceMeters(segments: List<CpStaffTrack.Segment>): Double? {
        val drawn = segments.filter { it.points.size >= 2 }
        if (drawn.isEmpty()) return null
        return drawn.sumOf { segment ->
            var total = 0.0
            for (i in 1 until segment.points.size) {
                val a = segment.points[i - 1]
                val b = segment.points[i]
                total += TrackCleaner.distanceM(a.lat, a.lng, b.lat, b.lng)
            }
            total
        }
    }

    /** IST calendar days the window touches, oldest first, at most [max] (the latest ones). */
    fun istDates(window: Window, max: Int = MAX_SNAP_DAYS): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = IST }
        val dates = mutableListOf<String>()
        var at = window.start
        while (at <= window.end && dates.size < 400) {
            val date = format.format(Date(at))
            if (dates.lastOrNull() != date) dates += date
            at += 6 * 60 * 60_000L
        }
        val last = format.format(Date(window.end))
        if (dates.lastOrNull() != last) dates += last
        return dates.takeLast(max)
    }

    fun sameIstDay(a: Long, b: Long): Boolean {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = IST }
        return format.format(Date(a)) == format.format(Date(b))
    }

    /**
     * "3.4 km travelled", or "82.0 km travelled since 22 Nov" when the trip
     * window started on an earlier day, so a multi-day total is not read as
     * one ride.
     */
    fun distanceLabel(meters: Double?, sinceMs: Long? = null, now: Long = System.currentTimeMillis()): String {
        if (meters == null || !meters.isFinite() || meters < 0) return "Distance —"
        val base = if (meters < 1000) "${meters.roundToInt()} m travelled"
        else String.format(Locale.US, "%.1f km travelled", meters / 1000.0)
        if (sinceMs == null || sinceMs <= 0L || sameIstDay(sinceMs, now)) return base
        val day = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = IST }.format(Date(sinceMs))
        return "$base since $day"
    }

    /** Clock time today, otherwise day + time, in IST. */
    fun clockLabel(ms: Long, now: Long): String {
        val pattern = if (sameIstDay(ms, now)) "hh:mm a" else "d MMM, hh:mm a"
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = IST }.format(Date(ms))
    }

    /**
     * Motion from what the live row already carries, most trusted first: the
     * geo service's movementMode, then the phone's activity-recognition
     * label, then speed. Null when none of them says anything usable (an
     * "anomaly" GPS jump, TILTING / UNKNOWN with no speed): nothing is
     * guessed.
     */
    fun motionFrom(movementMode: String?, activity: String?, speedMps: Double?): CpStaffTrack.Motion? {
        when (movementMode?.trim()?.lowercase()) {
            "stationary", "still", "idle" -> return CpStaffTrack.Motion.IDLE
            "walking", "running", "on_foot" -> return CpStaffTrack.Motion.WALKING
            "vehicle", "two_wheeler", "four_wheeler", "cycling", "in_vehicle", "driving" -> return CpStaffTrack.Motion.MOVING
            "anomaly" -> return null
        }
        when (activity?.trim()?.uppercase()) {
            "STILL" -> return CpStaffTrack.Motion.IDLE
            "WALKING", "ON_FOOT", "RUNNING" -> return CpStaffTrack.Motion.WALKING
            "IN_VEHICLE", "ON_BICYCLE", "CYCLING" -> return CpStaffTrack.Motion.MOVING
        }
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return when {
            speed > MAX_SEGMENT_SPEED_MPS -> null
            speed < 0.5 -> CpStaffTrack.Motion.IDLE
            speed < 2.5 -> CpStaffTrack.Motion.WALKING
            else -> CpStaffTrack.Motion.MOVING
        }
    }

    /**
     * The motion status to show, or null. Only while this staff member's trip
     * on this CP is running (started, not completed), and only from a live,
     * fresh fix: before the trip, after it, or on a stale fix nothing shows.
     */
    fun motionToShow(track: CpStaffTrack, now: Long): CpStaffTrack.Motion? {
        if (!track.tripInProgress) return null
        val live = track.live ?: return null
        if (!live.trackingActive) return null
        if (now - live.lastSeenMs > LIVE_FRESH_MS) return null
        return live.motion
    }

    fun locationLabel(live: CpStaffTrack.LiveFix?, now: Long, timeFormat: (Long) -> String): String {
        if (live == null) return "No recent location"
        val age = (now - live.lastSeenMs).coerceAtLeast(0L)
        return if (live.trackingActive && age <= LIVE_FRESH_MS) {
            val minutes = age / 60_000L
            if (minutes < 1) "Live · just now" else "Live · $minutes min ago"
        } else {
            "Last seen ${timeFormat(live.lastSeenMs)}"
        }
    }

    /** Staff on the CP, from the CP detail: Joint participants, else the assigned field staff. */
    fun involvedStaff(cp: CpVisitDetail): List<CpInvolvedStaff> {
        val participants = cp.joint?.participants.orEmpty()
            .filter { !it.staffId.isNullOrBlank() }
            .distinctBy { it.staffId }
        if (participants.isNotEmpty()) {
            return participants
                .sortedByDescending { it.isPrimary }
                .map {
                    CpInvolvedStaff(
                        staffId = it.staffId!!,
                        name = it.staffName,
                        isPrimary = it.isPrimary,
                        routeColor = it.routeColor,
                        startedAt = it.startedAt,
                        completedAt = it.completedAt,
                        recordedDistanceMeters = it.distanceMeters,
                    )
                }
        }
        val staffId = cp.assignedStaffId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            CpInvolvedStaff(
                staffId = staffId,
                name = cp.assignedStaff?.name ?: cp.assignedStaff?.staffName,
                isPrimary = true,
                routeColor = null,
                startedAt = cp.fieldVisit?.startedAt,
                completedAt = cp.fieldVisit?.completedAt,
                recordedDistanceMeters = cp.fieldVisit?.distanceMeters,
            ),
        )
    }

    /**
     * When the trip really started, for the Trip Progress "Start" time: the
     * viewer's own Joint CP leg, else the CP's field visit, else the primary
     * participant. Null when unknown (then the time is hidden, never "now").
     */
    fun tripStartedAt(cp: CpVisitDetail, viewerStaffId: String?): Long? {
        val participants = cp.joint?.participants.orEmpty()
        val own = participants.firstOrNull { !viewerStaffId.isNullOrBlank() && it.staffId == viewerStaffId }?.startedAt
        return listOfNotNull(
            own,
            cp.fieldVisit?.startedAt,
            participants.firstOrNull { it.isPrimary }?.startedAt,
        ).firstOrNull { it > 0L }
    }

    fun fromServer(dto: CpStaffTrackDto, now: Long): CpStaffTrack? {
        val staffId = dto.staffId?.takeIf { it.isNotBlank() } ?: return null
        val window = window(dto.startedAt?.toLong(), dto.completedAt?.toLong(), now)
        val segments = window?.let { w ->
            rawSegments(
                dto.routePoints.orEmpty().map { TrackCleaner.Fix(it.lat, it.lng, atMs = it.recordedAt.toLong()) },
                w,
            )
        }.orEmpty()
        val liveDto = dto.live
        val liveMs = liveDto?.lastSeen?.toLong()
        val live = if (liveDto != null &&
            liveFixVisible(liveDto.lat, liveDto.lng, liveMs, liveDto.trackingActive == true, window)
        ) {
            CpStaffTrack.LiveFix(
                liveDto.lat!!, liveDto.lng!!, liveMs!!, liveDto.trackingActive == true,
                motion = motionFrom(liveDto.movementMode, liveDto.activity, liveDto.speed),
            )
        } else {
            null
        }
        val drawn = segmentsDistanceMeters(segments)
        val pathState = when {
            drawn != null -> CpStaffTrack.PathState.SHOWN
            window == null -> CpStaffTrack.PathState.NOT_STARTED
            dto.routeAvailable == false -> CpStaffTrack.PathState.NOT_AVAILABLE
            else -> CpStaffTrack.PathState.NO_POINTS
        }
        return CpStaffTrack(
            staffId = staffId,
            name = dto.staffName?.takeIf { it.isNotBlank() } ?: "Staff",
            isPrimary = dto.isPrimary == true,
            routeColor = dto.routeColor,
            segments = segments,
            distanceMeters = drawn ?: dto.recordedDistanceMeters,
            live = live,
            pathState = pathState,
            tripInProgress = window?.open == true,
            windowStartMs = window?.start,
            windowEndMs = window?.end,
        )
    }

    /** Swap a track's path for the road-matched one, when there is any. */
    fun withSnapped(track: CpStaffTrack, pieces: List<CpSnapPiece>): CpStaffTrack {
        val start = track.windowStartMs ?: return track
        val end = track.windowEndMs ?: return track
        val snapped = snappedSegments(pieces, Window(start, end, track.tripInProgress))
        val distance = segmentsDistanceMeters(snapped) ?: return track
        return track.copy(
            segments = snapped,
            distanceMeters = distance,
            pathState = CpStaffTrack.PathState.SHOWN,
            roadMatched = true,
        )
    }
}

/**
 * Loads [CpStaffTrack]s for one CP. Prefers the server's scoped
 * `staff-tracks` route; when that route is not deployed yet it falls back to
 * the direct geo reads, reading another staff member's path only where the
 * geo service will really return THAT staff member's rows. Where the viewer
 * may read the path directly, the road-matched snapped-route replaces the raw
 * fixes.
 */
class CpStaffTracksLoader(
    private val geoApi: GeoTrackApi,
    private val bearerToken: String,
) {
    sealed class Result {
        data class Loaded(val tracks: List<CpStaffTrack>) : Result()
        /** The server says this viewer may not see these tracks. */
        object Forbidden : Result()
        object Unavailable : Result()
    }

    suspend fun load(
        cpId: String,
        involved: List<CpInvolvedStaff>,
        viewerStaffId: String?,
        viewerCanViewAllTracking: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Result {
        var tracks: List<CpStaffTrack>? = null
        if (!serverRouteMissing) {
            val server = runCatching {
                withTimeout(TIMEOUT_MS) { geoApi.getCpStaffTracks(bearerToken, cpId) }
            }
            server.getOrNull()?.takeIf { it.success }?.data?.staff?.let { rows ->
                val parsed = rows.mapNotNull { CpStaffTrackPolicy.fromServer(it, now) }
                if (parsed.isNotEmpty() || involved.isEmpty()) tracks = parsed
            }
            val error = server.exceptionOrNull()
            if (error is HttpException && error.code() == 403) return Result.Forbidden
            // Not deployed on this server yet: stop asking for the rest of
            // this app session (each miss would otherwise log a 404).
            if (error is HttpException && error.code() == 404) serverRouteMissing = true
        }
        if (tracks == null) {
            if (involved.isEmpty()) return Result.Unavailable
            tracks = loadDirect(involved, viewerStaffId, viewerCanViewAllTracking, now)
        }
        return Result.Loaded(withRoadMatched(tracks!!, viewerStaffId, viewerCanViewAllTracking))
    }

    /** Road-matched path per IST day of the trip window, where the viewer may read it. */
    private suspend fun withRoadMatched(
        tracks: List<CpStaffTrack>,
        viewerStaffId: String?,
        viewerCanViewAllTracking: Boolean,
    ): List<CpStaffTrack> = coroutineScope {
        tracks.map { track ->
            async {
                val start = track.windowStartMs
                val end = track.windowEndMs
                if (snappedRouteMissing || start == null || end == null ||
                    !CpStaffTrackPolicy.canReadRouteDirectly(track.staffId, viewerStaffId, viewerCanViewAllTracking)
                ) {
                    return@async track
                }
                val dates = CpStaffTrackPolicy.istDates(CpStaffTrackPolicy.Window(start, end, track.tripInProgress))
                val pieces = dates.map { date ->
                    async {
                        val response = runCatching {
                            withTimeout(TIMEOUT_MS) {
                                geoApi.getSnappedRoute(bearerToken, staffId = track.staffId, date = date)
                            }
                        }
                        val error = response.exceptionOrNull()
                        if (error is HttpException && error.code() == 404) snappedRouteMissing = true
                        response.getOrNull()?.takeIf { it.success }?.data?.segments.orEmpty().map { segment ->
                            CpSnapPiece(
                                kind = segment.kind,
                                startMs = segment.startMs,
                                endMs = segment.endMs,
                                points = SnappedRoute.decode(segment.encodedPolyline.orEmpty())
                                    .map { TrackCleaner.Point(it.latitude, it.longitude, null) },
                            )
                        }
                    }
                }.awaitAll().flatten()
                CpStaffTrackPolicy.withSnapped(track, pieces)
            }
        }.awaitAll()
    }

    private suspend fun loadDirect(
        involved: List<CpInvolvedStaff>,
        viewerStaffId: String?,
        viewerCanViewAllTracking: Boolean,
        now: Long,
    ): List<CpStaffTrack> = coroutineScope {
        val liveRows = runCatching {
            withTimeout(TIMEOUT_MS) {
                geoApi.getLiveStatusForStaff(bearerToken, staffIds = involved.joinToString(",") { it.staffId })
            }
        }.getOrNull()?.data.orEmpty().filter { !it.staffId.isNullOrBlank() }.associateBy { it.staffId!! }

        involved.map { staff ->
            async {
                val window = CpStaffTrackPolicy.window(staff.startedAt, staff.completedAt, now)
                val canRead = CpStaffTrackPolicy.canReadRouteDirectly(staff.staffId, viewerStaffId, viewerCanViewAllTracking)
                var routeRead = false
                val segments = if (window != null && canRead) {
                    runCatching {
                        withTimeout(TIMEOUT_MS) {
                            geoApi.getSessionRoute(
                                bearerToken,
                                staffId = staff.staffId,
                                dayStart = window.start,
                                dayEnd = window.end,
                            )
                        }
                    }.getOrNull()?.takeIf { it.success }?.data?.let { data ->
                        // Defence in depth: never draw a route the service
                        // resolved for someone else.
                        val owner = data.session?.staffId
                        if (owner != null && owner != staff.staffId) return@let null
                        routeRead = true
                        CpStaffTrackPolicy.rawSegments(data.timeline.map(::toFix), window)
                    }.orEmpty()
                } else {
                    emptyList()
                }
                val row = liveRows[staff.staffId]
                val live = if (row != null &&
                    CpStaffTrackPolicy.liveFixVisible(row.lat, row.lng, row.lastSeen, row.trackingActive == true, window)
                ) {
                    CpStaffTrack.LiveFix(
                        row.lat!!, row.lng!!, row.lastSeen!!, row.trackingActive == true,
                        motion = CpStaffTrackPolicy.motionFrom(row.movementMode, row.activity, row.speed),
                    )
                } else {
                    null
                }
                val drawn = CpStaffTrackPolicy.segmentsDistanceMeters(segments)
                CpStaffTrack(
                    staffId = staff.staffId,
                    name = staff.name?.takeIf { it.isNotBlank() } ?: "Staff",
                    isPrimary = staff.isPrimary,
                    routeColor = staff.routeColor,
                    segments = segments,
                    distanceMeters = drawn ?: staff.recordedDistanceMeters,
                    live = live,
                    pathState = when {
                        drawn != null -> CpStaffTrack.PathState.SHOWN
                        window == null -> CpStaffTrack.PathState.NOT_STARTED
                        !canRead || !routeRead -> CpStaffTrack.PathState.NOT_AVAILABLE
                        else -> CpStaffTrack.PathState.NO_POINTS
                    },
                    tripInProgress = window?.open == true,
                    windowStartMs = window?.start,
                    windowEndMs = window?.end,
                )
            }
        }.awaitAll()
    }

    private fun toFix(point: TimelinePoint) =
        TrackCleaner.Fix(point.lat, point.lng, point.accuracy, point.recordedAt, point.isMock == true)

    companion object {
        private const val TIMEOUT_MS = 15_000L

        @Volatile
        private var serverRouteMissing = false

        /** The geo service on this host has no snapped-route yet (404). */
        @Volatile
        private var snappedRouteMissing = false

        fun formatClock(ms: Long): String = CpStaffTrackPolicy.clockLabel(ms, System.currentTimeMillis())
    }
}
