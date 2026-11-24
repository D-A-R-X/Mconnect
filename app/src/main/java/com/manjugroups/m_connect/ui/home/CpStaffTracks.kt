package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.geotrack.TrackCleaner
import com.manjugroups.m_connect.network.CpStaffTrackDto
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TimelinePoint
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    /** Cleaned travelled path for this trip, oldest first. */
    val path: List<TrackCleaner.Point>,
    val distanceMeters: Double?,
    val live: LiveFix?,
    val pathState: PathState,
    /** The trip has started and is not completed yet. */
    val tripInProgress: Boolean,
) {
    data class LiveFix(val lat: Double, val lng: Double, val lastSeenMs: Long, val trackingActive: Boolean)

    enum class PathState {
        /** A path with at least two fixes is drawn. */
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

/** Pure rules, kept free of Android types so they are unit-tested on the JVM. */
object CpStaffTrackPolicy {

    /** A heartbeat this old is shown as "last seen", not live. */
    const val LIVE_FRESH_MS = 10 * 60_000L

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

    fun pathDistanceMeters(path: List<TrackCleaner.Point>): Double? {
        if (path.size < 2) return null
        var total = 0.0
        for (i in 1 until path.size) {
            total += TrackCleaner.distanceM(path[i - 1].lat, path[i - 1].lng, path[i].lat, path[i].lng)
        }
        return total
    }

    fun distanceLabel(meters: Double?): String = when {
        meters == null || !meters.isFinite() || meters < 0 -> "Distance —"
        meters < 1000 -> "${meters.roundToInt()} m travelled"
        else -> String.format(Locale.US, "%.1f km travelled", meters / 1000.0)
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

    fun fromServer(dto: CpStaffTrackDto, now: Long): CpStaffTrack? {
        val staffId = dto.staffId?.takeIf { it.isNotBlank() } ?: return null
        val window = window(dto.startedAt?.toLong(), dto.completedAt?.toLong(), now)
        val path = window?.let { w ->
            cleanPath(
                dto.routePoints.orEmpty().map { TrackCleaner.Fix(it.lat, it.lng, atMs = it.recordedAt.toLong()) },
                w,
            )
        }.orEmpty()
        val liveDto = dto.live
        val liveMs = liveDto?.lastSeen?.toLong()
        val live = if (liveDto != null &&
            liveFixVisible(liveDto.lat, liveDto.lng, liveMs, liveDto.trackingActive == true, window)
        ) {
            CpStaffTrack.LiveFix(liveDto.lat!!, liveDto.lng!!, liveMs!!, liveDto.trackingActive == true)
        } else {
            null
        }
        val pathState = when {
            path.size >= 2 -> CpStaffTrack.PathState.SHOWN
            window == null -> CpStaffTrack.PathState.NOT_STARTED
            dto.routeAvailable == false -> CpStaffTrack.PathState.NOT_AVAILABLE
            else -> CpStaffTrack.PathState.NO_POINTS
        }
        return CpStaffTrack(
            staffId = staffId,
            name = dto.staffName?.takeIf { it.isNotBlank() } ?: "Staff",
            isPrimary = dto.isPrimary == true,
            routeColor = dto.routeColor,
            path = path,
            distanceMeters = pathDistanceMeters(path) ?: dto.distanceMeters ?: dto.recordedDistanceMeters,
            live = live,
            pathState = pathState,
            tripInProgress = window?.open == true,
        )
    }
}

/**
 * Loads [CpStaffTrack]s for one CP. Prefers the server's scoped
 * `staff-tracks` route; when that route is not deployed yet it falls back to
 * the direct geo reads, reading another staff member's path only where the
 * geo service will really return THAT staff member's rows.
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
        if (!serverRouteMissing) {
            val server = runCatching {
                withTimeout(TIMEOUT_MS) { geoApi.getCpStaffTracks(bearerToken, cpId) }
            }
            server.getOrNull()?.takeIf { it.success }?.data?.staff?.let { rows ->
                val tracks = rows.mapNotNull { CpStaffTrackPolicy.fromServer(it, now) }
                if (tracks.isNotEmpty() || involved.isEmpty()) return Result.Loaded(tracks)
            }
            val error = server.exceptionOrNull()
            if (error is HttpException && error.code() == 403) return Result.Forbidden
            // Not deployed on this server yet: stop asking for the rest of
            // this app session (each miss would otherwise log a 404).
            if (error is HttpException && error.code() == 404) serverRouteMissing = true
        }
        if (involved.isEmpty()) return Result.Unavailable
        return Result.Loaded(loadDirect(involved, viewerStaffId, viewerCanViewAllTracking, now))
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
                val path = if (window != null && canRead) {
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
                        CpStaffTrackPolicy.cleanPath(data.timeline.map(::toFix), window)
                    }.orEmpty()
                } else {
                    emptyList()
                }
                val row = liveRows[staff.staffId]
                val live = if (row != null &&
                    CpStaffTrackPolicy.liveFixVisible(row.lat, row.lng, row.lastSeen, row.trackingActive == true, window)
                ) {
                    CpStaffTrack.LiveFix(row.lat!!, row.lng!!, row.lastSeen!!, row.trackingActive == true)
                } else {
                    null
                }
                CpStaffTrack(
                    staffId = staff.staffId,
                    name = staff.name?.takeIf { it.isNotBlank() } ?: "Staff",
                    isPrimary = staff.isPrimary,
                    routeColor = staff.routeColor,
                    path = path,
                    distanceMeters = CpStaffTrackPolicy.pathDistanceMeters(path) ?: staff.recordedDistanceMeters,
                    live = live,
                    pathState = when {
                        path.size >= 2 -> CpStaffTrack.PathState.SHOWN
                        window == null -> CpStaffTrack.PathState.NOT_STARTED
                        !canRead || !routeRead -> CpStaffTrack.PathState.NOT_AVAILABLE
                        else -> CpStaffTrack.PathState.NO_POINTS
                    },
                    tripInProgress = window?.open == true,
                )
            }
        }.map { it.await() }
    }

    private fun toFix(point: TimelinePoint) =
        TrackCleaner.Fix(point.lat, point.lng, point.accuracy, point.recordedAt, point.isMock == true)

    companion object {
        private const val TIMEOUT_MS = 15_000L

        @Volatile
        private var serverRouteMissing = false

        fun formatClock(ms: Long): String =
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
    }
}
