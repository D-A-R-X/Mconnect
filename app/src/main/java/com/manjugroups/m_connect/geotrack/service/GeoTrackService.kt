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
import com.manjugroups.m_connect.geotrack.GeoTrackFlushWorker
import com.manjugroups.m_connect.geotrack.GeoTrackPointFlusher
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
        // Idle-shift power saving: when clocked in but physically STILL with no
        // active trip/visit, relax GPS to balanced/long-interval so the chip
        // idles, and sync less often. Snaps back to fast/precise the moment a
        // field activity starts or motion resumes.
        private const val IDLE_LOCATION_INTERVAL_MS = 150_000L // 2.5 min
        private const val IDLE_SYNC_INTERVAL_MS = 90_000L
        private const val RECENT_MOTION_MS = 120_000L // keep precise 2 min after motion
        // Short, self-timing wakelock taken ONLY around a network sync burst
        // (not for the whole shift) so an upload can finish even if the device
        // is dozing. The OS auto-releases after this timeout as a safety net.
        private const val SYNC_WAKELOCK_MS = 15_000L
        // The persistent foreground notification is mandatory for a location
        // service. Its content is built by TrackingNotification and adapts to
        // the staff's field activity (On Duty / CP / SV / Fleet); it never
        // shows internal capture/sync/battery counters.
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
            // Shift ended → drop any lingering field-activity so the next
            // shift's notification starts neutral (no stale cross-day timer).
            SessionManager(context.applicationContext).clearFieldActivity()
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
    // Current fused GPS power mode. false = HIGH_ACCURACY (default/precise),
    // true = relaxed idle mode. Only ever true during an idle+still shift.
    @Volatile private var lowPowerLocationMode = false
    // Wall-clock of the last fix that showed real movement. Keeps precise mode
    // "sticky" for RECENT_MOTION_MS so we don't flap modes at stoplights.
    @Volatile private var lastMovingAtMs = 0L

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

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.i(TAG, "Device shutdown broadcast: ${intent?.action}")
            // The process is about to die, so we can't rely on the coroutine
            // scope surviving — persist the event SYNCHRONOUSLY to Room (a fast
            // local insert). It replays to the server on the next boot/connect
            // via GeoTrackEventQueue, and DEVICE_SHUTDOWN is in the backend's
            // always-notify-on-replay set so the RO is still alerted.
            try {
                kotlinx.coroutines.runBlocking {
                    GeoTrackEventQueue.enqueue(
                        this@GeoTrackService,
                        "DEVICE_SHUTDOWN",
                        buildEventMetadataQuick(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shutdown enqueue failed: ${e.message}")
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
            // Context-adaptive (On Duty / CP / SV / Fleet) — see TrackingNotification.
            val notification = TrackingNotification.build(this)
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
            // Anything still unsent (offline at clock-out, mid-teardown
            // failure) is handed to WorkManager: it survives this process
            // and flushes the tail the moment connectivity returns, so the
            // web timeline backfills without waiting for the next clock-in.
            try {
                val leftoverPoints = db.locationPointDao().getUnsentCount()
                val leftoverEvents = db.pendingGeoTrackEventDao().getPendingCount()
                if (leftoverPoints > 0 || leftoverEvents > 0) {
                    Log.i(TAG, "Teardown leftovers: $leftoverPoints points, $leftoverEvents events — scheduling flush worker")
                    GeoTrackFlushWorker.enqueue(this@GeoTrackService)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Leftover check failed: ${e.message}")
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
            // Start precise; reevaluateLocationMode() relaxes it later if the
            // shift goes idle + still.
            lowPowerLocationMode = false
            val req = buildFusedRequest(lowPower = false)

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

    /**
     * The fused location request for the current power mode.
     *  - precise (default): HIGH_ACCURACY @ 10s — full path fidelity.
     *  - low power (idle+still): BALANCED @ 2.5 min + 40 m min-distance so the
     *    GPS chip idles; a cheap wifi/cell fix still arrives occasionally and,
     *    the instant it shows motion, we snap back to precise.
     */
    private fun buildFusedRequest(lowPower: Boolean): LocationRequest {
        return if (lowPower) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, IDLE_LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(60_000)
                .setMinUpdateDistanceMeters(40f)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(5_000)
                .setWaitForAccurateLocation(false)
                .build()
        }
    }

    /**
     * Decide whether GPS should idle and re-register the fused request if the
     * mode changed. SAFE BY DESIGN: precise is forced whenever a field activity
     * (On Duty / CP / SV / Fleet) is running OR the staff is moving, so every
     * tracked trip keeps full fidelity. Low power engages only during an idle
     * shift while physically still — where accuracy doesn't matter and the old
     * always-HIGH_ACCURACY request was burning battery. Only touches the fused
     * stack; the LocationManager fallback is left alone.
     */
    @SuppressLint("MissingPermission")
    private fun reevaluateLocationMode() {
        if (!fusedLocationRegistered) return
        val hasFieldActivity = session.fieldActivity() != null
        val moving = (lastActivity != "STILL" && lastActivity != "UNKNOWN") ||
            (System.currentTimeMillis() - lastMovingAtMs < RECENT_MOTION_MS)
        val desiredLowPower = !hasFieldActivity && !moving
        if (desiredLowPower == lowPowerLocationMode) return
        val looper = locationThread?.looper ?: return
        try {
            fusedClient.requestLocationUpdates(buildFusedRequest(desiredLowPower), locationCallback, looper)
            lowPowerLocationMode = desiredLowPower
            Log.i(TAG, "Location mode → ${if (desiredLowPower) "LOW_POWER (idle+still)" else "HIGH_ACCURACY"}")
        } catch (e: Exception) {
            Log.w(TAG, "Re-register location failed: ${e.message}")
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
                    val battery = getBatteryLevel()
                    val tickAt = System.currentTimeMillis()
                    try {
                        api.heartbeat(
                            session.bearerToken,
                            HeartbeatRequest(
                                sessionId = session.activeTrackingSessionId,
                                deviceId = session.trackingDeviceId,
                                batteryPct = battery,
                                appVersion = BuildConfig.VERSION_NAME,
                                recordedAt = tickAt,
                            )
                        )
                        Log.i(TAG, "Initial heartbeat sent")
                    } catch (e: Exception) {
                        // Queue for replay like the periodic loop — the shift's
                        // very first battery/uptime tick must not vanish just
                        // because clock-in happened in a dead zone.
                        Log.w(TAG, "Initial heartbeat failed: ${e.message} — queueing for replay")
                        runCatching {
                            GeoTrackEventQueue.enqueue(
                                context = this@GeoTrackService,
                                eventType = GeoTrackEventQueue.HEARTBEAT_EVENT_TYPE,
                                metadata = buildMap {
                                    session.activeTrackingSessionId?.let { put("sessionId", it) }
                                    session.trackingDeviceId?.let { put("deviceId", it) }
                                    if (battery != null) put("batteryPct", battery)
                                    put("appVersion", BuildConfig.VERSION_NAME)
                                },
                                occurredAt = tickAt,
                            )
                        }
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

        // Motion detector for the power mode, independent of the storage dedup
        // below (which may early-return). A moving fix snaps GPS back to precise
        // immediately if we'd relaxed it.
        if (location.speed >= 1.5f) {
            lastMovingAtMs = now
            if (lowPowerLocationMode) reevaluateLocationMode()
        }

        // ── Smart dedup: different rules for moving vs stationary ──
        // An activity transition (still→vehicle, vehicle→still, …) forces the
        // next fix through the dedup so the mode change is visible on the
        // timeline even when the device hasn't moved far enough to store.
        val forceForTransition = ActivityRecognitionReceiver.consumeTransitionPending()
        if (lastStoredLat != 0.0 && !forceForTransition) {
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
            recordedAt = System.currentTimeMillis(),
            sessionId = session.activeTrackingSessionId,
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
                try {
                    // Cheap re-check (a pref read + flag compare) each cycle: relax
                    // or restore the GPS request as the shift goes idle/active.
                    reevaluateLocationMode()
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let an unexpected error kill the loop — local buffering
                    // keeps running and we retry next cycle. Store-and-forward must
                    // survive ANY single-cycle failure (permission, network, DB…);
                    // the data stays queued locally until a cycle succeeds.
                    consecutiveSyncFailures++
                    Log.e(TAG, "Sync cycle error (continuing): ${e.message}", e)
                }
                // Adaptive delay: back off hard while sync is failing, ease off
                // during an idle+still shift, otherwise the normal cadence.
                val delay = when {
                    consecutiveSyncFailures > 0 -> {
                        val backoff = SYNC_INTERVAL_MS * minOf(consecutiveSyncFailures, MAX_SYNC_RETRIES)
                        Log.d(TAG, "Sync backoff: ${backoff}ms (failures=$consecutiveSyncFailures)")
                        backoff
                    }
                    lowPowerLocationMode -> IDLE_SYNC_INTERVAL_MS
                    else -> SYNC_INTERVAL_MS
                }
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
        val result = GeoTrackPointFlusher.flush(
            context = this,
            api = api,
            session = session,
            maxBatches = MAX_BATCHES_PER_SYNC,
        )
        if (result.flushedPoints > 0) {
            pointsSynced.addAndGet(result.flushedPoints)
            Log.i(TAG, "Sync OK: ${result.flushedPoints} points across ${result.batches} batches (total saved: ${pointsSynced.get()})")
        }
        if (result.hadFailure) {
            consecutiveSyncFailures++
            Log.e(TAG, "Sync FAILED: ${result.lastError} (failures=$consecutiveSyncFailures)")
        } else if (result.flushedPoints > 0 || result.batches > 0) {
            consecutiveSyncFailures = 0
        }
        // The ACTIVE session id being unknown to the server means tracking is
        // genuinely broken (deleted doc / foreign deployment) — stop, exactly
        // like the old behaviour. Stale-session batches failing must NOT kill
        // live tracking; the flusher keeps them buffered and moves on.
        if (result.activeSessionInvalid) {
            Log.e(TAG, "Active tracking session rejected by server — stopping")
            session.shouldTrackNow = false
            session.activeTrackingSessionId = null
            stopSelf()
        }
    }

    // ── Heartbeat ──

    private fun startHeartbeatLoop() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                // Self-heal the ongoing permission alert every tick: clears it
                // the moment the staff grants the last missing permission, and
                // re-posts it if the OS dropped it (Android 14+ can).
                runCatching { refreshPermissionNotification() }
                val battery = getBatteryLevel()
                val sessionId = session.activeTrackingSessionId
                val deviceId = session.trackingDeviceId
                val tickAt = System.currentTimeMillis()
                try {
                    runWithSyncWakeLock {
                        api.heartbeat(
                            session.bearerToken,
                            HeartbeatRequest(
                                sessionId = sessionId,
                                deviceId = deviceId,
                                batteryPct = battery,
                                appVersion = BuildConfig.VERSION_NAME,
                                recordedAt = tickAt,
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
                            occurredAt = tickAt,
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
                    // Pending events (queued heartbeats/tamper) get the same
                    // 30-day retention as unsent points — they're the battery
                    // and uptime history of the exact same offline window, so
                    // purging them at 7 days lost part of the journey record.
                    db.pendingGeoTrackEventDao().deleteOlderThan(now - MAX_UNSENT_POINT_AGE_MS)
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
    // Built by TrackingNotification and adapts to the staff's current field
    // activity. There is still NO periodic update loop (the old per-minute
    // rewrite woke the CPU + hit the DB every tick): the content is refreshed
    // event-driven — a flow calls TrackingNotification.refresh() when the
    // activity changes — and the live elapsed timer is rendered by the OS
    // chronometer, so it ticks without any wakeups from us.

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
                    // Report IMMEDIATELY. The 15s window only collapses the
                    // duplicate system broadcasts a single toggle emits (Android
                    // fires PROVIDERS_CHANGED / AIRPLANE_MODE_CHANGED more than
                    // once) — the FIRST event still fires + syncs instantly, and
                    // a real repeat later in the shift is NOT suppressed. (Was
                    // 6h, which is why the RO wasn't being told.)
                    minIntervalMs = 15 * 1000L,
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

        // Immediate, offline-capable heads-up to the staff so they can fix it
        // and resume normal signal transfer. Posts the ongoing red alert for
        // the full missing set (same set the in-app gate enforces) so the
        // notification and the gate agree and clear together.
        com.manjugroups.m_connect.notifications.PermissionAlertNotification.update(this, missing)
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

    /**
     * Post-or-clear ONLY the ongoing permission alert, cheaply and idempotently
     * (a few permission reads + one notify/cancel; no DB, no network, no event
     * enqueue). Called each heartbeat so the red alert self-heals: it comes
     * back if the OS drops it and, crucially, disappears the moment the staff
     * grants the last missing permission mid-shift.
     */
    private fun refreshPermissionNotification() {
        val missing = mutableListOf<String>()
        val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) missing.add("fine_location")
        if (!hasBackgroundLocationPermission()) missing.add("background_location")
        if (!hasActivityRecognitionPermission()) missing.add("activity_recognition")
        if (!isIgnoringBatteryOptimizations()) missing.add("battery_optimization")
        com.manjugroups.m_connect.notifications.PermissionAlertNotification.update(this, missing)
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

    /**
     * Synchronous variant of buildEventMetadata for events that must be
     * captured without awaiting anything (e.g. DEVICE_SHUTDOWN, where the
     * process is being torn down). Skips the fused last-location await; keeps
     * the battery/network/gps/airplane snapshot.
     */
    private fun buildEventMetadataQuick(): Map<String, Any?> = mapOf(
        "batteryPct" to getBatteryLevel(),
        "networkType" to getNetworkType(),
        "gpsEnabled" to isGpsEnabled(),
        "airplaneMode" to isAirplaneModeOn(),
        "backgroundLocationPermission" to hasBackgroundLocationPermission(),
        "activityRecognitionPermission" to hasActivityRecognitionPermission(),
        "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations(),
    )

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
        // Device power-off: ACTION_SHUTDOWN is the standard signal; the
        // QUICKBOOT_POWEROFF actions cover OEM "fast shutdown" paths (HTC/
        // Samsung/others) that skip ACTION_SHUTDOWN. These are protected
        // system broadcasts, so a dynamically-registered NOT_EXPORTED receiver
        // is the only reliable way to catch them (manifest receivers are not
        // delivered for shutdown).
        val shutdownFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), RECEIVER_NOT_EXPORTED)
            registerReceiver(gpsReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), RECEIVER_NOT_EXPORTED)
            registerReceiver(shutdownReceiver, shutdownFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
            registerReceiver(gpsReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
            registerReceiver(shutdownReceiver, shutdownFilter)
        }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(airplaneReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(gpsReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(shutdownReceiver) } catch (_: Exception) {}
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
