package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R
import java.util.Locale

/**
 * Non-dismissible gate that blocks the app until the two background
 * permissions GeoTrack absolutely needs are in place:
 *
 *   1. ACCESS_BACKGROUND_LOCATION granted (Q+; on older Android this
 *      always reads true).
 *   2. Battery optimization ignored for our package — without this the
 *      OS suspends our foreground service inside doze, GPS pings dry up,
 *      and the day's attendance is full of holes.
 *
 * Behaviour:
 *  • Back button + outside touches do nothing.
 *  • Each row shows an "Enable" button that launches the system settings
 *    page for that specific toggle. Returning from settings triggers
 *    onResume → re-check → auto-dismiss if both are now satisfied.
 *  • Owner must invoke [show] every onResume so the dialog re-asserts
 *    itself when the user toggles a setting OFF and comes back.
 *
 * Call sites should only invoke this for staff who actually need
 * background tracking (e.g. `session.geoTrackingEnabled`). Office
 * staff who never get tracked shouldn't be force-prompted.
 */
class BackgroundPermissionsGateDialog : DialogFragment() {

    companion object {
        private const val TAG = "BackgroundPermissionsGateDialog"
        private const val REQUEST_BG_LOCATION = 1001

        fun hasBackgroundLocation(ctx: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Foreground (fine OR coarse) location. If the user denied this
         * at the initial OS prompt, the Settings page won't even show
         * an "Allow all the time" option, so it's the first thing the
         * gate has to surface.
         */
        fun hasForegroundLocation(ctx: android.content.Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        fun hasBatteryOptIgnored(ctx: android.content.Context): Boolean {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        fun isDeviceLocationEnabled(ctx: android.content.Context): Boolean {
            val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }

        fun allGranted(ctx: android.content.Context): Boolean =
            isDeviceLocationEnabled(ctx) &&
                hasForegroundLocation(ctx) &&
                hasBackgroundLocation(ctx) &&
                hasBatteryOptIgnored(ctx)

        /**
         * Show the gate if anything is missing, no-op otherwise. Safe to
         * call from every onResume — DialogFragment de-duplicates the
         * shown instance via its tag.
         */
        fun showIfNeeded(fm: FragmentManager, ctx: android.content.Context) {
            if (allGranted(ctx)) return
            if (fm.findFragmentByTag(TAG) != null) return
            BackgroundPermissionsGateDialog().show(fm, TAG)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        isCancelable = false
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_background_permissions_gate, container, false)

    override fun onStart() {
        super.onStart()
        // Transparent window background so rounded corners show.
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Compact centered dialog — 88% screen width, vertically centered.
        val width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.CENTER)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnFixDeviceLocation).setOnClickListener {
            openDeviceLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixBgLocation).setOnClickListener {
            openBackgroundLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixBatteryOpt).setOnClickListener {
            openBatteryOptimizationSettings()
        }
        view.findViewById<View>(R.id.btnFixAutostart).setOnClickListener {
            openOemAutostartSettings()
        }
        // Hidden continue button — auto-dismiss handles everything, but
        // we wire up a re-check in case the hidden view ever gets tapped.
        view.findViewById<View>(R.id.btnGateContinue).setOnClickListener {
            val ctx = requireContext()
            if (allGranted(ctx)) {
                dismissAllowingStateLoss()
            } else {
                Toast.makeText(ctx, missingReasonMessage(ctx), Toast.LENGTH_LONG).show()
                refreshStatus(view)
            }
        }
        refreshStatus(view)
    }

    override fun onResume() {
        super.onResume()
        recheckAndMaybeDismiss()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ recheckAndMaybeDismiss() }, 500)
        handler.postDelayed({ recheckAndMaybeDismiss() }, 1500)
    }

