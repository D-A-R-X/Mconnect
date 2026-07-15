package com.manjugroups.m_connect.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.MapAddressResult
import com.manjugroups.m_connect.network.MapServiceApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reusable "drop a pin on the map" picker with address search + live
 * suggestions from the map microservice ([MapServiceApi]).
 *
 * UX: a fixed pin sits at the screen centre and the map pans underneath it;
 * whenever the camera settles the centre is reverse-geocoded into an address.
 * Typing in the search box debounces and shows address suggestions; picking
 * one flies the map there. "Use this location" returns the chosen
 * lat/lng + formatted address to the host via [PinResultListener].
 *
 * Any screen that needs a location (CP-visit address, client address, etc.)
 * can open this instead of rebuilding a map picker.
 */
class MapPinDropBottomSheet : BottomSheetDialogFragment() {

    data class PinResult(val lat: Double, val lng: Double, val address: String) {
        val googleMapsLink: String get() = "https://maps.google.com/?q=$lat,$lng"
    }

    fun interface PinResultListener {
        fun onPinPicked(result: PinResult)
    }

    private var listener: PinResultListener? = null
    fun setListener(l: PinResultListener) { listener = l }

    private val mapApi = MapServiceApi.create()

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private lateinit var etSearch: EditText
    private lateinit var pbSearch: ProgressBar
    private lateinit var btnClear: ImageView
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var tvAddress: TextView
    private lateinit var btnConfirm: TextView

    private val suggestionAdapter = SuggestionAdapter { pick -> onSuggestionChosen(pick) }
    private var searchJob: Job? = null
    private var reverseJob: Job? = null

    private var pickedLat: Double? = null
    private var pickedLng: Double? = null
    private var pickedAddress: String = ""
    // While a search suggestion animates the camera, suppress the idle
    // reverse-geocode so it doesn't clobber the chosen address.
    private var suppressNextIdle = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false // map handles the gestures
                it.layoutParams = it.layoutParams.apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_map_pin_drop, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etSearch = view.findViewById(R.id.etPinSearch)
        pbSearch = view.findViewById(R.id.pbPinSearch)
        btnClear = view.findViewById(R.id.btnPinSearchClear)
        rvSuggestions = view.findViewById(R.id.rvPinSuggestions)
        tvAddress = view.findViewById(R.id.tvPinAddress)
        btnConfirm = view.findViewById(R.id.btnPinConfirm)

        rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        rvSuggestions.adapter = suggestionAdapter

