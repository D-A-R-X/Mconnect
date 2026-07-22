package com.manjugroups.m_connect.ui.library

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAgencyDriverTripDetailBinding
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskDriverTripRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A CP-style detail screen for one external-agency trip: a map pinned on the
 * client location (geocoded from the pickup address, since the trip payload
 * carries no coordinates), the trip details, and the single lifecycle action.
 *
 * Not started → "Start Trip" opens the capture sheet (dashboard photo + start
 * km). Started → "End Trip" opens the closing capture. Completed → read-only.
 */
class AgencyDriverTripDetailFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentAgencyDriverTripDetailBinding? = null
    private val binding get() = _binding!!
    private var mapView: MapView? = null
    private var marker: LatLng? = null

    private val api by lazy { TravelDeskApi.create() }
    private val geoApi by lazy { com.manjugroups.m_connect.network.GeoTrackApi.create() }
    private lateinit var session: SessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var pickupGateRunnable: Runnable? = null
    // Internal MFPL fleet driver (mms-fleet endpoints) vs external agency
    // driver (travel-desk endpoints). Same UI + lifecycle.
    private val internal get() = requireArguments().getBoolean(ARG_INTERNAL, false)

    // Live lifecycle state — starts from the args, then the fragment re-fetches
    // in place after each step so the driver stays on this screen.
    private var currentPhase: String = ""
    private var onSiteAtMs: Long = 0L
    private var busy = false

    private val visitId get() = requireArguments().getString(ARG_ID).orEmpty()
    private val title get() = requireArguments().getString(ARG_TITLE).orEmpty()
    private val whenText get() = requireArguments().getString(ARG_WHEN).orEmpty()
    private val address get() = requireArguments().getString(ARG_ADDRESS).orEmpty()
    private val vehicle get() = requireArguments().getString(ARG_VEHICLE).orEmpty()
    private val canOperateToday get() = requireArguments().getBoolean(ARG_CAN_OPERATE, true)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAgencyDriverTripDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvDetailTitle.text = title.ifBlank { "Site visit" }
        binding.tvDetailWhen.text = whenText
        binding.tvDetailAddress.text = address.ifBlank { "No address" }
        binding.tvDetailVehicle.text =
            if (vehicle.isNotBlank()) "Vehicle: $vehicle" else "No vehicle"
        session = SessionManager(requireContext())
        currentPhase = requireArguments().getString(ARG_PHASE).orEmpty()
        onSiteAtMs = requireArguments().getLong(ARG_ONSITE_AT, 0L)

        binding.tvDetailProgress.text = phaseLabel(currentPhase)
        renderStages()

        binding.btnDetailBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnDetailOpenMaps.setOnClickListener { openMaps() }

        mapView = binding.mapViewAgencyTrip
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)
        geocodeAddress()

        renderAction()
        // Internal trips are opened from Home with only the visit status as the
        // seed phase — pull the true fleet phase/onSite from the backend so the
        // stepper + action button reflect real progress on first paint.
        if (internal) reloadAndRender()

        // The Start/End capture sheets return here — re-fetch in place so the
        // driver keeps their spot in the flow rather than being bounced back.
        setFragmentResultListener(AgencyDriverTripActionSheet.RESULT_KEY) { _, _ ->
            reloadAndRender()
        }
    }

    /** Re-fetch this trip and re-render, keeping the driver on this screen. */
    private fun reloadAndRender() {
        viewLifecycleOwner.lifecycleScope.launch {
            val phaseAndOnSite: Pair<String, Long>? = if (internal) {
                runCatching { geoApi.getMmsFleetDriverTrips(session.bearerToken) }
                    .getOrNull()?.trips?.firstOrNull { it.id == visitId }
                    ?.let { t ->
                        // Fill the vehicle label (opened from Home with no vehicle
                        // arg) from the fetched trip.
                        val veh = listOfNotNull(
                            t.vehicle?.vehicleNumber?.takeIf { n -> n.isNotBlank() },
                            t.vehicle?.type?.takeIf { ty -> ty.isNotBlank() },
                        ).joinToString(" · ")
                        if (_binding != null && veh.isNotBlank()) {
                            binding.tvDetailVehicle.text = "Vehicle: $veh"
                        }
                        (t.phase ?: "") to (t.travelDeskOnSiteAt ?: 0L)
                    }
            } else {
                runCatching { api.listDriverTrips(session.bearerToken) }
                    .getOrNull()?.rows?.firstOrNull { it.id == visitId }
                    ?.let { (it.phase ?: "") to (it.travelDeskOnSiteAt ?: 0L) }
            }
            if (_binding == null || phaseAndOnSite == null) return@launch
            currentPhase = phaseAndOnSite.first.lowercase()
            onSiteAtMs = phaseAndOnSite.second
            binding.tvDetailProgress.text = phaseLabel(currentPhase)
            renderStages()
            renderAction()
        }
    }

    /** Return pickup unlocks this many ms after "reached site" is marked. */
    private val pickupGateMs = 60_000L

    private fun renderAction() {
        pickupGateRunnable?.let { handler.removeCallbacks(it) }
        val btn = binding.btnDetailAction
        val label = binding.tvDetailActionLabel

        fun enable(text: String, onClick: () -> Unit) {
            label.text = text
            btn.isEnabled = true
            btn.alpha = 1f
            btn.setOnClickListener { if (!busy) onClick() }
        }
        fun disable(text: String) {
            label.text = text
            btn.isEnabled = false
            btn.alpha = 0.5f
            btn.setOnClickListener(null)
        }

        when {
            currentPhase == "completed" -> disable("Completed")
            // The backend only allows trip actions on the scheduled date.
            !canOperateToday -> disable("Not scheduled for today")
            // Not started yet → capture OTP + odometer photo + start km.
            currentPhase != "in_progress" && currentPhase != "on_site" &&
                currentPhase != "picked_from_site" ->
                enable("Start Trip") {
                    AgencyDriverTripActionSheet
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.START, internal)
                        .show(childFragmentManager, "agency_start")
                }
            // Started, driving → mark arrival at the site.
            currentPhase == "in_progress" ->
                enable("Reached Site") { markOnSite() }
            // On site → return pickup, gated 60s after reaching the site.
            currentPhase == "on_site" -> {
                val elapsed = System.currentTimeMillis() - onSiteAtMs
                val remaining = pickupGateMs - elapsed
                if (onSiteAtMs > 0L && remaining > 0) {
                    disable("Picked from Site in ${(remaining / 1000) + 1}s")
                    val r = Runnable { if (_binding != null) renderAction() }
                    pickupGateRunnable = r
                    handler.postDelayed(r, minOf(remaining, 1000L))
                } else {
                    enable("Picked from Site") { markPickedFromSite() }
                }
            }
            // Return pickup done → close the trip with the end odometer.
            else ->
                enable("End Trip") {
                    AgencyDriverTripActionSheet
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.END, internal)
                        .show(childFragmentManager, "agency_end")
                }
        }
    }

    private fun markOnSite() = runStep {
        if (internal) {
            geoApi.markMmsFleetDriverOnSite(
                session.bearerToken,
                com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest(visitId),
            ).let { it.success to it.error }
        } else {
            api.driverMarkOnSite(session.bearerToken, TravelDeskDriverTripRequest(visitId))
                .let { it.success to it.error }
        }
    }

    private fun markPickedFromSite() = runStep {
        if (internal) {
            geoApi.markMmsFleetDriverPickedFromSite(
                session.bearerToken,
                com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest(visitId),
            ).let { it.success to it.error }
        } else {
            api.driverMarkPickedFromSite(session.bearerToken, TravelDeskDriverTripRequest(visitId))
                .let { it.success to it.error }
        }
    }

    /** Fire a one-tap lifecycle step, then re-fetch + re-render in place. */
    private fun runStep(call: suspend () -> Pair<Boolean, String?>) {
        if (busy) return
        busy = true
        binding.btnDetailAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { call() }
            busy = false
            if (_binding == null) return@launch
            val ok = result.getOrNull()?.first == true
            if (!ok) {
                Toast.makeText(
                    requireContext(),
                    result.getOrNull()?.second
                        ?: result.exceptionOrNull()?.message
                        ?: "Could not update the trip",
                    Toast.LENGTH_LONG,
                ).show()
                renderAction()
            } else {
                reloadAndRender()
            }
        }
    }

    private fun phaseLabel(p: String): String = when (p) {
        "at_client" -> "At client"
        "in_progress" -> "Trip started"
        "on_site" -> "On site"
        "picked_from_site" -> "Picked from site"
        "completed" -> "Completed"
        else -> "Assigned"
    }

    /** Same stage order the internal driver + SV stepper + travel-desk use. */
    private fun stages(): List<String> = listOf(
        "Assigned",
        "Picked from CP",
        "On Site",
        "Picked from Site",
        "Dropped",
        "Completed",
    )

    /** How far the agency phase has advanced through [stages]. */
    private fun reachedIndex(): Int = when (currentPhase.lowercase()) {
        "completed", "complete", "done", "closed" -> 5
        "dropped" -> 4
        "picked_from_site" -> 3
        "on_site", "on-site", "arrived" -> 2
        "in_progress", "in-progress", "at_client", "picked_up", "started", "active" -> 1
        else -> 0
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun renderStages() {
        val container = binding.agencyTripStages
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

    private fun geocodeAddress() {
        if (address.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val latLng = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(requireContext())
                        .getFromLocationName(address, 1)
                        ?.firstOrNull()
                        ?.let { LatLng(it.latitude, it.longitude) }
                }.getOrNull()
            }
            if (_binding == null || latLng == null) return@launch
            marker = latLng
            mapView?.getMapAsync(this@AgencyDriverTripDetailFragment)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        map.uiSettings.isMapToolbarEnabled = false
        val target = marker ?: return
        map.clear()
        map.addMarker(MarkerOptions().position(target).title("Client location"))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 14f))
    }

    private fun openMaps() {
        val q = Uri.encode(address.ifBlank { title })
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        runCatching { startActivity(intent) }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView?.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pickupGateRunnable?.let { handler.removeCallbacks(it) }
        mapView?.onDestroy()
        mapView = null
        _binding = null
    }

    companion object {
        private const val ARG_ID = "arg_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_WHEN = "arg_when"
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_VEHICLE = "arg_vehicle"
        private const val ARG_PHASE = "arg_phase"
        private const val ARG_CAN_OPERATE = "arg_can_operate"
        private const val ARG_ONSITE_AT = "arg_onsite_at"
        private const val ARG_INTERNAL = "arg_internal"

        fun newInstance(
            id: String,
            title: String,
            whenText: String,
            address: String,
            vehicle: String,
            phase: String,
            canOperateToday: Boolean,
            onSiteAtMs: Long = 0L,
            // true → internal MFPL fleet driver (mms-fleet endpoints).
            internal: Boolean = false,
        ): AgencyDriverTripDetailFragment = AgencyDriverTripDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_TITLE, title)
                putString(ARG_WHEN, whenText)
                putString(ARG_ADDRESS, address)
                putString(ARG_VEHICLE, vehicle)
                putString(ARG_PHASE, phase)
                putBoolean(ARG_CAN_OPERATE, canOperateToday)
                putLong(ARG_ONSITE_AT, onSiteAtMs)
                putBoolean(ARG_INTERNAL, internal)
            }
        }
    }
}
