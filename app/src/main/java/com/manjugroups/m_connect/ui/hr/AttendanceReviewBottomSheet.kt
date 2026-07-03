package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                behavior.skipCollapsed = false
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
        }
    }

    private fun drawRoute(map: GoogleMap, data: SessionRouteData) {
        val boundsBuilder = LatLngBounds.Builder()
        var hasBounds = false
        val routeColor = Color.parseColor("#2563EB")

        val tripPaths = data.trips.mapNotNull { trip ->
            val path = trip.snappedPath.orEmpty().map { LatLng(it.lat, it.lng) }
            if (path.size >= 2) path else null
        }
        if (tripPaths.isNotEmpty()) {
            tripPaths.forEach { path ->
                map.addPolyline(PolylineOptions().addAll(path).color(routeColor).width(8f))
                path.forEach { boundsBuilder.include(it); hasBounds = true }
            }
        } else if (data.timeline.size >= 2) {
            val timelinePath = data.timeline.map { LatLng(it.lat, it.lng) }
            map.addPolyline(PolylineOptions().addAll(timelinePath).color(routeColor).width(8f))
            timelinePath.forEach { boundsBuilder.include(it); hasBounds = true }
        }

        val stops = (data.stops + data.trips.flatMap { it.stops.orEmpty() })
            .distinctBy { "${it.arrivedAt}:${it.departedAt}" }
        stops.forEach { stop ->
            val point = LatLng(stop.lat, stop.lng)
            map.addMarker(MarkerOptions().position(point).title(stop.address ?: "Stop"))
            boundsBuilder.include(point); hasBounds = true
        }

        // No travelled route for this record/date — keep the map hidden and
        // leave the original "Route Preview" placeholder showing, rather than
        // exposing an empty world-view map.
        if (!hasBounds) return

        // Real data arrived — reveal the map and hide the placeholder graphic.
        binding.mapRoutePreview.visibility = View.VISIBLE
        binding.imgRoutePlaceholder.visibility = View.GONE
        binding.layoutRoutePlaceholderText.visibility = View.GONE

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
        val times = listOf("09:00 AM", "11:45 AM", "02:15 PM", "05:00 PM")
        val places = listOf("Check In (Office)", "Site Visit - A (Kannapuram)", "Site Visit - B (Ponneri)", "Check Out (Office)")

        binding.replaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val index = (progress / 26).coerceIn(0, 3)
                binding.tvReplayTime.text = times[index]
                binding.tvReplayLocation.text = places[index]
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play/Pause button
        binding.btnReplayPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            if (isPlaying) {
                binding.btnReplayPlayPause.setImageResource(R.drawable.ic_chat_pause)
            } else {
                binding.btnReplayPlayPause.setImageResource(R.drawable.ic_home_trip_play)
            }
        }

        // Speed buttons
        binding.btnSpeedHalf.setOnClickListener { selectSpeed(binding.btnSpeedHalf) }
        binding.btnSpeedOne.setOnClickListener { selectSpeed(binding.btnSpeedOne) }
        binding.btnSpeedTwo.setOnClickListener { selectSpeed(binding.btnSpeedTwo) }
    }

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

    override fun onResume() {
        super.onResume()
        _binding?.mapRoutePreview?.onResume()
    }

    override fun onPause() {
        _binding?.mapRoutePreview?.onPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.mapRoutePreview?.onLowMemory()
    }

    override fun onDestroyView() {
        _binding?.mapRoutePreview?.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
