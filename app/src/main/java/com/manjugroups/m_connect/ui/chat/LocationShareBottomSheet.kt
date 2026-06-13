package com.manjugroups.m_connect.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.ChipGroup
import com.manjugroups.m_connect.R
import java.util.*
import android.app.Dialog

class LocationShareBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    interface LocationShareListener {
        fun onLocationShared(locationString: String)
    }

    private var listener: LocationShareListener? = null
    fun setListener(listener: LocationShareListener) {
        this.listener = listener
    }

    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvState: TextView
    private lateinit var imgRadarSweep: ImageView
    private lateinit var btnShare: Button

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var lastKnownLocation: Location? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_location_share, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind Views
        tvLatitude = view.findViewById(R.id.tvLocationLatitude)
        tvLongitude = view.findViewById(R.id.tvLocationLongitude)
        tvBattery = view.findViewById(R.id.tvLocationBattery)
        tvSpeed = view.findViewById(R.id.tvLocationSpeed)
        tvAccuracy = view.findViewById(R.id.tvLocationAccuracy)
        tvState = view.findViewById(R.id.tvLocationState)
        imgRadarSweep = view.findViewById(R.id.imgRadarSweep)
        btnShare = view.findViewById(R.id.btnLocationShareSend)

        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())

        // Start Radar Rotation Animation
        startRadarSweepAnimation()

        // Fetch Initial Battery Telemetry
        updateBatteryUi()

        // Request Location Updates
        startLocationUpdatesSafe()

        // Handle Share Click
        btnShare.setOnClickListener {
            val loc = lastKnownLocation
            if (loc == null) {
                Toast.makeText(requireContext(), "Acquiring GPS Lock... Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val contextLabel = "Current Location"

            val battery = getBatteryPercentage()
            val rawSpeedKmH = (loc.speed * 3.6).toInt()
            val speedKmH = if (rawSpeedKmH >= 3) rawSpeedKmH else 0
            val status = if (speedKmH >= 3) "Moving" else "Stationary"

            // Construct telemetry protocol string
            val formatted = "[LOCATION:lat=${loc.latitude};lon=${loc.longitude};label=$contextLabel;battery=$battery;speed=$speedKmH;status=$status]"
            
            listener?.onLocationShared(formatted)
            dismiss()
        }
    }

    private fun startRadarSweepAnimation() {
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3000
            repeatCount = Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        imgRadarSweep.startAnimation(rotate)
    }

    private fun getBatteryPercentage(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = requireContext().registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            100
        }
    }

    private fun updateBatteryUi() {
        val pct = getBatteryPercentage()
        tvBattery.text = "$pct%"
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesSafe() {
        if (!hasLocationPermissions()) {
            tvState.text = "Permission Denied"
            tvState.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            return
        }

        tvState.text = "Acquiring GPS Lock..."
        tvState.setTextColor(ColorStateListHelper.getColor(requireContext(), "#FF9F0A"))

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastKnownLocation = loc
                updateLocationUi(loc)
            }
        }

        try {
            fusedClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            tvState.text = "GPS Error"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateLocationUi(location: Location) {
        if (!isAdded) return
        tvLatitude.text = String.format(Locale.US, "%.6f°", location.latitude)
        tvLongitude.text = String.format(Locale.US, "%.6f°", location.longitude)
        tvAccuracy.text = String.format(Locale.US, "±%.1fm", location.accuracy)
        
        val rawSpeedKmH = (location.speed * 3.6).toInt()
        val speedKmH = if (rawSpeedKmH >= 3) rawSpeedKmH else 0
        tvSpeed.text = if (speedKmH >= 3) "$speedKmH km/h" else "Stationary"

        tvState.text = "Telemetry Active"
        tvState.setTextColor(ColorStateListHelper.getColor(requireContext(), "#38A612"))
        updateBatteryUi()
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationCallback?.let {
            fusedClient?.removeLocationUpdates(it)
        }
        imgRadarSweep.clearAnimation()
    }
}

// Simple internal helper to parse hex colors without crash risks
object ColorStateListHelper {
    fun getColor(context: Context, hex: String): Int {
        return try {
            android.graphics.Color.parseColor(hex)
        } catch (e: Exception) {
            ContextCompat.getColor(context, android.R.color.darker_gray)
        }
    }
}
