package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.ActivityGeoConsentBinding
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ConsentRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch

class GeoTrackConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeoConsentBinding
    private lateinit var session: SessionManager
    private val api = GeoTrackApi.create()
    private var permissionCheckPending = false

    override fun onResume() {
        super.onResume()
        // User returned from Settings — check if background location was granted
        if (permissionCheckPending) {
            permissionCheckPending = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Background location granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Background location not granted — tracking may not work when screen is off", Toast.LENGTH_LONG).show()
            }
            requestActivityRecognition()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            requestBackgroundLocation()
        } else {
            Toast.makeText(this, "Location permission is required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestActivityRecognition()
        } else {
            // Still proceed — background location is optional but recommended
            requestActivityRecognition()
        }
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestNotificationPermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestBatteryOptimizationExemption()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        startTrackingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeoConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        binding.btnConsent.setOnClickListener {
            recordConsentAndRequestPermissions()
        }

        binding.btnDecline.setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    api.recordTrackingConsent(
                        session.bearerToken,
                        ConsentRequest(
                            consented = false,
                            appVersion = BuildConfig.VERSION_NAME,
                            policyKey = "attendance_field",
                            status = "declined",
                            deviceId = session.trackingDeviceId,
                        )
                    )
                }
                session.geoConsentDeclined = true
                goToMain()
            }
        }
    }

    private fun recordConsentAndRequestPermissions() {
        lifecycleScope.launch {
            try {
                val response = api.recordTrackingConsent(
                    session.bearerToken,
                    ConsentRequest(
                        consented = true,
                        appVersion = BuildConfig.VERSION_NAME,
                        policyKey = "attendance_field",
                        status = "granted",
                        deviceId = session.trackingDeviceId,
                    )
                )
                session.geoConsentGiven = true
                session.geoTrackingEnabled = response.bootstrap?.assignment?.attendance != null || response.bootstrap?.assignment?.siteVisit != null
                session.activeTrackingSessionId = response.bootstrap?.activeSession?.id
                session.shouldTrackNow = response.bootstrap?.shouldTrack == true
            } catch (e: Exception) {
                // Still proceed even if server call fails — consent is recorded locally
                session.geoConsentGiven = true
            }
            requestLocationPermissions()
        }
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — system won't show "Allow all the time" in dialog
            // Must redirect user to Settings. Tracking-enabled staff cannot
            // skip this step: without "Allow all the time" the OS pauses
            // FusedLocation callbacks the moment the app leaves foreground,
            // so the day's locationPoints stream silently dies. We removed
            // the Skip button — the only path forward is Open Settings →
            // Location → Allow all the time.
            android.app.AlertDialog.Builder(this)
                .setTitle("Background Location Required")
                .setMessage("For tracking to work when the screen is off, you must select \"Allow all the time\" in Location settings.\n\nTap Open Settings → Location → Allow all the time")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                    // Check permission when user returns
                    permissionCheckPending = true
                }
                .setCancelable(false)
                .show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestActivityRecognition()
        }
    }

    private fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTrackingService()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startTrackingService()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Keep Tracking Active")
            .setMessage("To ensure attendance tracking and heartbeat work reliably on all devices, please disable battery optimization for this app.\n\nTap Allow on the next screen.")
            .setPositiveButton("Allow") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryOptimizationLauncher.launch(intent)
            }
            .setCancelable(false)
            .show()
    }

    private fun startTrackingService() {
        lifecycleScope.launch {
            val attendanceActive = runCatching {
                AttendanceTrackingGate.isClockedInForToday(session.bearerToken, ApiService.create())
            }.getOrDefault(false)
            session.shouldTrackNow = attendanceActive && session.shouldTrackNow
            if (attendanceActive) {
                GeoTrackService.start(this@GeoTrackConsentActivity)
            } else {
                GeoTrackService.stop(this@GeoTrackConsentActivity)
            }
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
