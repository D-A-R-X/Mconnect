package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.manjugroups.m_connect.util.AppSettingsDeepLink
import com.manjugroups.m_connect.util.UnusedAppRestrictions
import java.util.Locale

/**
 * One tap → every tracking permission, one system prompt after another.
 *
 * Staff used to tap each row of the permissions sheet, land in a Settings
 * screen, hunt for the right toggle and come back, six times. This chains the
 * steps instead, using the system's own in-place prompts wherever Android has
 * one:
 *
 * 1. Location, physical activity and notifications — one runtime request, so
 *    the OS shows its dialogs back to back.
 * 2. Device location (GPS) off → the Play services "Turn on location" dialog,
 *    not the Settings page.
 * 3. Background location → the system's own "Allow all the time" page for this
 *    app (Android 11+ shows no dialog for it; this is the closest to one tap).
 * 4. Battery optimisation → the system "Allow / Deny" dialog.
 * 5. "Manage app if unused" → its settings page (no API can toggle it).
 * 6. OEM autostart (Xiaomi, Vivo, Oppo…) → the maker's page, once.
 *
 * Each step is skipped when it is already satisfied, not supported by the
 * phone, or cannot be opened, so the chain never stalls. Whatever is still
 * missing at the end stays on the sheet as a row the user can fix by hand.
 *
 * Must be constructed while the host is being created (a property
 * initialiser), because it registers activity-result launchers.
 */
