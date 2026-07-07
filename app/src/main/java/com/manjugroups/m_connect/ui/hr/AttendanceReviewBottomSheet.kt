package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetAttendanceReviewBinding
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SessionRouteData
import com.manjugroups.m_connect.network.TimelinePoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AttendanceReviewBottomSheet : BottomSheetDialogFragment() {

    interface OnActionClickListener {
        fun onApprove(recordId: String)
        fun onReject(recordId: String)
    }

    private var _binding: BottomSheetAttendanceReviewBinding? = null
    private val binding get() = _binding!!
    private var record: AttendanceApprovalRecord? = null
    private var listener: OnActionClickListener? = null

    private var isPlaying = false
    private var activeSpeed = 1.0

    // ── Live route playback (mirrors the web replay: a marker lerps along the
    // real GPS timeline; the seekbar scrubs it; 0.5/1/2× changes the rate) ──
    private var routeMap: GoogleMap? = null
    private data class PbStop(val pos: LatLng, val label: String)
    private val playbackPath = mutableListOf<LatLng>()
    private val playbackTimes = mutableListOf<Long>()
    private val playbackStops = mutableListOf<PbStop>()
    private var playbackMarker: Marker? = null
    private var playbackVehicleIcon: BitmapDescriptor? = null
    private var playbackIdx = 0
    private var playbackProgress = 0f
    private var playbackJob: Job? = null

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    companion object {
        fun newInstance(record: AttendanceApprovalRecord, listener: OnActionClickListener): AttendanceReviewBottomSheet {
            val sheet = AttendanceReviewBottomSheet()
            sheet.record = record
            sheet.listener = listener
            return sheet
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { di ->
            val sheet = (di as? BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
                it.outlineProvider = null

                val behavior = BottomSheetBehavior.from(it)
                // Open at full height so the entire review — map, playback, and
                // the full journey timeline at the bottom — is reachable by
                // scrolling the inner list. Collapsed at 60% the timeline sat
                // below the fold, and dragging to expand fought the full-mode
                // map's touch gestures, so the timeline never fully showed.
                it.layoutParams = it.layoutParams.apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                behavior.skipCollapsed = true
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAttendanceReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        bindHeaderAndDetails()
        setupTabs()
        setupPlaybackControls()
        setupActionButtons()
        setupRouteMap(savedInstanceState)
    }

    // ── Real travelled-route map (feeds the route-preview holder) ──────────
    //
    // Uses the same day-route source as the web / GeoTrackLive: fetch the
    // staff's session route for the attendance date and draw the snapped
    // polyline + stop markers. Only fills the placeholder area — the
    // playback controls and every other view are left exactly as-is.
    private fun setupRouteMap(savedInstanceState: Bundle?) {
        val mapView = binding.mapRoutePreview
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            map.uiSettings.isMapToolbarEnabled = false
            // Disable map panning so a vertical drag over the map scrolls the
            // sheet's list (reaching the journey timeline below) instead of
            // being swallowed by the map. The route is auto-framed to bounds,
            // so pan isn't needed; zoom buttons still work.
            map.uiSettings.isScrollGesturesEnabled = false
            routeMap = map
            loadRoute(map)
        }
    }

    private fun loadRoute(map: GoogleMap) {
        val rec = record ?: return

        // Clear the layout's placeholder stat values up front so the mock
        // numbers ("286", "7h 42m", …) never show — only live data does.
        binding.tvReviewGpsPoints.text = "—"
        binding.tvReviewTrips.text = "—"
        binding.tvReviewDistance.text = "—"
        val activeMins = rec.totalMinutes ?: 0
        binding.tvReviewActiveTime.text =
            if (activeMins > 0) "${activeMins / 60}h ${activeMins % 60}m" else "—"

        // Assume "No trips recorded" until a real route is drawn — hide the
        // play overlay + replay controls so a no-trip person doesn't get a
        // misleading playback UI. drawRoute() re-reveals them on hasBounds.
        binding.btnMapPlayOverlay.visibility = View.GONE
        binding.layoutReplayControls.visibility = View.GONE

        val staffId = rec.staffId ?: return
        val range = dayRangeMillis(rec.date) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val data = runCatching {
                geoApi.getSessionRoute(
                    token = session.bearerToken,
                    staffId = staffId,
                    dayStart = range.first,
                    dayEnd = range.second,
                    minStopMinutes = 30,
                ).data
            }.getOrNull()
            if (_binding == null) return@launch
            if (data == null) return@launch

            // Live travel stats for this staff/day, from the same route source.
            binding.tvReviewGpsPoints.text = data.timeline.size.toString()
            binding.tvReviewTrips.text = data.trips.size.toString()
            val meters = if (data.distanceMeters > 0) data.distanceMeters
                else data.trips.sumOf { it.distanceMeters }
            binding.tvReviewDistance.text =
                if (meters >= 1000) String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
                else "$meters m"

            drawRoute(map, data)
            renderJourneyTimeline(data)
        }
    }

    private fun drawRoute(map: GoogleMap, data: SessionRouteData) {
        val boundsBuilder = LatLngBounds.Builder()
        var hasBounds = false
        // ── Only travel that qualifies for the allowance is shown on the map:
        // trips of > 1 km (the same TRIP_QUALIFIER_METERS the travelAllowance
        // backend uses). A staff who didn't travel ≥ 1 km shows "No trips
        // recorded" — no map details at all. ──
        val minMeters = 1000
        val qualifyingTrips = data.trips.filter { it.distanceMeters > minMeters }
        var drewRoute = false

        // Trip lines, coloured per kind (on-duty → amber, field visit → green,
        // else blue), each with a white casing + forward direction arrows.
        qualifyingTrips.forEach { trip ->
            val path = trip.snappedPath.orEmpty().map { LatLng(it.lat, it.lng) }
                .ifEmpty {
                    val s = trip.startLat; val sl = trip.startLng
                    val e = trip.endLat; val el = trip.endLng
                    if (s != null && sl != null && e != null && el != null)
                        listOf(LatLng(s, sl), LatLng(e, el)) else emptyList()
                }
            if (path.size >= 2) {
                drewRoute = true
                drawTripLine(map, path, kindColor(trip))
                path.forEach { boundsBuilder.include(it); hasBounds = true }
            }
        }

        // No qualifying trips — fall back to the raw GPS timeline, but only when
        // it too represents ≥ 1 km of actual travel. Qualification uses the
        // BACKEND's distanceMeters, which excludes movement inside the office
        // geofence (100 m around the day's punch-in) — pacing around the office
        // must never put a route on the approval map. The raw path sum here
        // would wrongly count that wandering.
        if (!drewRoute && data.timeline.size >= 2) {
            val timelinePath = data.timeline.map { LatLng(it.lat, it.lng) }
            if (data.distanceMeters > minMeters) {
                drawTripLine(map, timelinePath, Color.parseColor("#2563EB"))
                timelinePath.forEach { boundsBuilder.include(it); hasBounds = true }
                drewRoute = true
            }
        }

        if (drewRoute) {
            // Stops — red pins with dwell time.
            val stopIcon = vectorToBitmap(R.drawable.ic_map_pin, Color.parseColor("#EF4444"), 30, 30)
            (data.stops + qualifyingTrips.flatMap { it.stops.orEmpty() })
                .distinctBy { "${it.arrivedAt}:${it.departedAt}" }
                .forEach { stop ->
                    val point = LatLng(stop.lat, stop.lng)
                    map.addMarker(
                        MarkerOptions().position(point).icon(stopIcon).anchor(0.5f, 1f)
                            .title(stop.address ?: "Stop")
                            .snippet("Stayed ${stop.durationMinutes} min").zIndex(6f),
                    )
                    boundsBuilder.include(point); hasBounds = true
                }

            // Journey start (green flag) + end (red flag) markers.
            firstRoutePoint(data)?.let { p ->
                map.addMarker(
                    MarkerOptions().position(p).anchor(0.25f, 1f).zIndex(9f).title("Start")
                        .icon(vectorToBitmap(R.drawable.ic_route_start_flag, null, 28, 34)),
                )
                boundsBuilder.include(p); hasBounds = true
            }
            lastRoutePoint(data)?.let { p ->
                map.addMarker(
                    MarkerOptions().position(p).anchor(0.25f, 1f).zIndex(9f).title("End")
                        .icon(vectorToBitmap(R.drawable.ic_route_end_flag, null, 28, 34)),
                )
                boundsBuilder.include(p); hasBounds = true
            }
        }

        // No travelled route — hide the map + playback, leave "No trips recorded".
        if (!hasBounds) {
            binding.btnMapPlayOverlay.visibility = View.GONE
            binding.layoutReplayControls.visibility = View.GONE
            return
        }

        // Real data arrived — reveal the map, hide the placeholder graphic,
        // and bring back the playback controls for the drawn route.
        binding.mapRoutePreview.visibility = View.VISIBLE
        binding.imgRoutePlaceholder.visibility = View.GONE
        binding.layoutRoutePlaceholderText.visibility = View.GONE
        binding.btnMapPlayOverlay.visibility = View.VISIBLE
        binding.layoutReplayControls.visibility = View.VISIBLE

        val bounds = boundsBuilder.build()
        val mapView = binding.mapRoutePreview
        mapView.post {
            val w = mapView.width
            val h = mapView.height
            runCatching {
                if (w > 0 && h > 0) {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, w, h, dp(28)))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(28)))
                }
            }.onFailure {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 13f))
            }
        }

        // Feed the real route into the replay engine (marker + seekbar).
        preparePlayback(data)
    }

    /** Line colour by trip kind — mirrors web: on-duty amber, field-visit
     *  green, otherwise blue. */
    private fun kindColor(trip: com.manjugroups.m_connect.network.GeoTrip): Int = when {
        !trip.onDutyCategory.isNullOrBlank() -> Color.parseColor("#F59E0B")
        !trip.fieldVisitId.isNullOrBlank() -> Color.parseColor("#22C55E")
        else -> Color.parseColor("#2563EB")
    }

    /** White casing + coloured line + forward arrows — the web polyline look. */
    private fun drawTripLine(map: GoogleMap, path: List<LatLng>, color: Int) {
        map.addPolyline(PolylineOptions().addAll(path).color(Color.WHITE).width(15f).zIndex(1f).geodesic(true))
        map.addPolyline(PolylineOptions().addAll(path).color(color).width(7f).zIndex(2f).geodesic(true))
        addDirectionArrows(map, path)
    }

    private val arrowIcon: BitmapDescriptor by lazy {
        vectorToBitmap(R.drawable.ic_route_arrow, null, 16, 16)
    }

    private fun addDirectionArrows(map: GoogleMap, path: List<LatLng>) {
        if (path.size < 2) return
        val step = (path.size / 8).coerceAtLeast(1)
        var i = step
        while (i < path.size) {
            val prev = path[i - 1]
            val cur = path[i]
            map.addMarker(
                MarkerOptions().position(cur).icon(arrowIcon).anchor(0.5f, 0.5f)
                    .rotation(bearing(prev, cur)).flat(true).zIndex(4f),
            )
            i += step
        }
    }

    private fun firstRoutePoint(data: SessionRouteData): LatLng? {
        data.trips.filter { it.distanceMeters > 1000 }.firstOrNull()?.let { t ->
            t.snappedPath?.firstOrNull()?.let { return LatLng(it.lat, it.lng) }
            val s = t.startLat; val sl = t.startLng
            if (s != null && sl != null) return LatLng(s, sl)
        }
        data.timeline.firstOrNull()?.let { return LatLng(it.lat, it.lng) }
        return null
    }

    private fun lastRoutePoint(data: SessionRouteData): LatLng? {
        data.trips.filter { it.distanceMeters > 1000 }.lastOrNull()?.let { t ->
            t.snappedPath?.lastOrNull()?.let { return LatLng(it.lat, it.lng) }
            val e = t.endLat; val el = t.endLng
            if (e != null && el != null) return LatLng(e, el)
        }
        data.timeline.lastOrNull()?.let { return LatLng(it.lat, it.lng) }
        return null
    }

    // ── Journey timeline — real logs derived from the day's GPS stream,
    // mirroring the web: Tracking Started, movement legs (mode + distance +
    // duration), stops, and the Check In / Check Out punches. ──
    private data class JtRow(
        val time: Long, val title: String, val subtitle: String?, val color: Int, val trailing: String?,
    )
    private data class JtSeg(val mode: String, val startTime: Long, val endTime: Long, val distance: Double)

    private fun renderJourneyTimeline(data: SessionRouteData) {
        if (_binding == null) return
        val container = binding.layoutTimelineEntries
        container.removeAllViews()
        val rows = buildJourneyTimeline(data)
        val inflater = LayoutInflater.from(requireContext())
        rows.forEachIndexed { idx, row ->
            val v = inflater.inflate(R.layout.item_journey_timeline, container, false)
            v.findViewById<TextView>(R.id.tvJtTime).text =
                formatMillisTime(row.time).lowercase(Locale.getDefault())
            v.findViewById<TextView>(R.id.tvJtTitle).text = row.title
            v.findViewById<TextView>(R.id.tvJtSubtitle).apply {
                if (row.subtitle.isNullOrBlank()) visibility = View.GONE
                else { text = row.subtitle; visibility = View.VISIBLE }
            }
            v.findViewById<TextView>(R.id.tvJtTrailing).apply {
                if (row.trailing.isNullOrBlank()) visibility = View.GONE
                else { text = row.trailing; visibility = View.VISIBLE }
            }
            v.findViewById<View>(R.id.jtDot).background =
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(row.color)
                }
            if (idx == rows.size - 1) v.findViewById<View>(R.id.jtLine).visibility = View.INVISIBLE
            container.addView(v)
        }
    }

    private fun buildJourneyTimeline(data: SessionRouteData): List<JtRow> {
        val rows = mutableListOf<JtRow>()
        data.timeline.firstOrNull()?.let {
            rows.add(JtRow(it.recordedAt, "Tracking Started", null, Color.parseColor("#22C55E"), null))
        }
        parseIsoMillis(record?.punchInTime)?.let {
            rows.add(JtRow(it, "Check In", null, Color.parseColor("#12B76A"), null))
        }
        buildSegments(data.timeline).forEach { seg ->
            if (normMode(seg.mode) == "stationary") return@forEach
            val distM = seg.distance.toInt()
            val durS = ((seg.endTime - seg.startTime) / 1000L).toInt().coerceAtLeast(0)
            val distLabel = if (distM >= 1000)
                String.format(Locale.getDefault(), "%.1f km", distM / 1000.0) else "$distM m"
            rows.add(JtRow(seg.startTime, modeLabel(seg.mode), null, modeColor(seg.mode), "$distLabel · ${formatDur(durS)}"))
        }
        (data.stops + data.trips.flatMap { it.stops.orEmpty() })
            .distinctBy { "${it.arrivedAt}:${it.departedAt}" }
            .forEach { stop ->
                rows.add(JtRow(stop.arrivedAt, "Stopped", stop.address, Color.parseColor("#6B7280"), "${stop.durationMinutes} min"))
            }
        parseIsoMillis(record?.punchOutTime)?.let {
            rows.add(JtRow(it, "Check Out", null, Color.parseColor("#F04438"), null))
        }
        return rows.sortedBy { it.time }
    }

    private fun buildSegments(points: List<TimelinePoint>): List<JtSeg> {
        if (points.size < 2) return emptyList()
        val segs = mutableListOf<JtSeg>()
        var curMode = normMode(points[0].movementMode)
        var segStart = 0
        var segDist = 0.0
        for (i in 1 until points.size) {
            val p = points[i]; val prev = points[i - 1]
            val mode = normMode(p.movementMode)
            segDist += distMeters(LatLng(prev.lat, prev.lng), LatLng(p.lat, p.lng))
            if (mode != curMode) {
                segs.add(JtSeg(curMode, points[segStart].recordedAt, prev.recordedAt, segDist))
                curMode = mode; segStart = i; segDist = 0.0
            }
        }
        segs.add(JtSeg(curMode, points[segStart].recordedAt, points.last().recordedAt, segDist))
        return segs
    }

    private fun normMode(m: String?): String = when {
        m.isNullOrBlank() -> "stationary"
        m == "vehicle" -> "two_wheeler"
        else -> m
    }

    private fun modeLabel(m: String): String = when (normMode(m)) {
        "walking" -> "Walking"
        "running" -> "Running"
        "two_wheeler" -> "Two-wheeler"
        "four_wheeler" -> "Four-wheeler"
        "cycling" -> "Cycling"
        else -> "Stationary"
    }

    private fun modeColor(m: String): Int = when (normMode(m)) {
        "walking" -> Color.parseColor("#22C55E")
        "running" -> Color.parseColor("#16A34A")
        "two_wheeler" -> Color.parseColor("#EAB308")
        "four_wheeler" -> Color.parseColor("#3B82F6")
        "cycling" -> Color.parseColor("#14B8A6")
        else -> Color.parseColor("#6B7280")
    }

    private fun formatDur(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun bearing(from: LatLng, to: LatLng): Float {
        val res = FloatArray(2)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude, to.latitude, to.longitude, res,
        )
        return res[1]
    }

    private fun vectorToBitmap(resId: Int, tint: Int?, widthDp: Int, heightDp: Int): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(requireContext(), resId)!!.mutate()
        tint?.let { DrawableCompat.setTint(drawable, it) }
        val w = (widthDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val h = (heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** Local-day (Asia/Kolkata) [start, endExclusive) epoch-millis for a
     *  "yyyy-MM-dd" attendance date. */
    private fun dayRangeMillis(dateStr: String?): Pair<Long, Long>? {
        if (dateStr.isNullOrBlank()) return null
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val parsed = runCatching { fmt.parse(dateStr) }.getOrNull() ?: return null
        val cal = Calendar.getInstance(tz).apply {
            time = parsed
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindHeaderAndDetails() {
        val rec = record ?: return

        // Date Header
        val parseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val subtitleFmt = SimpleDateFormat("dd MMMM yyyy • EEEE", Locale.getDefault())
        val dateParsed = rec.date?.let { runCatching { parseFmt.parse(it) }.getOrNull() }
        binding.tvReviewSubtitle.text = dateParsed?.let { subtitleFmt.format(it) } ?: (rec.date ?: "")

        // Punch details
        val parseTimeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val parseTimeFmt2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val displayTimeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

        val inTime = rec.punchInTime?.let {
            runCatching { parseTimeFmt.parse(it) }.getOrNull() ?: runCatching { parseTimeFmt2.parse(it) }.getOrNull()
        }
        val outTime = rec.punchOutTime?.let {
            runCatching { parseTimeFmt.parse(it) }.getOrNull() ?: runCatching { parseTimeFmt2.parse(it) }.getOrNull()
        }

        binding.tvReviewCheckIn.text = inTime?.let { displayTimeFmt.format(it) } ?: "09:00 AM"
        binding.tvReviewCheckOut.text = outTime?.let { displayTimeFmt.format(it) } ?: "05:00 PM"

        val durationHours = rec.totalMinutes?.let { it / 60 } ?: 8
        val durationMins = rec.totalMinutes?.let { it % 60 } ?: 0
        binding.tvReviewDuration.text = String.format(Locale.getDefault(), "%02d:%02d hrs", durationHours, durationMins)

        val sourceLabel = when (rec.source?.lowercase(Locale.US)) {
            "mobile" -> "Mobile App"
            "biometric" -> "Biometric"
            "manual" -> "Manual"
            else -> "Mobile App"
        }
        binding.tvReviewSource.text = sourceLabel
    }

    private fun setupTabs() {
        binding.tabSummary.setOnClickListener {
            updateTabStyles(0)
            binding.nestedScrollView.smoothScrollTo(0, 0)
        }

        binding.tabRoute.setOnClickListener {
            updateTabStyles(1)
            binding.nestedScrollView.smoothScrollTo(0, binding.layoutLiveRoutePreview.top)
        }

        binding.tabTimeline.setOnClickListener {
            updateTabStyles(2)
            binding.nestedScrollView.smoothScrollTo(0, binding.layoutPunchTimeline.top)
        }

        binding.btnMapPlayOverlay.setOnClickListener {
            updateTabStyles(1)
            binding.nestedScrollView.smoothScrollTo(0, binding.layoutLiveRoutePreview.top)
            // Start the replay — drives the vehicle marker along the route so
            // the reviewer sees the staff's position move through their journey.
            if (playbackPath.size >= 2 && !isPlaying) {
                if (playbackIdx >= playbackPath.size - 1) { playbackIdx = 0; playbackProgress = 0f }
                startPlayback()
            }
        }
    }

    private fun updateTabStyles(index: Int) {
        val activeBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_review_tab_active)
        val inactiveBg = null
        val activeColor = Color.parseColor("#0B61CA")
        val inactiveColor = Color.parseColor("#667085")

        binding.tabSummary.background = if (index == 0) activeBg else inactiveBg
        binding.tabSummary.setTextColor(if (index == 0) activeColor else inactiveColor)

        binding.tabRoute.background = if (index == 1) activeBg else inactiveBg
        binding.tabRoute.setTextColor(if (index == 1) activeColor else inactiveColor)

        binding.tabTimeline.background = if (index == 2) activeBg else inactiveBg
        binding.tabTimeline.setTextColor(if (index == 2) activeColor else inactiveColor)
    }

    private fun setupPlaybackControls() {
        // Scrub: drag the seekbar to jump the marker to that timeline point.
        binding.replaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || playbackPath.size < 2) return
                playbackIdx = progress.coerceIn(0, playbackPath.size - 1)
                playbackProgress = 0f
                renderPlaybackFrame()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { if (isPlaying) pausePlayback() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play / Pause the live replay.
        binding.btnReplayPlayPause.setOnClickListener {
            if (playbackPath.size < 2) return@setOnClickListener
            if (isPlaying) {
                pausePlayback()
            } else {
                if (playbackIdx >= playbackPath.size - 1) { playbackIdx = 0; playbackProgress = 0f }
                startPlayback()
            }
        }

        // Speed buttons — the running loop reads activeSpeed live.
        binding.btnSpeedHalf.setOnClickListener { activeSpeed = 0.5; selectSpeed(binding.btnSpeedHalf) }
        binding.btnSpeedOne.setOnClickListener { activeSpeed = 1.0; selectSpeed(binding.btnSpeedOne) }
        binding.btnSpeedTwo.setOnClickListener { activeSpeed = 2.0; selectSpeed(binding.btnSpeedTwo) }

        // -10s / +10s of real journey time (jumps to the nearest fix).
        binding.btnReplayBack10.setOnClickListener { skipPlayback(-10_000L) }
        binding.btnReplayForward10.setOnClickListener { skipPlayback(10_000L) }
    }

    /** Jump the playback marker ±[deltaMs] of journey time, landing on the
     *  timeline fix whose timestamp is closest. Keeps playing if it was. */
    private fun skipPlayback(deltaMs: Long) {
        if (playbackPath.size < 2 || playbackTimes.isEmpty()) return
        val curTime = playbackTimes.getOrNull(playbackIdx.coerceIn(0, playbackTimes.size - 1)) ?: return
        val target = curTime + deltaMs
        var best = playbackIdx
        var bestDiff = Long.MAX_VALUE
        for (i in playbackTimes.indices) {
            val d = kotlin.math.abs(playbackTimes[i] - target)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        playbackIdx = best.coerceIn(0, playbackPath.size - 1)
        playbackProgress = 0f
        renderPlaybackFrame()
    }

    // ── Live playback engine ──
    //
    // Ports the web replay: build a position/time track from the real GPS
    // timeline (or, when there are no raw fixes, the snapped trip paths timed
    // across the day), then lerp a marker between consecutive points. At 1×
    // one timeline point plays per ~0.9s; 0.5×/2× scale that. The seekbar and
    // the time/location labels stay in sync every frame.
    private fun preparePlayback(data: SessionRouteData) {
        pausePlayback()
        playbackPath.clear(); playbackTimes.clear(); playbackStops.clear()
        playbackMarker?.remove(); playbackMarker = null
        playbackIdx = 0; playbackProgress = 0f

        if (data.timeline.size >= 2) {
            data.timeline.forEach { p ->
                playbackPath.add(LatLng(p.lat, p.lng))
                playbackTimes.add(p.recordedAt)
            }
        } else {
            val pts = data.trips.flatMap { it.snappedPath.orEmpty() }
            if (pts.size >= 2) {
                val span = (data.routeEnd - data.routeStart).coerceAtLeast(0L)
                pts.forEachIndexed { i, pt ->
                    playbackPath.add(LatLng(pt.lat, pt.lng))
                    val frac = i.toDouble() / (pts.size - 1)
                    playbackTimes.add(data.routeStart + (span * frac).toLong())
                }
            }
        }

        (data.stops + data.trips.flatMap { it.stops.orEmpty() })
            .distinctBy { "${it.arrivedAt}:${it.departedAt}" }
            .forEach { playbackStops.add(PbStop(LatLng(it.lat, it.lng), it.address ?: "Stop")) }

        // Travel marker — the top-down rider PNG, rotated to the heading as it
        // moves along the route during playback.
        playbackVehicleIcon = vectorToBitmap(R.drawable.ic_travel_marker, null, 44, 66)

        binding.replaySeekBar.max = (playbackPath.size - 1).coerceAtLeast(1)
        binding.replaySeekBar.progress = 0
        if (playbackPath.size >= 2) renderPlaybackFrame()
    }

    private fun startPlayback() {
        if (playbackPath.size < 2) return
        isPlaying = true
        binding.btnReplayPlayPause.setImageResource(R.drawable.ic_chat_pause)
        playbackJob?.cancel()
        playbackJob = viewLifecycleOwner.lifecycleScope.launch {
            val frameMs = 40L
            val baseStepMs = 900.0 // ms per timeline point at 1×
            while (isActive && isPlaying) {
                if (playbackIdx >= playbackPath.size - 1) { pausePlayback(); break }
                playbackProgress += (frameMs / (baseStepMs / activeSpeed)).toFloat()
                while (playbackProgress >= 1f && playbackIdx < playbackPath.size - 1) {
                    playbackProgress -= 1f; playbackIdx++
                }
                renderPlaybackFrame()
                delay(frameMs)
            }
        }
    }

    private fun pausePlayback() {
        isPlaying = false
        playbackJob?.cancel(); playbackJob = null
        _binding?.btnReplayPlayPause?.setImageResource(R.drawable.ic_home_trip_play)
    }

    private fun renderPlaybackFrame() {
        val map = routeMap ?: return
        if (playbackPath.size < 2 || _binding == null) return
        val i = playbackIdx.coerceIn(0, playbackPath.size - 1)
        val cur = playbackPath[i]
        val nxt = if (i + 1 < playbackPath.size) playbackPath[i + 1] else cur
        val t = playbackProgress.coerceIn(0f, 1f)
        val pos = LatLng(
            cur.latitude + (nxt.latitude - cur.latitude) * t,
            cur.longitude + (nxt.longitude - cur.longitude) * t,
        )
        val heading = bearing(cur, nxt)
        val m = playbackMarker
        if (m == null) {
            playbackMarker = map.addMarker(
                MarkerOptions().position(pos).anchor(0.5f, 0.5f).zIndex(20f)
                    .flat(true).rotation(heading)
                    .icon(playbackVehicleIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
            )
        } else {
            m.position = pos
            m.rotation = heading
        }
        binding.replaySeekBar.progress = i
        binding.tvReplayTime.text = playbackTimes.getOrNull(i)?.let { formatMillisTime(it) } ?: "--"
        binding.tvReplayLocation.text = nearestStopLabel(pos) ?: "En route"
    }

    private fun nearestStopLabel(pos: LatLng): String? {
        var best: PbStop? = null
        var bestD = Double.MAX_VALUE
        for (s in playbackStops) {
            val d = distMeters(pos, s.pos)
            if (d < bestD) { bestD = d; best = s }
        }
        return if (best != null && bestD <= 60.0) best.label else null
    }

    private fun distMeters(a: LatLng, b: LatLng): Double {
        val res = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, res)
        return res[0].toDouble()
    }

    private fun formatMillisTime(ms: Long): String =
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))

    private fun selectSpeed(view: TextView) {
        val activeBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_review_tab_active)
        val inactiveBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_review_tab_container)
        val activeColor = Color.parseColor("#0B61CA")
        val inactiveColor = Color.parseColor("#667085")

        binding.btnSpeedHalf.background = if (view == binding.btnSpeedHalf) activeBg else inactiveBg
        binding.btnSpeedHalf.setTextColor(if (view == binding.btnSpeedHalf) activeColor else inactiveColor)

        binding.btnSpeedOne.background = if (view == binding.btnSpeedOne) activeBg else inactiveBg
        binding.btnSpeedOne.setTextColor(if (view == binding.btnSpeedOne) activeColor else inactiveColor)

        binding.btnSpeedTwo.background = if (view == binding.btnSpeedTwo) activeBg else inactiveBg
        binding.btnSpeedTwo.setTextColor(if (view == binding.btnSpeedTwo) activeColor else inactiveColor)
    }

    private fun setupActionButtons() {
        val rec = record ?: return

        binding.btnSheetReject.setOnClickListener {
            listener?.onReject(rec.id.orEmpty())
            dismiss()
        }

        binding.btnSheetApprove.setOnClickListener {
            listener?.onApprove(rec.id.orEmpty())
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        _binding?.mapRoutePreview?.onStart()
    }

    override fun onResume() {
        super.onResume()
        _binding?.mapRoutePreview?.onResume()
    }

    override fun onPause() {
        pausePlayback()
        _binding?.mapRoutePreview?.onPause()
        super.onPause()
    }

    override fun onStop() {
        _binding?.mapRoutePreview?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapRoutePreview?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.mapRoutePreview?.onLowMemory()
    }

    override fun onDestroyView() {
        playbackJob?.cancel()
        _binding?.mapRoutePreview?.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