        mapView = view.findViewById(R.id.pinMapView)
        mapView?.onCreate(savedInstanceState)
        mapView?.onResume()
        mapView?.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isMyLocationButtonEnabled = false
            map.uiSettings.isMapToolbarEnabled = false
            // Reverse-geocode the centre whenever the camera settles.
            map.setOnCameraIdleListener {
                if (suppressNextIdle) { suppressNextIdle = false; return@setOnCameraIdleListener }
                reverseGeocodeCenter()
            }
            moveToInitialLocation()
        }

        etSearch.addTextChangedListener { text ->
            val q = text?.toString().orEmpty().trim()
            btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            scheduleSearch(q)
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearch(etSearch.text.toString().trim(), immediate = true)
                true
            } else false
        }
        btnClear.setOnClickListener {
            etSearch.setText("")
            hideSuggestions()
        }
        view.findViewById<View>(R.id.btnPinMyLocation).setOnClickListener { moveToDeviceLocation() }
        btnConfirm.setOnClickListener {
            val lat = pickedLat
            val lng = pickedLng
            if (lat == null || lng == null) {
                android.widget.Toast.makeText(
                    requireContext(), "Move the map to set a location", android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            listener?.onPinPicked(PinResult(lat, lng, pickedAddress))
            dismiss()
        }
    }

    // ── Map positioning ─────────────────────────────────────────────────

    private fun moveToInitialLocation() {
        val argLat = arguments?.getDouble(ARG_LAT, Double.NaN)
        val argLng = arguments?.getDouble(ARG_LNG, Double.NaN)
        if (argLat != null && argLng != null && !argLat.isNaN() && !argLng.isNaN()) {
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(argLat, argLng), 16f))
        } else {
            moveToDeviceLocation(fallback = LatLng(DEFAULT_LAT, DEFAULT_LNG))
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveToDeviceLocation(fallback: LatLng? = null) {
        if (!hasLocationPermission()) {
            fallback?.let { googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 12f)) }
            return
        }
        LocationServices.getFusedLocationProviderClient(requireContext())
            .lastLocation
            .addOnSuccessListener { loc ->
                if (!isAdded) return@addOnSuccessListener
                val target = if (loc != null) LatLng(loc.latitude, loc.longitude) else fallback
                target?.let { googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
            }
            .addOnFailureListener {
                fallback?.let { googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 12f)) }
            }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun reverseGeocodeCenter() {
        val center = googleMap?.cameraPosition?.target ?: return
        pickedLat = center.latitude
        pickedLng = center.longitude
        reverseJob?.cancel()
        reverseJob = viewLifecycleOwner.lifecycleScope.launch {
            tvAddress.text = "Locating…"
            val address = runCatching {
                mapApi.reverseGeocode(center.latitude, center.longitude)
                    .results.firstOrNull()?.address
            }.getOrNull()
            if (!isAdded) return@launch
            pickedAddress = address ?: "Pinned location (${fmt(center.latitude)}, ${fmt(center.longitude)})"
            tvAddress.text = pickedAddress
        }
    }

    // ── Search + suggestions ────────────────────────────────────────────

    private fun scheduleSearch(query: String, immediate: Boolean = false) {
        searchJob?.cancel()
        if (query.length < 2) {
            pbSearch.visibility = View.GONE
            hideSuggestions()
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(320) // debounce
            pbSearch.visibility = View.VISIBLE
            val results = runCatching { mapApi.searchAddress(query).results }.getOrNull().orEmpty()
            if (!isAdded) return@launch
            pbSearch.visibility = View.GONE
            if (results.isEmpty()) hideSuggestions()
            else {
                suggestionAdapter.submit(results)
                rvSuggestions.visibility = View.VISIBLE
            }
        }
    }

    private fun onSuggestionChosen(result: MapAddressResult) {
        val lat = result.location?.lat
        val lng = result.location?.lng
        hideSuggestions()
        etSearch.setText(result.displayName)
        etSearch.clearFocus()
        hideKeyboard()
        pickedAddress = result.address ?: result.displayName
        tvAddress.text = pickedAddress
        if (lat != null && lng != null) {
            pickedLat = lat
            pickedLng = lng
            suppressNextIdle = true // keep the chosen address, don't re-reverse
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 16.5f))
        }
    }

    private fun hideSuggestions() {
        rvSuggestions.visibility = View.GONE
        suggestionAdapter.submit(emptyList())
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.5f", v)

    // ── MapView lifecycle ───────────────────────────────────────────────

    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() {
        searchJob?.cancel()
        reverseJob?.cancel()
        mapView?.onDestroy()
        mapView = null
        googleMap = null
        super.onDestroyView()
    }

    // ── Suggestion list ─────────────────────────────────────────────────

    private class SuggestionAdapter(
        private val onClick: (MapAddressResult) -> Unit,
    ) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
        private val items = mutableListOf<MapAddressResult>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MapAddressResult>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_map_suggestion, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.displayName
            holder.subtitle.text = item.address.orEmpty()
            holder.subtitle.visibility =
                if (item.address.isNullOrBlank() || item.address == item.displayName) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(item) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvSuggestionTitle)
            val subtitle: TextView = v.findViewById(R.id.tvSuggestionSubtitle)
        }
    }

    companion object {
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LNG = "arg_lng"
        // Chennai — the app's home region; used only until GPS / a search
        // moves the camera.
        private const val DEFAULT_LAT = 13.0827
        private const val DEFAULT_LNG = 80.2707

        /** Optionally seed the map at an existing pin. */
        fun newInstance(lat: Double? = null, lng: Double? = null) = MapPinDropBottomSheet().apply {
            if (lat != null && lng != null) {
                arguments = Bundle().apply {
                    putDouble(ARG_LAT, lat)
                    putDouble(ARG_LNG, lng)
                }
            }
        }
    }
}
