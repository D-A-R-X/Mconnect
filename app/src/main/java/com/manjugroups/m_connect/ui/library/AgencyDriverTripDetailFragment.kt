package com.manjugroups.m_connect.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.manjugroups.m_connect.databinding.FragmentAgencyDriverTripDetailBinding
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

    private val visitId get() = requireArguments().getString(ARG_ID).orEmpty()
    private val title get() = requireArguments().getString(ARG_TITLE).orEmpty()
    private val whenText get() = requireArguments().getString(ARG_WHEN).orEmpty()
    private val address get() = requireArguments().getString(ARG_ADDRESS).orEmpty()
    private val vehicle get() = requireArguments().getString(ARG_VEHICLE).orEmpty()
    private val phase get() = requireArguments().getString(ARG_PHASE).orEmpty()
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
        binding.tvDetailProgress.text = phaseLabel(phase)

        binding.btnDetailBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnDetailOpenMaps.setOnClickListener { openMaps() }

        mapView = binding.mapViewAgencyTrip
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)
        geocodeAddress()

        renderAction()

        // A completed capture returns here; refresh the visible state.
        setFragmentResultListener(AgencyDriverTripActionSheet.RESULT_KEY) { _, _ ->
            // The lifecycle advanced; the list behind us reloads on resume, and
            // popping back shows the fresh card. Simplest correct behaviour.
            parentFragmentManager.popBackStack()
        }
    }

    private fun renderAction() {
        val started = phase == "in_progress" || phase == "on_site" ||
            phase == "picked_from_site"
        val completed = phase == "completed"
        when {
            completed -> {
                binding.tvDetailActionLabel.text = "Completed"
                binding.btnDetailAction.isEnabled = false
                binding.btnDetailAction.alpha = 0.5f
            }
            // The backend only allows trip actions on the scheduled date, so a
            // trip whose day has passed can't be started/ended — say so rather
            // than offer a button that always errors.
            !canOperateToday -> {
                binding.tvDetailActionLabel.text = "Not scheduled for today"
                binding.btnDetailAction.isEnabled = false
                binding.btnDetailAction.alpha = 0.5f
            }
            started -> {
                binding.tvDetailActionLabel.text = "End Trip"
                binding.btnDetailAction.setOnClickListener {
                    AgencyDriverTripActionSheet
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.END)
                        .show(childFragmentManager, "agency_end")
                }
            }
            else -> {
                binding.tvDetailActionLabel.text = "Start Trip"
                binding.btnDetailAction.setOnClickListener {
                    AgencyDriverTripActionSheet
                        .newInstance(visitId, AgencyDriverTripActionSheet.Mode.START)
                        .show(childFragmentManager, "agency_start")
                }
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

        fun newInstance(
            id: String,
            title: String,
            whenText: String,
            address: String,
            vehicle: String,
            phase: String,
            canOperateToday: Boolean,
        ): AgencyDriverTripDetailFragment = AgencyDriverTripDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_TITLE, title)
                putString(ARG_WHEN, whenText)
                putString(ARG_ADDRESS, address)
                putString(ARG_VEHICLE, vehicle)
                putString(ARG_PHASE, phase)
                putBoolean(ARG_CAN_OPERATE, canOperateToday)
            }
        }
    }
}
