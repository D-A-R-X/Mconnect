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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.ActivityGeoConsentBinding
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GeoTrackConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeoConsentBinding
    private lateinit var session: SessionManager
    /**
     * Which Settings trip is outstanding, or null. A bare boolean could not say
     * whether the user went to grant fine or background location, and the two
     * have to resume the chain at different points.
     */
    private var settingsReturn: String? = null

    /**
     * Guards against leaving twice. Several tails can reach [goToMain] — the
     * setup coroutine, its timeout, and a "Not now" on a permission refusal —
     * and a second startActivity would stack another MainActivity behind the
     * one the user is already looking at.
     */
    private var navigated = false

    /** True while the post-permission setup runs, so the screen cannot be re-entered. */
    private var setupInFlight = false

    companion object {
        /**
         * True while a consent screen instance is alive. Bootstrap sync runs on
         * every MainActivity resume / punch, and each pass would otherwise launch
         * a fresh consent screen — stacking duplicates. The launcher checks this
         * flag so it never opens a second one.
         */
        @Volatile
        var isActive = false

        private const val STATE_SETTINGS_RETURN = "settingsReturn"
        private const val SETTINGS_RETURN_FINE = "fine"
        private const val SETTINGS_RETURN_BACKGROUND = "background"

        /**
         * Long enough for the sync to finish on a normal connection, short
         * enough that a stalled backend does not hold the user on a consent
         * screen. Whatever does not complete here is retried on Home.
         */
        private const val SETUP_TIMEOUT_MS = 8_000L
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings. WHICH trip it was matters: a fine-location trip
        // has to rejoin the chain at background location, while a background
        // trip must move on even if nothing was granted — re-showing that
        // dialog on every return is how a user gets stuck in a loop.
        val pendingReturn = settingsReturn ?: return
        settingsReturn = null
        when (pendingReturn) {
            SETTINGS_RETURN_FINE -> {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestBackgroundLocation()
                } else {
                    handleLocationPermissionDenied()
                }
            }
            SETTINGS_RETURN_BACKGROUND -> {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                Toast.makeText(
                    this,
                    if (granted) {
                        "Background location granted!"
                    } else {
                        "Background location not granted — tracking may not work when screen is off"
                    },
                    if (granted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                requestActivityRecognition()
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            requestBackgroundLocation()
        } else {
            // Previously a Toast and nothing else — a dead end. Once the OS has
            // recorded two refusals it stops showing the dialog at all, so the
            // launcher returns instantly with "denied" and the button looked
            // like it did nothing. There must always be a way off this screen.
            handleLocationPermissionDenied()
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
        isActive = true
        binding = ActivityGeoConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        settingsReturn = savedInstanceState?.getString(STATE_SETTINGS_RETURN)

        binding.btnConsent.setOnClickListener {
            recordConsentAndRequestPermissions()
        }

        binding.btnDecline.setOnClickListener {
            session.geoConsentGiven = false
            session.geoConsentDeclined = true
            session.shouldTrackNow = false
            goToMain()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The Settings round-trip can outlive this activity on a low-memory
        // device. Without this the return lands on a fresh consent screen with
        // no idea it was mid-flow, and the user starts over.
        outState.putString(STATE_SETTINGS_RETURN, settingsReturn)
    }

    private fun recordConsentAndRequestPermissions() {
        // Re-entering the chain mid-flight relaunches permission requests on top
        // of each other and can fire the setup tail twice.
        if (setupInFlight) return
        session.geoConsentGiven = true
        session.geoConsentDeclined = false
        requestLocationPermissions()
    }

    /**
     * Fine location refused — the one branch that used to have no exit.
     *
     * Retry is offered only while the OS will still show its dialog. Once the
     * refusal is permanent, `requestPermissions` returns immediately and a retry
     * button would just re-trigger the same silent denial, so the only real
     * options are app settings or leaving.
     *
     * "Not now" deliberately keeps consent recorded and drops the user on Home:
     * tracking simply will not start, and the standing permission-alert
     * notification already nags for the missing grant. Trapping them here does
     * not make the permission any more granted.
     */
    private fun handleLocationPermissionDenied() {
        if (isFinishing || isDestroyed) return
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Location permission needed")
            .setMessage(
                if (canAskAgain) {
                    "M Connect cannot track field work without location access. " +
                        "Please allow it to continue."
                } else {
                    "Location access is blocked for M Connect. Turn it on in " +
                        "Settings → Permissions → Location to enable tracking."
                },
            )
            .setNegativeButton("Not now") { _, _ -> goToMain() }
        if (canAskAgain) {
            builder.setPositiveButton("Try again") { _, _ -> requestLocationPermissions() }
        } else {
            builder.setPositiveButton("Open Settings") { _, _ ->
                settingsReturn = SETTINGS_RETURN_FINE
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    },
                )
            }
        }
        builder.setCancelable(false).show()
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun requestBackgroundLocation() {
        // Short-circuit when background location is already granted —
        // previously the Android 11+ branch always opened the
        // "Background Location Required" dialog, so users who'd already
        // approved "Allow all the time" had to keep dismissing it on
        // every consent run. Check first, advance straight to the next
        // step if we're good.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            requestActivityRecognition()
            return
        }

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
                    settingsReturn = SETTINGS_RETURN_BACKGROUND
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

    /**
     * Last step: kick tracking off, then leave.
     *
     * This used to await two attendance calls and a bootstrap sync — four
     * network round-trips at a 30s timeout each — before navigating, with the
     * button still enabled and no sign anything was happening. On a slow
     * connection the screen sat there for a minute looking dead, and an
     * exception anywhere in the chain meant `goToMain()` never ran at all and
     * the user was stranded for good.
     *
     * Now: bounded, guarded, and navigation happens in `finally` so there is no
     * path that leaves the user on this screen. Consent is already persisted
     * locally, and the same sync re-runs on every MainActivity resume and on
     * every punch, so finishing it here was never actually required — only
     * nice to have.
     */
    private fun startTrackingService() {
        if (setupInFlight) return
        setupInFlight = true
        setBusy(true)
        lifecycleScope.launch {
            try {
                withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                    val attendanceActive = runCatching {
                        AttendanceTrackingGate.isClockedInForToday(
                            session.bearerToken,
                            ApiService.create(),
                        )
                    }.getOrDefault(false)
                    runCatching {
                        GeoTrackBootstrapSync.sync(
                            context = this@GeoTrackConsentActivity,
                            allowPromptConsent = false,
                            attendanceOpenOverride = attendanceActive,
                        )
                    }
                }
            } finally {
                setupInFlight = false
                goToMain()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConsent.isEnabled = !busy
        binding.btnDecline.isEnabled = !busy
        binding.btnConsent.text =
            if (busy) "Setting up tracking…" else "I Understand and Agree"
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

}