class TrackingPermissionSetup(
    caller: ActivityResultCaller,
    private val activity: () -> Activity?,
    private val onProgress: () -> Unit = {},
    private val onFinished: () -> Unit = {},
    /**
     * False for staff who are not geo-tracked. They are walked through only
     * what the app uses for them — precise location while it is open (punch-in)
     * and notifications — and never asked for background location, physical
     * activity, battery exemption, unused-app or autostart, which exist only to
     * keep tracking alive. Asking an untracked user for background location is
     * also what Play's background-location policy forbids.
     */
    private val tracked: () -> Boolean = { true },
) {
    private enum class Step { RUNTIME, DEVICE_LOCATION, BACKGROUND, BATTERY, UNUSED_APP, AUTOSTART, DONE }

    private var step = Step.DONE
    var isRunning = false
        private set

    private val runtimeLauncher = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { advance() }

    private val backgroundLauncher = caller.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { advance() }

    private val resolutionLauncher = caller.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { advance() }

    private val screenLauncher = caller.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { advance() }

    fun start() {
        if (isRunning) return
        isRunning = true
        step = Step.RUNTIME
        run()
    }

    private fun advance() {
        if (!isRunning) return
        onProgress()
        step = Step.entries[step.ordinal + 1]
        if (!tracked() && step.ordinal > Step.DEVICE_LOCATION.ordinal) step = Step.DONE
        run()
    }

    private fun finish() {
        isRunning = false
        step = Step.DONE
        onProgress()
        onFinished()
    }

    private fun run() {
        val host = activity()
        if (host == null || host.isFinishing || host.isDestroyed) {
            isRunning = false
            return
        }
        when (step) {
            Step.RUNTIME -> requestRuntime(host)
            Step.DEVICE_LOCATION -> turnOnDeviceLocation(host)
            Step.BACKGROUND -> requestBackground(host)
            Step.BATTERY -> requestBatteryExemption(host)
            Step.UNUSED_APP -> openUnusedAppSetting(host)
            Step.AUTOSTART -> openAutostart(host)
            Step.DONE -> finish()
        }
    }

    private fun granted(host: Activity, permission: String) =
        ContextCompat.checkSelfPermission(host, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntime(host: Activity) {
        val wanted = buildList {
            if (!granted(host, Manifest.permission.ACCESS_FINE_LOCATION)) {
                // FINE with COARSE, so a coarse-only grant gets the OS
                // "precise" upgrade prompt.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (tracked() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !granted(host, Manifest.permission.ACTIVITY_RECOGNITION)
            ) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            // The tracking notification and the permission alert need it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(host, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (wanted.isEmpty()) return advance()
        launchOrSkip { runtimeLauncher.launch(wanted.toTypedArray()) }
    }

    private fun turnOnDeviceLocation(host: Activity) {
        if (BackgroundPermissionsGateDialog.isDeviceLocationEnabled(host)) return advance()
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build(),
            )
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(host)
            .checkLocationSettings(request)
            .addOnSuccessListener { advance() }
            .addOnFailureListener { error ->
                val resolvable = error as? ResolvableApiException
                if (resolvable == null) {
                    // No in-place dialog on this phone: its Settings page.
                    launchScreenOrSkip(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    launchOrSkip {
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(resolvable.resolution).build(),
                        )
                    }
                }
            }
    }

    private fun requestBackground(host: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            BackgroundPermissionsGateDialog.hasBackgroundLocation(host) ||
            // Android only offers "all the time" on top of a location grant.
            !BackgroundPermissionsGateDialog.hasForegroundLocation(host)
        ) {
            return advance()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(
                host,
                "Choose \"Allow all the time\", then go back",
                Toast.LENGTH_LONG,
            ).show()
        }
        launchOrSkip { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    }

    private fun requestBatteryExemption(host: Activity) {
        if (BackgroundPermissionsGateDialog.hasBatteryOptIgnored(host)) return advance()
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${host.packageName}"))
        if (runCatching { screenLauncher.launch(direct) }.isSuccess) return
        // No Allow/Deny dialog on this phone: this app's Battery row, not the
        // phone-wide list of every app.
        Toast.makeText(host, "Tap Battery → Unrestricted, then go back", Toast.LENGTH_LONG).show()
        launchScreenOrSkip(AppSettingsDeepLink.battery(host))
    }

    private fun openUnusedAppSetting(host: Activity) {
        val intent = UnusedAppRestrictions.settingsIntent(host) ?: return advance()
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(host)
        future.addListener(
            {
                if (!isRunning) return@addListener
                val restrictionOn = when (runCatching { future.get() }.getOrNull()) {
                    UnusedAppRestrictionsConstants.API_30_BACKPORT,
                    UnusedAppRestrictionsConstants.API_30,
                    UnusedAppRestrictionsConstants.API_31 -> true
                    else -> false
                }
                if (!restrictionOn) {
                    advance()
                } else {
                    Toast.makeText(
                        host,
                        "Turn OFF \"Pause app activity if unused\", then go back",
                        Toast.LENGTH_LONG,
                    ).show()
                    launchScreenOrSkip(intent)
                }
            },
            ContextCompat.getMainExecutor(host),
        )
    }

    private fun openAutostart(host: Activity) {
        // No OS API reports this, so it is opened once; the sheet's row stays
        // for anyone who needs to go back to it.
        if (!OemAutostart.isManaged() || OemAutostart.isMarkedEnabled(host)) return advance()
        OemAutostart.markEnabled(host, true)
        // Maker pages list every app; there is no way to pre-select this one.
        for (intent in OemAutostart.candidateIntents()) {
            if (runCatching { screenLauncher.launch(intent) }.isSuccess) {
                Toast.makeText(host, "Allow Mconnect to auto-start, then go back", Toast.LENGTH_LONG).show()
                return
            }
        }
        advance()
    }

    private fun launchScreenOrSkip(intent: Intent) = launchOrSkip { screenLauncher.launch(intent) }

    /** A screen this phone does not have must not stop the chain. */
    private inline fun launchOrSkip(block: () -> Unit) {
        if (runCatching(block).isFailure) advance()
    }
}

/** OEM "auto-start" pages. Shared by the one-tap chain and the sheet row. */
object OemAutostart {
    private const val PREFS = "permissions_gate"
    private const val KEY = "autostart_enabled"

    private val brand get() = Build.MANUFACTURER.lowercase(Locale.US)

    fun isManaged(): Boolean = listOf(
        "xiaomi", "redmi", "poco", "vivo", "oppo", "realme", "huawei", "honor", "oneplus",
    ).any { brand.contains(it) }

    fun isMarkedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    fun candidateIntents(): List<Intent> {
        val b = brand
        val candidates = when {
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.appmanager.ApplicationsDetailsActivity",
            )
            b.contains("vivo") -> listOf(
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            b.contains("oppo") || b.contains("realme") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            b.contains("huawei") || b.contains("honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )
            b.contains("samsung") -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            b.contains("oneplus") -> listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            else -> emptyList()
        }
        return candidates.map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
    }
}
