package com.manjugroups.m_connect.geotrack.service

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.geotrack.GeoTrackEventQueue
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.LocationPointEntity
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.HeartbeatRequest
import com.manjugroups.m_connect.network.LocationPoint
import com.manjugroups.m_connect.network.PushBatchRequest
import com.manjugroups.m_connect.network.TamperReportRequest
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GeoTrackService : Service() {

    companion object {
        private const val TAG = "GeoTrackSvc"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "geotrack_channel"
        private const val SYNC_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 120_000L
        private const val LOCATION_INTERVAL_MS = 10_000L
        // Short, self-timing wakelock taken ONLY around a network sync burst
        // (not for the whole shift) so an upload can finish even if the device
        // is dozing. The OS auto-releases after this timeout as a safety net.
        private const val SYNC_WAKELOCK_MS = 15_000L
        // The persistent foreground notification is mandatory for a location
        // service, but it shows a single clean, user-facing line — not the
        // internal capture/sync/battery counters that used to leak to staff.
        private const val TRACKING_NOTIF_TEXT = "Location tracking is active during your shift."
        private const val MAX_POINT_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        // Unsent points hang around for 30 days so a multi-day offline
        // period (field staff with no signal) still flushes when the
        // device reconnects, instead of silently losing the journey to
        // the 7-day cleanup.
        private const val MAX_UNSENT_POINT_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_SYNC_RETRIES = 5
        // Batch sizing: drain the queue in chunks of 200 but flush up
        // to 25 chunks per sync cycle (5000 points = ~14 hours at 10s
        // intervals). That's enough to recover almost any realistic
        // offline period in a single cycle when the network returns.
        private const val BATCH_SIZE = 200
        private const val MAX_BATCHES_PER_SYNC = 25

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "Service already running, skipping start")
                return
            }
            if (!hasRequiredLocationPermissions(context)) {
                Log.w(TAG, "Skipping GeoTrack start: required location permissions are not granted")
                return
            }
            Log.i(TAG, ">>> Starting GeoTrack service")
            val intent = Intent(context, GeoTrackService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { e ->
                Log.e(TAG, "Unable to start GeoTrack service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            Log.i(TAG, ">>> Stopping GeoTrack service")
            context.stopService(Intent(context, GeoTrackService::class.java))
        }

        fun hasRequiredLocationPermissions(context: Context): Boolean {
            val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasFgsLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            return (hasFine || hasCoarse) && hasFgsLocation
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var session: SessionManager
    private lateinit var db: GeoTrackDatabase
    private lateinit var api: GeoTrackApi
    private var locationManagerListener: android.location.LocationListener? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Thread-safe state
    @Volatile private var lastActivity = "STILL"
    @Volatile private var lastActivityConfidence = 0
    private val pointsCaptured = AtomicInteger(0)
    private val pointsSynced = AtomicInteger(0)
    private val firstLocationReceived = AtomicBoolean(false)
    private val lastProcessedTime = AtomicLong(0L)
    // Live clock-in gate. GeoTrack must capture travel ONLY between punch-in
    // and punch-out, so we refuse to buffer points (and tear the service down)
    // the moment the attendance session is no longer open. Defaults to true
    // because the service is only ever started off a clock-in; the first
    // enforceClockInGate() pass confirms it and stops if we're actually
    // clocked out (stale/boot/biometric/before-punch starts).
    @Volatile private var sessionOpen = true
    @Volatile private var clockGateStopped = false
    private val offlineStartedAt = AtomicLong(0L)
    private var consecutiveSyncFailures = 0

    private var locationThread: HandlerThread? = null
    private var fusedLocationRegistered = false
    private var activityPendingIntent: PendingIntent? = null

    // Stationary dedup + GPS drift filter
    @Volatile private var lastStoredLat = 0.0
    @Volatile private var lastStoredLng = 0.0
    @Volatile private var lastStoredTimeMs = 0L
    private val STATIONARY_RADIUS_M = 50f       // Skip if within 50m of last stored point
    private val DRIFT_SPEED_THRESHOLD = 1.0f     // m/s — below this, treat position jumps as drift
    private val DRIFT_DISTANCE_THRESHOLD = 100f  // If speed <1 m/s but jumped >100m, it's GPS drift
    private val STATIONARY_PING_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    // ── Receivers ──

    private val airplaneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val isOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            Log.i(TAG, "Airplane mode changed: $isOn")
            serviceScope.launch {
                reportTamper(if (isOn) "AIRPLANE_MODE_ON" else "AIRPLANE_MODE_OFF")
            }
        }
    }

    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val gpsEnabled = (getSystemService(LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
            Log.i(TAG, "GPS provider changed: enabled=$gpsEnabled")
            serviceScope.launch {
                reportTamper(if (gpsEnabled) "GPS_ENABLED" else "GPS_DISABLED")
            }
        }
    }

    // ── Location callback ──

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val best = result.locations.maxByOrNull { it.accuracy.let { a -> -a } } ?: return
            serviceScope.launch { processLocation(best) }
        }
    }

    // ── Lifecycle ──

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        session = SessionManager(this)
        db = GeoTrackDatabase.getInstance(this)
        api = GeoTrackApi.create()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand — token=${session.token?.take(10)}...")
        if (!session.shouldTrackNow || session.activeTrackingSessionId.isNullOrBlank()) {
            Log.i(TAG, "No active tracking session from server bootstrap — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasRequiredLocationPermissions(this)) {
            Log.w(TAG, "Stopping GeoTrack service: required location permissions are not granted")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        if (!startForegroundSafely()) {
            Log.w(TAG, "Stopping GeoTrack service: could not enter foreground state safely")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // No always-on wakelock: the location-type foreground service keeps
        // fused updates flowing in Doze. We only take a short timed wakelock
        // around each sync burst (see runWithSyncWakeLock).

        val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
        Log.i(TAG, "Permissions: FINE_LOCATION=$hasFine BACKGROUND_LOCATION=$hasBackground")
        if (!hasFine) Log.e(TAG, "FINE_LOCATION NOT GRANTED — GPS will NOT work!")
        serviceScope.launch { queuePermissionHealthEvents(hasFine, hasBackground) }
        if (!isGpsEnabled()) {
            serviceScope.launch { reportTamper("GPS_DISABLED") }
        }

        try { requestLocationUpdates() } catch (e: Exception) { Log.e(TAG, "Location registration failed: ${e.message}", e) }
        try { requestActivityRecognition() } catch (e: Exception) { Log.e(TAG, "Activity recognition failed: ${e.message}") }
        try { registerReceivers() } catch (e: Exception) { Log.e(TAG, "Receiver registration failed: ${e.message}") }

        startSyncLoop()
        startHeartbeatLoop()
        startCleanupLoop()
        registerNetworkCallback()

        try { getLastLocationAndNotifyServer() } catch (e: Exception) { Log.e(TAG, "Server notify failed: ${e.message}") }

        Log.i(TAG, "Service fully initialized")
        return START_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        return runCatching {
            val notification = buildNotif(TRACKING_NOTIF_TEXT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { e ->
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }.isSuccess
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — flushing ${pointsCaptured.get() - pointsSynced.get()} pending points")
        isRunning = false
        fusedClient.removeLocationUpdates(locationCallback)
        // Remove activity recognition
        activityPendingIntent?.let {
            try { ActivityRecognition.getClient(this).removeActivityTransitionUpdates(it) } catch (_: Exception) {}
        }
        // Remove LocationManager listener
        locationManagerListener?.let {
            try {
                (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it)
            } catch (_: Exception) {}
        }
        locationThread?.quitSafely()
        locationThread = null
        unregisterReceivers()
        unregisterNetworkCallback()

        // Final sync — guarded by a short timed wakelock so it can complete
        // even if the device is dozing at teardown.
        runBlocking(Dispatchers.IO) {
            try {
                runWithSyncWakeLock {
                    syncPoints()
                    syncEvents()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Final sync failed: ${e.message}")
            }
        }

        syncJob?.cancel()
        heartbeatJob?.cancel()
        cleanupJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── WakeLock ──
    //
    // We deliberately do NOT hold a wakelock for the whole tracking session.
    // A FOREGROUND_SERVICE_TYPE_LOCATION service keeps receiving fused
    // location updates even in Doze, so pinning the CPU awake for an entire
    // 8–10h shift was pure waste — it was the main reason devices overheated
    // and drained while sitting idle in a pocket. Instead we take a SHORT,
    // self-timing wakelock only around a network sync burst so the upload can
    // finish if the device happens to be dozing, then immediately let the CPU
    // sleep again.

    private suspend fun runWithSyncWakeLock(block: suspend () -> Unit) {
        val wl: PowerManager.WakeLock? = try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MConnect::GeoTrackSync")
                .apply { acquire(SYNC_WAKELOCK_MS) }
        } catch (e: Exception) {
            Log.e(TAG, "Sync wakelock acquire failed: ${e.message}")
            null
        }
        try {
            block()
        } finally {
            try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
        }
    }

    // ── Location ──

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        locationThread = HandlerThread("GeoTrackLocation").apply { start() }
        val looper = locationThread!!.looper

        // Primary: FusedLocationProviderClient (Google Play Services)
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(5_000)
                .setWaitForAccurateLocation(false)
                .build()

            fusedClient.requestLocationUpdates(req, locationCallback, looper)
                .addOnSuccessListener {
                    fusedLocationRegistered = true
                    Log.i(TAG, "FusedLocation registered")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "FusedLocation FAILED: ${e.message} — falling back to LocationManager")
                    startLocationManagerFallback(looper)
                }
        } catch (e: Exception) {
            Log.e(TAG, "FusedLocation exception: ${e.message} — using LocationManager")
            startLocationManagerFallback(looper)
        }

        // Delayed fallback: if FusedLocation didn't register in 5 seconds, start LocationManager
        serviceScope.launch {
            delay(5_000)
            if (!fusedLocationRegistered) {
                Log.w(TAG, "FusedLocation not confirmed after 5s, starting LocationManager fallback")
                startLocationManagerFallback(looper)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationManagerFallback(looper: Looper) {
        if (locationManagerListener != null) return // Already registered
        try {
            // Only ever drive ONE location source at a time. If we're falling
            // back to LocationManager, stop fused first so the GPS chip isn't
            // pumped by both stacks at once (double the power draw / heat).
            try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
            fusedLocationRegistered = false
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    serviceScope.launch { processLocation(location) }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            locationManagerListener = listener

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, listener, looper)
                Log.i(TAG, "LocationManager GPS registered (fallback)")
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL_MS, 0f, listener, looper)
                Log.i(TAG, "LocationManager Network registered (fallback)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LocationManager fallback failed: ${e.message}", e)
        }
    }

    // ── Activity Recognition ──

    @SuppressLint("MissingPermission")
    private fun requestActivityRecognition() {
        val transitions = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE
        ).flatMap { activity ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
        }

        val request = ActivityTransitionRequest(transitions)
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        activityPendingIntent = pi

        ActivityRecognition.getClient(this)
            .requestActivityTransitionUpdates(request, pi)
            .addOnSuccessListener { Log.i(TAG, "Activity recognition registered") }
            .addOnFailureListener { e -> Log.e(TAG, "Activity recognition failed: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocationAndNotifyServer() {
        fusedClient.lastLocation
            .addOnSuccessListener { loc: Location? ->
                Log.i(TAG, "Last location: ${loc?.latitude},${loc?.longitude} (null=${loc == null})")
                serviceScope.launch {
                    // Send immediate heartbeat so battery shows up right away
                    try {
                        api.heartbeat(
                            session.bearerToken,
                            HeartbeatRequest(
                                sessionId = session.activeTrackingSessionId,
                                deviceId = session.trackingDeviceId,
                                batteryPct = getBatteryLevel(),
                                appVersion = BuildConfig.VERSION_NAME,
                            )
                        )
                        Log.i(TAG, "Initial heartbeat sent")
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial heartbeat failed: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { Log.e(TAG, "getLastLocation failed: ${it.message}") }
    }

    private suspend fun processLocation(location: Location) {
        // Clock-in gate — never buffer a point outside the punch-in → punch-out
        // window. `sessionOpen` flips false as soon as enforceClockInGate() sees
        // a closed session, so pre-clock-in and post-clock-out travel is dropped
        // before it ever reaches the local buffer or the server.
        if (!sessionOpen || clockGateStopped) return
        // Dedup: skip if less than 3 seconds since last processed point (atomic)
        val now = System.currentTimeMillis()
        val last = lastProcessedTime.get()
        if (now - last < 3_000) return
        if (!lastProcessedTime.compareAndSet(last, now)) return // Another thread got here first

        // Skip very inaccurate points (>50m) — indoor GPS jitter
        if (location.accuracy > 50f) {
            Log.d(TAG, "Skipping inaccurate point: accuracy=${location.accuracy}m")
            return
        }

        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else @Suppress("DEPRECATION") location.isFromMockProvider

        if (isMock) reportTamper("MOCK_LOCATION")

        // ── Smart dedup: different rules for moving vs stationary ──
        if (lastStoredLat != 0.0) {
            val distFromLast = distanceMeters(
                lastStoredLat, lastStoredLng,
                location.latitude, location.longitude
            )
            val timeSinceLastStore = now - lastStoredTimeMs
            val isMoving = location.speed >= 1.5f // > 1.5 m/s = ~5.4 km/h = walking/driving

            if (isMoving) {
                // MOVING: store every point that's >15m apart (good density for road snapping)
                // Roads API needs points every ~50-100m for proper snapping
                // At 30 km/h city driving + 10s interval = ~83m spacing — perfect
                if (distFromLast < 15f) return

                // Drift check: speed says moving but jumped impossibly far
                if (distFromLast > 500f && location.speed < 15f) {
                    Log.d(TAG, "GPS teleport detected: dist=${distFromLast}m at speed=${location.speed} — skipping")
                    return
                }
            } else {
                // STATIONARY: only store once every 5 minutes to avoid building clutter
                // Drift filter: position jumped >100m but speed is near zero = GPS noise
                if (distFromLast > DRIFT_DISTANCE_THRESHOLD) {
                    Log.d(TAG, "GPS drift detected: speed=${location.speed} but dist=${distFromLast}m — skipping")
                    return
                }
                // Within 50m and less than 5 min → skip
                if (distFromLast < STATIONARY_RADIUS_M && timeSinceLastStore < STATIONARY_PING_INTERVAL_MS) {
                    return
                }
            }
        }

        // Infer activity from speed when activity recognition isn't available
        val activity = inferActivityFromSpeed(location.speed)

        val entity = LocationPointEntity(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            bearing = location.bearing,
            altitude = if (location.hasAltitude()) location.altitude else null,
            activity = activity,
            activityConfidence = if (ActivityRecognitionReceiver.currentConfidence > 0) ActivityRecognitionReceiver.currentConfidence else 70,
            isMock = isMock,
            batteryPct = getBatteryLevel(),
            networkType = getNetworkType(),
            gpsEnabled = isGpsEnabled(),
            airplaneMode = isAirplaneModeOn(),
            recordedAt = System.currentTimeMillis()
        )

        db.locationPointDao().insert(entity)
        lastStoredLat = location.latitude
        lastStoredLng = location.longitude
        lastStoredTimeMs = now
        val captured = pointsCaptured.incrementAndGet()

        Log.d(TAG, "GPS #$captured: ${location.latitude},${location.longitude} spd=${location.speed} acc=${location.accuracy} act=$activity")

        // On first location, do an immediate sync
        if (firstLocationReceived.compareAndSet(false, true)) {
            Log.i(TAG, "First location received — triggering immediate sync")
            syncPoints()
        }
    }

    private fun inferActivityFromSpeed(speedMps: Float): String {
        // Prefer activity recognition hardware data if available
        val recognized = ActivityRecognitionReceiver.currentActivity
        if (recognized != "STILL" && recognized != "UNKNOWN") {
            lastActivity = recognized
            lastActivityConfidence = ActivityRecognitionReceiver.currentConfidence
            return recognized
        }
        // Fallback: infer from GPS speed
        return when {
            speedMps < 0.5f -> "STILL"
            speedMps < 1.5f -> "ON_FOOT"
            speedMps < 3.0f -> "WALKING"
            speedMps < 8.0f -> "ON_BICYCLE"
            else -> "IN_VEHICLE"
        }
    }

    // ── Sync Loop ──

    /**
     * Listen for connectivity changes so we sync the moment network
     * returns instead of waiting up to 30 s for the next tick. Critical
     * for short outages (a tunnel, a building's dead zone) where the
     * staff doesn't want a visible gap on the Journey Timeline. Also
     * resets the failure backoff so a successful reconnect doesn't get
     * delayed by a previous string of failures.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.i(TAG, "Network available — kicking immediate sync")
                    consecutiveSyncFailures = 0
                    serviceScope.launch {
                        try { markNetworkOnlineIfNeeded() } catch (_: Exception) {}
                        try { syncPoints() } catch (e: Exception) {
                            Log.w(TAG, "Immediate sync on reconnect failed: ${e.message}")
                        }
                        try { syncEvents() } catch (_: Exception) {}
                    }
                }
                override fun onLost(network: android.net.Network) {
                    Log.d(TAG, "Network lost — points will buffer locally")
                    serviceScope.launch {
                        try { markNetworkOfflineIfNeeded() } catch (_: Exception) {}
                    }
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkCallback = null
    }

    /**
     * Authoritative clock-in gate for GeoTrack. Confirms an attendance session
     * is open right now; if it has closed (clock-out) — or was never open (a
     * stale/boot/before-punch start) — we stop buffering and tear the service
     * down so no out-of-window travel is ever recorded or uploaded.
     *
     * Offline-safe: a null result (network/server error) is treated as
     * "unknown" and tracking continues, so a transient outage never drops a
     * legitimate in-window journey. Clock-out itself always happens online, so
     * a real clock-out is detected on the next successful check.
     *
     * @return true to keep running, false if we stopped the service.
     */
    private suspend fun enforceClockInGate(): Boolean {
        if (clockGateStopped) return false
        val open = AttendanceTrackingGate.hasOpenSessionNow(session.bearerToken)
            ?: return true // unknown — keep tracking, recheck next cycle
        sessionOpen = open
        if (open) return true
        Log.i(TAG, "Attendance session closed (clocked out) — stopping GeoTrack")
        clockGateStopped = true
        // Clear the bootstrap flags so START_STICKY / a later bootstrap sync
        // doesn't resurrect tracking until the next genuine clock-in.
        runCatching {
            session.shouldTrackNow = false
            session.activeTrackingSessionId = null
        }
        stopSelf()
        return false
    }

    private fun startSyncLoop() {
        syncJob = serviceScope.launch {
            // Immediate gate check at startup — catches a service that came up
            // while already clocked out (stale prefs, boot, biometric gate
            // punch that closed at once, or a pre-clock-in start).
            if (!enforceClockInGate()) return@launch
            delay(SYNC_INTERVAL_MS)
            while (isActive) {
                if (hasNetwork()) {
                    markNetworkOnlineIfNeeded()
                    if (!enforceClockInGate()) break
                    runWithSyncWakeLock {
                        syncPoints()
                        syncEvents()
                    }
                } else {
                    markNetworkOfflineIfNeeded()
                    Log.d(TAG, "Sync: no network, skipping")
                }
                // Adaptive delay: back off if failing
                val delay = if (consecutiveSyncFailures > 0) {
                    val backoff = SYNC_INTERVAL_MS * minOf(consecutiveSyncFailures, MAX_SYNC_RETRIES)
                    Log.d(TAG, "Sync backoff: ${backoff}ms (failures=$consecutiveSyncFailures)")
                    backoff
                } else SYNC_INTERVAL_MS
                delay(delay)
            }
        }
    }

    private suspend fun syncEvents() {
        try {
            val flushed = GeoTrackEventQueue.flush(this, api, session)
            if (flushed > 0) Log.i(TAG, "Event sync OK: $flushed pushed")
        } catch (e: Exception) {
            Log.w(TAG, "Event sync failed: ${e.message}")
        }
    }

    private suspend fun syncPoints() {
        try {
            val activeSessionId = session.activeTrackingSessionId
            if (activeSessionId.isNullOrBlank()) {
                Log.d(TAG, "Sync skipped: no active tracking session id")
                return
            }
            val dao = db.locationPointDao()
            // Drain the entire backlog in batches of 200, not just the
            // first 200. After a long offline period (a 6-hour outage
            // at 10s intervals = 2160 points) the old single-batch
            // sync would leave 90% of the journey stuck on-device for
            // hours until enough 30s ticks fired.
            var batchesFlushed = 0
            var pointsThisCycle = 0
            // Bounded loop: exits on empty queue or MAX_BATCHES_PER_SYNC
            // cap. No cancellation check needed — the surrounding
            // coroutine's own cancellation will short-circuit the next
            // suspending dao/api call.
            while (true) {
                val unsent = dao.getUnsent(BATCH_SIZE)
                if (unsent.isEmpty()) {
                    if (batchesFlushed == 0) Log.d(TAG, "Sync: no pending points")
                    break
                }

                Log.d(TAG, "Sync: pushing ${unsent.size} points (batch ${batchesFlushed + 1})...")

                val points = unsent.map { e ->
                    LocationPoint(
                        lat = e.lat, lng = e.lng, accuracy = e.accuracy,
                        speed = e.speed, bearing = e.bearing, altitude = e.altitude,
                        activity = e.activity, activityConfidence = e.activityConfidence,
                        isMock = e.isMock, batteryPct = e.batteryPct,
                        networkType = e.networkType, gpsEnabled = e.gpsEnabled,
                        airplaneMode = e.airplaneMode, recordedAt = e.recordedAt
                    )
                }

                val resp = api.pushBatch(
                    session.bearerToken,
                    PushBatchRequest(
                        sessionId = activeSessionId,
                        deviceId = session.trackingDeviceId,
                        points = points
                    )
                )

                if (resp.success) {
                    dao.deleteByIds(unsent.map { it.id })
                    val inserted = resp.inserted ?: unsent.size
                    pointsSynced.addAndGet(inserted)
                    pointsThisCycle += inserted
                    batchesFlushed++
                    consecutiveSyncFailures = 0
                    // Safety cap: bail after this many batches per
                    // cycle so we don't monopolise the service scope
                    // when a giant backlog is finally draining.
                    if (batchesFlushed >= MAX_BATCHES_PER_SYNC) {
                        Log.i(TAG, "Sync: hit max batches per cycle ($MAX_BATCHES_PER_SYNC); resuming next tick")
                        break
                    }
                    // If we got fewer points than we asked for, the
                    // queue is empty — done.
                    if (unsent.size < BATCH_SIZE) break
                } else {
                    consecutiveSyncFailures++
                    Log.e(TAG, "Sync FAILED — server said: ${resp.error} (failures=$consecutiveSyncFailures)")
                    if (resp.error?.contains("Tracking session not active", ignoreCase = true) == true) {
                        session.shouldTrackNow = false
                        session.activeTrackingSessionId = null
                        stopSelf()
                    }
                    return
                }
            }
            if (batchesFlushed > 0) {
                Log.i(TAG, "Sync OK: $pointsThisCycle points across $batchesFlushed batches (total saved: ${pointsSynced.get()})")
            }
            return
        } catch (e: Exception) {
            consecutiveSyncFailures++
            Log.e(TAG, "Sync EXCEPTION: ${e.javaClass.simpleName}: ${e.message} (failures=$consecutiveSyncFailures)", e)
        }
    }

    // ── Heartbeat ──

    private fun startHeartbeatLoop() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val battery = getBatteryLevel()
                val sessionId = session.activeTrackingSessionId
                val deviceId = session.trackingDeviceId
                try {
                    runWithSyncWakeLock {
                        api.heartbeat(
                            session.bearerToken,
                            HeartbeatRequest(
                                sessionId = sessionId,
                                deviceId = deviceId,
                                batteryPct = battery,
                                appVersion = BuildConfig.VERSION_NAME,
                            )
                        )
                    }
                    Log.d(TAG, "Heartbeat OK (battery=${battery}%)")
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed: ${e.message} — queueing for replay")
                    // Persist the heartbeat snapshot so a tunnel /
                    // building dead-zone doesn't lose battery + uptime
                    // visibility for that window. The sync loop +
                    // network-callback replay it via the same flush
                    // path used for tamper events; the server then has
                    // a complete heartbeat history with no gaps.
                    runCatching {
                        GeoTrackEventQueue.enqueue(
                            context = this@GeoTrackService,
                            eventType = GeoTrackEventQueue.HEARTBEAT_EVENT_TYPE,
                            metadata = buildMap {
                                if (sessionId != null) put("sessionId", sessionId)
                                if (deviceId != null) put("deviceId", deviceId)
                                if (battery != null) put("batteryPct", battery)
                                put("appVersion", BuildConfig.VERSION_NAME)
                            },
                        )
                    }
                }
            }
        }
    }

    // ── DB Cleanup Loop ──

    private fun startCleanupLoop() {
        cleanupJob = serviceScope.launch {
            delay(60_000) // Wait 1 min after start
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    // Sent points: standard 7-day retention (the server
                    // already has these — no point keeping them locally).
                    db.locationPointDao().deleteSentOlderThan(now - MAX_POINT_AGE_MS)
                    // Unsent points: keep MUCH longer (30 days) so a
                    // multi-day offline period — staff on a field trip
                    // with no signal — doesn't lose the journey before
                    // the device reconnects. The 30d safety cap stops
                    // genuinely abandoned local DBs from growing
                    // unbounded if the device is never recovered.
                    db.locationPointDao().deleteUnsentOlderThan(now - MAX_UNSENT_POINT_AGE_MS)
                    db.pendingGeoTrackEventDao().deleteOlderThan(now - MAX_POINT_AGE_MS)
                    Log.d(TAG, "Cleanup: purged sent>${MAX_POINT_AGE_MS / 86_400_000L}d, unsent>${MAX_UNSENT_POINT_AGE_MS / 86_400_000L}d")
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup failed: ${e.message}")
                }
                delay(6 * 60 * 60 * 1000) // Every 6 hours
            }
        }
    }

    // ── Notification ──
    //
    // The foreground notification now shows a single static, user-facing line
    // (TRACKING_NOTIF_TEXT) set when we enter the foreground state. There is no
    // periodic update loop: it used to rewrite the notification every minute
    // with internal capture/sync/battery counters, which both leaked debug
    // detail to staff and woke the CPU + hit the DB on every tick.

    // ── Tamper ──

    @SuppressLint("MissingPermission")
    private suspend fun reportTamper(eventType: String) {
        try {
            val throttled = eventType == "GPS_DISABLED" ||
                eventType == "AIRPLANE_MODE_ON" ||
                eventType == "GPS_ENABLED" ||
                eventType == "AIRPLANE_MODE_OFF"
            val queued = if (throttled) {
                GeoTrackEventQueue.enqueueDistinct(
                    this,
                    eventType,
                    buildEventMetadata(),
                    signature = "tamper_$eventType",
                    minIntervalMs = 6 * 60 * 60 * 1000L,
                )
            } else {
                GeoTrackEventQueue.enqueue(this, eventType, buildEventMetadata())
                true
            }
            Log.i(TAG, "Tamper queued=$queued: $eventType")
            if (queued && hasNetwork()) syncEvents()
        } catch (e: Exception) {
            Log.w(TAG, "Tamper queue failed: ${e.message}")
        }
    }

    private suspend fun queuePermissionHealthEvents(hasFine: Boolean, hasBackground: Boolean) {
        val missing = mutableListOf<String>()
        if (!hasFine) missing.add("fine_location")
        if (!hasBackground) missing.add("background_location")
        if (!hasActivityRecognitionPermission()) missing.add("activity_recognition")
        if (!isIgnoringBatteryOptimizations()) missing.add("battery_optimization")
        if (missing.isEmpty()) return

        val missingKey = missing.sorted().joinToString("_")
        val queued = GeoTrackEventQueue.enqueueDistinct(
            this,
            "PERMISSION_MISSING",
            buildEventMetadata() + ("missingPermissions" to missing.joinToString(",")),
            signature = "permission_missing_$missingKey",
        )
        if (queued && hasNetwork()) syncEvents()
    }

    private suspend fun buildEventMetadata(): Map<String, Any?> {
        val metadata = mutableMapOf<String, Any?>(
            "batteryPct" to getBatteryLevel(),
            "networkType" to getNetworkType(),
            "gpsEnabled" to isGpsEnabled(),
            "airplaneMode" to isAirplaneModeOn(),
            "backgroundLocationPermission" to hasBackgroundLocationPermission(),
            "activityRecognitionPermission" to hasActivityRecognitionPermission(),
            "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations(),
        )
        try {
            val loc = suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc, null) }
                    .addOnFailureListener { cont.resume(null, null) }
            }
            if (loc != null) {
                metadata["lat"] = loc.latitude
                metadata["lng"] = loc.longitude
                metadata["accuracy"] = loc.accuracy
            }
        } catch (_: Exception) {}
        return metadata
    }

    private suspend fun markNetworkOfflineIfNeeded() {
        val now = System.currentTimeMillis()
        if (!offlineStartedAt.compareAndSet(0L, now)) return
        GeoTrackEventQueue.enqueueDistinct(
            this,
            "NETWORK_OFFLINE",
            mapOf(
                "batteryPct" to getBatteryLevel(),
                "gpsEnabled" to isGpsEnabled(),
                "airplaneMode" to isAirplaneModeOn(),
            ),
            signature = "network_offline",
            minIntervalMs = 30 * 60 * 1000L,
            occurredAt = now,
        )
    }

    private suspend fun markNetworkOnlineIfNeeded() {
        val startedAt = offlineStartedAt.getAndSet(0L)
        if (startedAt <= 0L) return
        val now = System.currentTimeMillis()
        GeoTrackEventQueue.enqueue(
            this,
            "NETWORK_ONLINE",
            mapOf(
                "offlineStartedAt" to startedAt,
                "offlineEndedAt" to now,
                "offlineDurationMs" to (now - startedAt),
                "batteryPct" to getBatteryLevel(),
                "gpsEnabled" to isGpsEnabled(),
                "airplaneMode" to isAirplaneModeOn(),
            ),
            occurredAt = now,
        )
    }

    // ── Receivers ──

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), RECEIVER_NOT_EXPORTED)
            registerReceiver(gpsReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
            registerReceiver(gpsReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(airplaneReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(gpsReceiver) } catch (_: Exception) {}
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW)
            ch.description = "M-Connect field tracking"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M-Connect Tracking")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tab_home)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── Utils ──

    private fun getBatteryLevel(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    private fun isGpsEnabled(): Boolean =
        (getSystemService(LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun hasBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun hasActivityRecognitionPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun getNetworkType(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "none") ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
