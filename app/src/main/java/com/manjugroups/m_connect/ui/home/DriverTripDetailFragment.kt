package com.manjugroups.m_connect.ui.home

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentDriverTripDetailBinding
import com.manjugroups.m_connect.ui.common.showOnce

/**
 * What a driver sees before committing to a trip: where they're going, how
 * far along the trip already is, and the single action available right now.
 *
 * Tapping the card or the green button on Home lands here instead of firing
 * straight into the start sheet, so the driver can check the address and the
 * stage they're at first.
 */
class DriverTripDetailFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDriverTripDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    private var mapView: MapView? = null

    private val visitId get() = requireArguments().getString(ARG_VISIT_ID).orEmpty()
    private val title get() = requireArguments().getString(ARG_TITLE).orEmpty()
    private val whenText get() = requireArguments().getString(ARG_WHEN).orEmpty()
    private val address get() = requireArguments().getString(ARG_ADDRESS).orEmpty()
    private val status get() = requireArguments().getString(ARG_STATUS).orEmpty()
    private val scheduledDate get() = requireArguments().getString(ARG_DATE).orEmpty()
    private val lat get() = requireArguments().getDouble(ARG_LAT, Double.NaN)
    private val lng get() = requireArguments().getDouble(ARG_LNG, Double.NaN)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDriverTripDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView?.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onMapReady(map: GoogleMap) {
        val destination = destinationLatLng() ?: return
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.addMarker(
            MarkerOptions().position(destination).title(title.ifBlank { "Destination" }),
        )
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(destination, 14f))
    }

    private fun destinationLatLng(): LatLng? =
        if (!lat.isNaN() && !lng.isNaN()) LatLng(lat, lng) else null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Edge-to-edge shell: drop the header below the status bar so the back
        // button + title don't sit under the notch / status icons.
        com.manjugroups.m_connect.ui.common.BottomActionInsets
            .applyStatusBarTop(binding.detailHeaderBar)

        binding.btnTripDetailBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.tvTripDetailTitle.text = title.ifBlank { "Site visit" }
        binding.tvTripDetailWhen.text = whenText.ifBlank { scheduledDate }
        binding.tvTripDetailAddress.text = address.ifBlank { "No address provided" }

        binding.btnTripDetailMap.setOnClickListener { openMaps() }

        mapView = binding.mapViewTripDetail
        mapView?.onCreate(savedInstanceState)
        if (destinationLatLng() != null) {
            mapView?.getMapAsync(this)
        }
        renderDistance()
        renderStages()
        renderAction()
    }

    /**
     * Straight-line distance from the last known fix. Deliberately not a
     * routed distance: this screen is a pre-trip glance and the routed
     * figure only becomes meaningful once tracking is running.
     */
    private fun renderDistance() {
        val destination = destinationLatLng()
        if (destination == null) {
            binding.tvTripDetailDistance.text = "Not mapped"
            return
        }
        val last = lastKnownLocation()
        if (last == null) {
            binding.tvTripDetailDistance.text = "—"
            return
        }
        val metres = FloatArray(1)
        android.location.Location.distanceBetween(
            last.latitude, last.longitude,
            destination.latitude, destination.longitude,
            metres,
        )
        val km = metres[0] / 1000f
        binding.tvTripDetailDistance.text =
            if (km < 1f) "${metres[0].toInt()} m" else String.format("%.1f km", km)
    }

    private fun lastKnownLocation(): android.location.Location? {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        val granted = { p: String ->
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), p) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted(fine) && !granted(coarse)) return null
        val manager = requireContext()
            .getSystemService(android.content.Context.LOCATION_SERVICE)
                as? android.location.LocationManager ?: return null
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
        (activity as? MainActivity)?.setTabBarVisible(true)
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    /** Same order the SV stepper and the travel-desk portal use. */
    private fun stages(): List<String> = listOf(
        "Assigned",
        "Picked from CP",
        "On Site",
        "Picked from Site",
        "Dropped",
        "Completed",
    )

    private fun reachedIndex(): Int = when (status.lowercase()) {
        "completed", "complete", "done", "closed" -> 5
        "dropped" -> 4
        "picked_from_site" -> 3
        "on_site", "on-site", "arrived" -> 2
        "in-progress", "in_progress", "picked_up", "started", "active" -> 1
        else -> 0
    }

    private fun renderStages() {
        val container = binding.tripDetailStages
        container.removeAllViews()
        val reached = reachedIndex()

        stages().forEachIndexed { index, label ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val done = index <= reached

            val dot = TextView(requireContext()).apply {
                text = if (done) "✓" else (index + 1).toString()
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                setTextColor(if (done) Color.WHITE else Color.parseColor("#98A2B3"))
                setBackgroundResource(
                    if (done) R.drawable.bg_my_trips_tab_active
                    else R.drawable.bg_cpv_filter_pill_inactive,
                )
                if (done) {
                    backgroundTintList = android.content.res.ColorStateList
                        .valueOf(Color.parseColor("#0B61CA"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }

            val text = TextView(requireContext()).apply {
                this.text = label
                textSize = 13f
                setTextColor(
                    if (done) Color.parseColor("#111827")
                    else Color.parseColor("#98A2B3"),
                )
                typeface = ResourcesCompat.getFont(
                    requireContext(),
                    if (done) R.font.inter_semibold else R.font.inter_regular,
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = dp(10) }
            }

            row.addView(dot)
            row.addView(text)
            container.addView(row)
        }
    }

    private fun renderAction() {
        val reached = reachedIndex()
        val label = binding.tvTripDetailActionLabel
        when {
            reached >= 5 -> {
                label.text = "Completed"
                binding.btnTripDetailAction.isEnabled = false
                binding.btnTripDetailAction.alpha = 0.5f
            }
            reached >= 1 -> {
                // Already under way — hand off to the live navigation screen
                // rather than offering to start it again.
                label.text = "Continue Trip"
                binding.btnTripDetailAction.setOnClickListener { openNavigation() }
            }
            else -> {
                label.text = "Start Trip"
                binding.btnTripDetailAction.setOnClickListener {
                    DriverStartTripBottomSheet
                        .newInstance(visitId, scheduledDate)
                        .showOnce(parentFragmentManager, "driver_start_trip")
                }
            }
        }
    }

    /**
     * Home owns the navigation hand-off (it holds the full TodayVisit), so
     * ask it to open the trip rather than rebuilding those args here.
     */
    private fun openNavigation() {
        setFragmentResult(
            RESULT_OPEN_NAVIGATION,
            androidx.core.os.bundleOf("visitId" to visitId),
        )
        parentFragmentManager.popBackStack()
    }

    private fun openMaps() {
        val uri = if (!lat.isNaN() && !lng.isNaN()) {
            Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(title)})")
        } else if (address.isNotBlank()) {
            Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        } else {
            Toast.makeText(requireContext(), "No location for this trip", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(
                    requireContext(),
                    "No maps app installed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        mapView = null
        _binding = null
    }

    companion object {
        const val RESULT_OPEN_NAVIGATION = "driver_trip_detail_open_navigation"

        private const val ARG_VISIT_ID = "visitId"
        private const val ARG_TITLE = "title"
        private const val ARG_WHEN = "when"
        private const val ARG_ADDRESS = "address"
        private const val ARG_STATUS = "status"
        private const val ARG_DATE = "date"
        private const val ARG_LAT = "lat"
        private const val ARG_LNG = "lng"

        fun newInstance(
            visitId: String,
            title: String,
            whenText: String,
            address: String,
            status: String,
            scheduledDate: String,
            lat: Double?,
            lng: Double?,
        ): DriverTripDetailFragment = DriverTripDetailFragment().apply {
            arguments = androidx.core.os.bundleOf(
                ARG_VISIT_ID to visitId,
                ARG_TITLE to title,
                ARG_WHEN to whenText,
                ARG_ADDRESS to address,
                ARG_STATUS to status,
                ARG_DATE to scheduledDate,
                ARG_LAT to (lat ?: Double.NaN),
                ARG_LNG to (lng ?: Double.NaN),
            )
        }
    }
}