    /**
     * Handles the result of [requestPermissions] for background
     * location. If the user denied via the system page (or "Don't
     * ask again"), we fall back to opening app details settings so
     * they can manually navigate to Permissions → Location.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BG_LOCATION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] != PackageManager.PERMISSION_GRANTED
            ) {
                // System dialog was suppressed ("Don't ask again") or
                // the user came back without granting — fall back to
                // app details settings as a last resort.
                openAppDetailsSettings()
            }
            recheckAndMaybeDismiss()
        }
    }

    private fun recheckAndMaybeDismiss() {
        if (!isAdded || isDetached) return
        val ctx = context ?: return
        val root = view ?: return
        if (allGranted(ctx)) {
            dismissAllowingStateLoss()
        } else {
            refreshStatus(root)
        }
    }

    private fun missingReasonMessage(ctx: android.content.Context): String {
        val deviceLocationOk = isDeviceLocationEnabled(ctx)
        val fgOk = hasForegroundLocation(ctx)
        val bgOk = hasBackgroundLocation(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        return when {
            !deviceLocationOk ->
                "Turn on Location/GPS in phone settings to continue."
            !fgOk ->
                "Open the location toggle and select \"Allow all the time\" for Mconnect."
            !bgOk && !batOk ->
                "Enable background location (Allow all the time) and unrestricted battery use to continue."
            !bgOk ->
                "Set Location to Allow all the time, then tap Enable."
            !batOk ->
                "Set Battery to Unrestricted, then tap Enable. If you already did, force-close Settings and try again."
            else -> "All set — closing."
        }
    }

    /**
     * Styles each permission row's "Enable" / "✓ Enabled" pill button.
     */
    private fun refreshStatus(root: View) {
        val ctx = requireContext()
        val deviceLocationOk = isDeviceLocationEnabled(ctx)
        val fgOk = hasForegroundLocation(ctx)
        val bgOk = hasBackgroundLocation(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        val needsAutostart = isAutostartManaged()

        val deviceLocBtn = root.findViewById<TextView>(R.id.btnFixDeviceLocation)
        val locBtn = root.findViewById<TextView>(R.id.btnFixBgLocation)
        val batBtn = root.findViewById<TextView>(R.id.btnFixBatteryOpt)
        val autoRow = root.findViewById<View>(R.id.rowAutostart)
        val autoBtn = root.findViewById<TextView>(R.id.btnFixAutostart)

        fun stylePill(btn: TextView, granted: Boolean) {
            if (granted) {
                btn.text = "✓ Enabled"
                btn.setBackgroundResource(R.drawable.bg_gate_btn_enabled)
                btn.setTextColor(android.graphics.Color.parseColor("#10B981"))
                btn.isEnabled = false
                btn.alpha = 1f
            } else {
                btn.text = "Enable"
                btn.setBackgroundResource(R.drawable.bg_gate_btn_enable)
                btn.setTextColor(android.graphics.Color.parseColor("#10B981"))
                btn.isEnabled = true
                btn.alpha = 1f
            }
        }

        stylePill(deviceLocBtn, deviceLocationOk)

        if (!fgOk) {
            stylePill(locBtn, false)
        } else {
            stylePill(locBtn, bgOk)
        }

        stylePill(batBtn, batOk)

        if (needsAutostart) {
            autoRow.visibility = View.VISIBLE
            stylePill(autoBtn, false)
        } else {
            autoRow.visibility = View.GONE
        }
    }

    // ── Settings launchers ──────────────────────────────────────────

    private fun openDeviceLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure { openAppDetailsSettings() }
    }

    /**
     * Opens the **location permission page** for this app so the user
     * can select "Allow all the time".
     *
     * On Android 11+ (R+), calling [requestPermissions] with
     * ACCESS_BACKGROUND_LOCATION opens the system's per-app location
     * permission screen directly — the user sees the "Allow all the
     * time" / "Allow only while using" / "Deny" toggles. This is the
     * most direct path to the background-location grant.
     *
     * On Android 10 (Q), [requestPermissions] shows an in-app system
     * dialog with the same choices.
     *
     * Falls back to [openAppDetailsSettings] on pre-Q devices where
     * background location is implicitly granted with foreground.
     */
    private fun openBackgroundLocationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BG_LOCATION,
            )
        } else {
            openAppDetailsSettings()
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
    }

    /**
     * Best-effort launch of the OEM-specific autostart-permission
     * screen. Falls through to app details if every target fails.
     */
    private fun openOemAutostartSettings() {
        val ctx = requireContext()
        val brand = Build.MANUFACTURER.lowercase(Locale.US)
        val candidates = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.appmanager.ApplicationsDetailsActivity",
            )
            brand.contains("vivo") -> listOf(
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            brand.contains("oppo") || brand.contains("realme") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            brand.contains("huawei") || brand.contains("honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )
            brand.contains("samsung") -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            brand.contains("oneplus") -> listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            else -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                ctx.startActivity(intent)
                return
            } catch (_: Exception) {
                // Try next candidate.
            }
        }
        openAppDetailsSettings()
    }

    /** Fallback: opens the App Info page from system settings. */
    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                },
            )
        }
    }

    private fun isAutostartManaged(): Boolean {
        val brand = Build.MANUFACTURER.lowercase(Locale.US)
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            brand.contains("vivo") ||
            brand.contains("oppo") ||
            brand.contains("realme") ||
            brand.contains("huawei") ||
            brand.contains("honor") ||
            brand.contains("oneplus")
    }
}
