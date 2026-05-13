package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentGeotrackLiveBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.network.GeoLiveStatus
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.GeoTrip
import com.manjugroups.m_connect.network.TimelinePoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GeoTrackLiveFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentGeotrackLiveBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    private val geoApi = GeoTrackApi.create()
    private var googleMap: GoogleMap? = null

    private var liveStatuses: List<GeoLiveStatus> = emptyList()
    private var timeline: List<TimelinePoint> = emptyList()
    private var trips: List<GeoTrip> = emptyList()
    private var selectedStaffId: String? = null
    private var isUpdatingSpinner = false
    private var isExpanded = false
    private var selectedDayStartMillis: Long = startOfDay(System.currentTimeMillis())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGeotrackLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        if (!session.hasPermission("attendance.liveTracking")) {
            Toast.makeText(requireContext(), "GeoTrack Live access is not enabled for this account", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnExpandMap.setOnClickListener {
            isExpanded = !isExpanded
            renderExpandedState()
        }
        binding.btnToday.setOnClickListener {
            selectedDayStartMillis = startOfDay(System.currentTimeMillis())
            renderDateControls()
            loadSelectedStaffRoute()
        }
        binding.btnYesterday.setOnClickListener {
            selectedDayStartMillis = startOfDay(System.currentTimeMillis()) - DAY_MS
            renderDateControls()
            loadSelectedStaffRoute()
        }
        binding.btnPickDate.setOnClickListener { showDatePicker() }

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)
        setupSpinner()
        renderDateControls()
        renderExpandedState()
        loadLiveStatuses()
    }

    private fun setupSpinner() {
        binding.spinnerStaff.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingSpinner || position !in liveStatuses.indices) return
                val staffId = liveStatuses[position].staffId
                if (staffId == selectedStaffId) return
                selectedStaffId = staffId
                loadSelectedStaffRoute()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadLiveStatuses() {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                geoApi.getLiveStatus(session.bearerToken)
            }.onSuccess { response ->
                liveStatuses = (response.data ?: emptyList())
                    .filter { it.lat != 0.0 || it.lng != 0.0 || it.lastSeen > 0L }
                    .sortedWith(compareByDescending<GeoLiveStatus> { it.isOnline }.thenBy { it.staffName ?: it.staffId })

                binding.tvOnlineCount.text = liveStatuses.count { it.isOnline }.toString()
                binding.tvTrackedCount.text = liveStatuses.size.toString()

                if (liveStatuses.isEmpty()) {
                    selectedStaffId = null
                    timeline = emptyList()
                    trips = emptyList()
                    renderSpinner()
                    renderSelection()
                    renderMap()
                    setLoading(false)
                    return@onSuccess
                }

                if (selectedStaffId == null || liveStatuses.none { it.staffId == selectedStaffId }) {
                    selectedStaffId = liveStatuses.firstOrNull { it.isOnline }?.staffId ?: liveStatuses.first().staffId
                }

                renderSpinner()
                loadSelectedStaffRoute()
            }.onFailure { error ->
                setLoading(false)
                binding.tvMapEmpty.visibility = View.VISIBLE
                binding.tvMapEmpty.text = error.message ?: "Failed to load GeoTrack Live data"
                Toast.makeText(requireContext(), error.message ?: "Failed to load GeoTrack Live", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSelectedStaffRoute() {
        val staffId = selectedStaffId
        if (staffId == null) {
            setLoading(false)
            renderSelection()
            renderMap()
            return
        }

        setLoading(true)
        val range = selectedDateRange()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val timelineResp = geoApi.getTimeline(
                    token = session.bearerToken,
                    staffId = staffId,
                    dayStart = range.first,
                    dayEnd = range.second
                )
                val tripsResp = geoApi.getTrips(
                    token = session.bearerToken,
                    staffId = staffId,
                    startDate = range.first,
                    endDate = range.second
                )
                Pair(timelineResp.data ?: emptyList(), tripsResp.data ?: emptyList())
            }.onSuccess { (timelineData, tripsData) ->
                timeline = timelineData
                trips = tripsData.sortedBy { it.startedAt }
                renderSelection()
                renderMap()
            }.onFailure { error ->
                timeline = emptyList()
                trips = emptyList()
                renderSelection()
                renderMap()
                Toast.makeText(requireContext(), error.message ?: "Failed to load staff route", Toast.LENGTH_SHORT).show()
            }
            setLoading(false)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isCompassEnabled = true
            uiSettings.isZoomControlsEnabled = false
            setOnMarkerClickListener { marker ->
                val staffId = marker.tag as? String
                if (staffId != null && staffId != selectedStaffId) {
                    selectedStaffId = staffId
                    renderSpinner()
                    loadSelectedStaffRoute()
                }
                false
            }
        }
        renderMap()
    }

    private fun renderSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            liveStatuses.map { status ->
                val name = status.staffName ?: status.staffId
                val state = if (status.isOnline) "Online" else "Offline"
                "$name • $state • ${status.batteryPct}%"
            }
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        isUpdatingSpinner = true
        binding.spinnerStaff.adapter = adapter
        val selectedIndex = liveStatuses.indexOfFirst { it.staffId == selectedStaffId }.coerceAtLeast(0)
        if (liveStatuses.isNotEmpty()) {
            binding.spinnerStaff.setSelection(selectedIndex, false)
        }
        isUpdatingSpinner = false
    }

    private fun renderSelection() {
        val selected = liveStatuses.firstOrNull { it.staffId == selectedStaffId }
        if (selected == null) {
            binding.tvSelectedStaffName.text = "No staff selected"
            binding.tvSelectedStaffStatus.text = "Waiting for live data"
            binding.tvSelectedStaffMeta.text = "${formattedSelectedDate()} route and current position"
            binding.tvTripDistance.text = "0 km"
            binding.tvTripCount.text = "0 trips selected"
            binding.tvMapModeLabel.text = "Route View"
            return
        }

        binding.tvSelectedStaffName.text = selected.staffName ?: "Unknown"
        binding.tvSelectedStaffMeta.text = listOfNotNull(
            listOfNotNull(selected.designation, selected.department).joinToString(" • ").ifBlank { null },
            formattedSelectedDate()
        ).joinToString(" • ")

        val lastSeen = if (selected.lastSeen > 0) {
            "Last seen ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(selected.lastSeen))}"
        } else {
            "No recent heartbeat"
        }
        val onlineState = if (selected.isOnline) "Online" else "Offline"
        val tamper = if (selected.hasTamperAlert) " • Tamper alert" else ""
        binding.tvSelectedStaffStatus.text = "$onlineState • ${selected.batteryPct}% battery • $lastSeen$tamper"

        val totalDistanceKm = trips.sumOf { it.distanceMeters.toDouble() } / 1000.0
        binding.tvTripDistance.text = String.format(Locale.getDefault(), "%.1f km", totalDistanceKm)
        binding.tvTripCount.text = "${trips.size} trip${if (trips.size == 1) "" else "s"} selected"
        binding.tvMapModeLabel.text = formattedSelectedDate()
    }

    private fun renderMap() {
        val map = googleMap ?: return
        map.clear()

        if (liveStatuses.isEmpty()) {
            binding.tvMapEmpty.visibility = View.VISIBLE
            binding.tvMapEmpty.text = "No live GeoTrack data available"
            return
        }

        binding.tvMapEmpty.visibility = View.GONE
        val boundsBuilder = LatLngBounds.Builder()
        var hasBounds = false

        liveStatuses.forEach { status ->
            if (status.lat == 0.0 && status.lng == 0.0) return@forEach
            val point = LatLng(status.lat, status.lng)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(point)
                    .title(status.staffName ?: "Tracked staff")
                    .snippet("${status.movementMode ?: status.activity ?: "Unknown"} • ${status.batteryPct}%")
                    .icon(
                        BitmapDescriptorFactory.defaultMarker(
                            when {
                                status.staffId == selectedStaffId -> BitmapDescriptorFactory.HUE_AZURE
                                status.hasTamperAlert -> BitmapDescriptorFactory.HUE_RED
                                status.isOnline -> BitmapDescriptorFactory.HUE_GREEN
                                else -> BitmapDescriptorFactory.HUE_ORANGE
                            }
                        )
                    )
            )
            marker?.tag = status.staffId
            boundsBuilder.include(point)
            hasBounds = true
        }

        val selected = liveStatuses.firstOrNull { it.staffId == selectedStaffId }

        val selectedTripPaths = trips
            .mapNotNull { trip ->
                val snapped = trip.snappedPath.orEmpty().map { LatLng(it.lat, it.lng) }
                if (snapped.size >= 2) snapped else null
            }

        if (selectedTripPaths.isNotEmpty()) {
            selectedTripPaths.forEach { path ->
                map.addPolyline(
                    PolylineOptions()
                        .addAll(path)
                        .color(Color.parseColor("#2563EB"))
                        .width(if (isExpanded) 10f else 8f)
                )
                path.forEach { point ->
                    boundsBuilder.include(point)
                    hasBounds = true
                }
            }
        } else if (timeline.size >= 2) {
            val timelinePath = timeline.map { LatLng(it.lat, it.lng) }
            map.addPolyline(
                PolylineOptions()
                    .addAll(timelinePath)
                    .color(Color.parseColor("#2563EB"))
                    .width(if (isExpanded) 10f else 8f)
            )
            timelinePath.forEach { point ->
                boundsBuilder.include(point)
                hasBounds = true
            }
        }

        trips.forEach { trip ->
            trip.stops.orEmpty().forEach { stop ->
                val stopPoint = LatLng(stop.lat, stop.lng)
                map.addMarker(
                    MarkerOptions()
                        .position(stopPoint)
                        .title(stop.address ?: "Stop")
                        .snippet("${stop.durationMinutes} min")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                boundsBuilder.include(stopPoint)
                hasBounds = true
            }

            val startPoint = trip.startLat?.let { lat -> trip.startLng?.let { lng -> LatLng(lat, lng) } }
            if (startPoint != null) {
                map.addMarker(
                    MarkerOptions()
                        .position(startPoint)
                        .title("Trip start")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                boundsBuilder.include(startPoint)
                hasBounds = true
            }
        }

        if (hasBounds) {
            runCatching {
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), if (isExpanded) 80 else 120))
            }.recover {
                selected?.let { status ->
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(status.lat, status.lng), 13f))
                }
            }
        } else if (selected != null && (selected.lat != 0.0 || selected.lng != 0.0)) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(selected.lat, selected.lng), 13f))
        }
    }

    private fun renderDateControls() {
        val todayStart = startOfDay(System.currentTimeMillis())
        val yesterdayStart = todayStart - DAY_MS

        updateDateButton(binding.btnToday, selectedDayStartMillis == todayStart)
        updateDateButton(binding.btnYesterday, selectedDayStartMillis == yesterdayStart)
        updateDateButton(binding.btnPickDate, selectedDayStartMillis != todayStart && selectedDayStartMillis != yesterdayStart)
        binding.tvSelectedDate.text = formattedSelectedDate()
    }

    private fun updateDateButton(view: TextView, active: Boolean) {
        if (active) {
            view.setBackgroundResource(R.drawable.bg_button_primary)
            view.setTextColor(resolveColor(android.R.color.white))
        } else {
            view.setBackgroundResource(R.drawable.bg_stat_card)
            view.setTextColor(resolveThemeColor(R.attr.colorForegroundPrimary))
        }
    }

    private fun renderExpandedState() {
        binding.topContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
        binding.bottomSummaryCard.visibility = if (isExpanded) View.GONE else View.VISIBLE
        binding.btnExpandMap.text = if (isExpanded) "Collapse" else "Expand"
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDayStartMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedDayStartMillis = picked.timeInMillis
                renderDateControls()
                loadSelectedStaffRoute()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun formattedSelectedDate(): String {
        val todayStart = startOfDay(System.currentTimeMillis())
        val yesterdayStart = todayStart - DAY_MS
        return when (selectedDayStartMillis) {
            todayStart -> "Today"
            yesterdayStart -> "Yesterday"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDayStartMillis))
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressMap.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
        } else {
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
        }
    }

    private fun selectedDateRange(): Pair<Long, Long> {
        val dayStart = selectedDayStartMillis
        val todayStart = startOfDay(System.currentTimeMillis())
        val dayEnd = if (dayStart == todayStart) {
            System.currentTimeMillis()
        } else {
            dayStart + DAY_MS - 1
        }
        return dayStart to dayEnd
    }

    override fun onStart() {
        super.onStart()
        _binding?.mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        _binding?.mapView?.onResume()
    }

    override fun onPause() {
        _binding?.mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        _binding?.mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        _binding?.mapView?.onDestroy()
        googleMap = null
        _binding = null
        super.onDestroyView()
    }

    private fun resolveColor(colorRes: Int): Int = ContextCompat.getColor(requireContext(), colorRes)

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private fun startOfDay(timeMillis: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = timeMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}
