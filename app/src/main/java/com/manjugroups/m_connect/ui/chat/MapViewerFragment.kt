package com.manjugroups.m_connect.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.common.navigateUp
import java.util.Locale

class MapViewerFragment : Fragment(), OnMapReadyCallback {

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null

    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var label: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            lat = it.getDouble(ARG_LAT)
            lon = it.getDouble(ARG_LON)
            label = it.getString(ARG_LABEL).orEmpty()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_map_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btnMapViewerBack).setOnClickListener {
            navigateUp()
        }

        val tvTitle = view.findViewById<TextView>(R.id.tvMapViewerTitle)
        val tvLabel = view.findViewById<TextView>(R.id.tvMapViewerLabel)
        val tvCoords = view.findViewById<TextView>(R.id.tvMapViewerCoords)
        val btnExternal = view.findViewById<Button>(R.id.btnMapViewerExternal)

        tvTitle.text = label.ifBlank { "Location Map" }
        tvLabel.text = label.ifBlank { "Shared Location" }
        tvCoords.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)

        btnExternal.setOnClickListener {
            val uriString = String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, Uri.encode(label))
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "No map application found", Toast.LENGTH_SHORT).show()
            }
        }

        mapView = view.findViewById(R.id.mapViewViewer)
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = true

        val location = LatLng(lat, lon)
        map.addMarker(
            MarkerOptions()
                .position(location)
                .title(label)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        googleMap = null
        super.onDestroyView()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    companion object {
        private const val ARG_LAT = "lat"
        private const val ARG_LON = "lon"
        private const val ARG_LABEL = "label"

        fun newInstance(lat: Double, lon: Double, label: String): MapViewerFragment {
            return MapViewerFragment().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_LAT, lat)
                    putDouble(ARG_LON, lon)
                    putString(ARG_LABEL, label)
                }
            }
        }
    }
}
