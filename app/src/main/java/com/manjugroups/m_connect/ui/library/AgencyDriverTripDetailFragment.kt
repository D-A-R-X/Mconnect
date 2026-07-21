package com.manjugroups.m_connect.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    private lateinit var session: SessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var pickupGateRunnable: Runnable? = null

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

        binding.btnDetailBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnDetailOpenMaps.setOnClickListener { openMaps() }

        mapView = binding.mapViewAgencyTrip
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)
        geocodeAddress()

        renderAction()

        // The Start/End capture sheets return here — re-fetch in place so the
        // driver keeps their spot in the flow rather than being bounced back.
        setFragmentResultListener(AgencyDriverTripActionSheet.RESULT_KEY) { _, _ ->
            reloadAndRender()
        }
    }

    /** Re-fetch this trip and re-render, keeping the driver on this screen. */
    private fun reloadAndRender() {
        viewLifecycleOwner.lifecycleScope.launch {
            val trip = runCatching { api.listDriverTrips(session.bearerToken) }
                .getOrNull()?.rows?.firstOrNull { it.id == visitId }
            if (_binding == null || trip == null) return@launch
            currentPhase = (trip.phase ?: "").lowercase()
            onSiteAtMs = trip.travelDeskOnSiteAt ?: 0L
            binding.tvDetailProgress.text = phaseLabel(currentPhase)
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
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.START)
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
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.END)
                        .show(childFragmentManager, "agency_end")
                }
        }
    }

    private fun markOnSite() = runStep {
        api.driverMarkOnSite(session.bearerToken, TravelDeskDriverTripRequest(visitId))
    }

    private fun markPickedFromSite() = runStep {
        api.driverMarkPickedFromSite(session.bearerToken, TravelDeskDriverTripRequest(visitId))
    }

    /** Fire a one-tap lifecycle step, then re-fetch + re-render in place. */
    private fun runStep(call: suspend () -> com.manjugroups.m_connect.network.TravelDeskSimpleResponse) {
        if (busy) return
        busy = true
        binding.btnDetailAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { call() }
            busy = false
            if (_binding == null) return@launch
            val ok = result.getOrNull()?.success == true
            if (!ok) {
                Toast.makeText(
                    requireContext(),
                    result.getOrNull()?.error
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

        fun newInstance(
            id: String,
            title: String,
            whenText: String,
            address: String,
            vehicle: String,
            phase: String,
            canOperateToday: Boolean,
            onSiteAtMs: Long = 0L,
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
            }
        }
    }
}
