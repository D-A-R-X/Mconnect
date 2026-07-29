package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R
import java.util.Locale

/**
 * Non-dismissible gate Bottom Sheet — premium mockup UI with Custom Switches.
 * Auto-dismisses when all mandatory permissions are granted.
 */
class BackgroundPermissionsGateDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "BackgroundPermissionsGateDialog"
        private const val REQUEST_BG_LOCATION = 1001
        private const val REQUEST_FG_LOCATION = 1002
        private const val REQUEST_ACTIVITY_RECOGNITION = 1003

        fun hasBackgroundLocation(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasActivityRecognition(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasForegroundLocation(ctx: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        fun hasBatteryOptIgnored(ctx: Context): Boolean {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        fun isDeviceLocationEnabled(ctx: Context): Boolean {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }

        fun allGranted(ctx: Context): Boolean =
            isDeviceLocationEnabled(ctx) &&
                hasForegroundLocation(ctx) &&
                hasBackgroundLocation(ctx) &&
                hasActivityRecognition(ctx) &&
                hasBatteryOptIgnored(ctx)

        /**
         * The tracking permissions that are missing right now, as the same
         * string keys the ongoing red alert and the synced PERMISSION_MISSING
         * event use. Single source of truth so the in-app gate and the
         * out-of-app notification always agree on "any permission missing".
         * (Device-location-enabled is a GPS toggle, not a permission — it
         * gates the sheet but is surfaced separately as a GPS_DISABLED signal,
         * so it is intentionally not a key here.)
         */
        fun missingPermissionKeys(ctx: Context): List<String> {
            val missing = mutableListOf<String>()
            if (!hasForegroundLocation(ctx)) missing.add("fine_location")
            if (!hasBackgroundLocation(ctx)) missing.add("background_location")
            if (!hasActivityRecognition(ctx)) missing.add("activity_recognition")
            if (!hasBatteryOptIgnored(ctx)) missing.add("battery_optimization")
            return missing
        }

        fun showIfNeeded(fm: FragmentManager, ctx: Context) {
            if (fm.findFragmentByTag(TAG) != null) return
            if (!allGranted(ctx)) {
                BackgroundPermissionsGateDialog().show(fm, TAG)
                return
            }
            // All runtime permissions are on, but "Manage app if unused" is now
            // required too. Resolve it async and still show the gate when the OS
            // would hibernate/revoke the app, so it can't be silently skipped.
            val future = androidx.core.content.PackageManagerCompat
                .getUnusedAppRestrictionsStatus(ctx)
            future.addListener(
                {
                    val restrictionOn = when (runCatching { future.get() }.getOrNull()) {
                        androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT,
                        androidx.core.content.UnusedAppRestrictionsConstants.API_30,
                        androidx.core.content.UnusedAppRestrictionsConstants.API_31 -> true
                        else -> false
                    }
                    if (restrictionOn && fm.findFragmentByTag(TAG) == null) {
                        runCatching { BackgroundPermissionsGateDialog().show(fm, TAG) }
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }
    }

    // "Manage app if unused" must be turned OFF before the gate lets the user
    // through — users were skipping it, leaving the OS free to hibernate the app
    // and revoke tracking permissions. Resolved asynchronously; stays false
    // (blocking) until we confirm the restriction is disabled OR the device has
    // no such setting.
    private var unusedAppSatisfied = false

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        isCancelable = false
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                
                // Force wrap_content on sheet container to prevent stretching to full height
                val lp = it.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.layoutParams = lp
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_background_permissions_gate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Click row elements to trigger relevant permission logic
        view.findViewById<View>(R.id.rowLocation).setOnClickListener {
            val ctx = requireContext()
            val deviceLocationOk = isDeviceLocationEnabled(ctx)
            val fgOk = hasForegroundLocation(ctx)
            if (!deviceLocationOk || !fgOk) {
                openDeviceLocationSettings()
            } else {
                showRevokeToast()
            }
        }

        view.findViewById<View>(R.id.rowBgLocation).setOnClickListener {
            val ctx = requireContext()
            if (!hasBackgroundLocation(ctx)) {
                openBackgroundLocationSettings()
            } else {
                showRevokeToast()
            }
        }

        view.findViewById<View>(R.id.rowActivityRecognition).setOnClickListener {
            val ctx = requireContext()
            if (!hasActivityRecognition(ctx)) {
                openActivityRecognitionSettings()
            } else {
                showRevokeToast()
            }
        }

        view.findViewById<View>(R.id.rowBatteryOpt).setOnClickListener {
            val ctx = requireContext()
            if (!hasBatteryOptIgnored(ctx)) {
                openBatteryOptimizationSettings()
            } else {
                showRevokeToast()
            }
        }

        view.findViewById<View>(R.id.rowAutostart).setOnClickListener {
            val ctx = requireContext()
            val current = isAutostartEnabled(ctx)
            setAutostartEnabled(ctx, !current)
            if (!current && isAutostartManaged()) {
                openOemAutostartSettings()
            }
            refreshStatus(view)
        }

        // "Manage app if unused" (hibernation / auto-revoke) — deep-link to the
        // OS setting so the user turns it OFF, keeping the app alive.
        view.findViewById<View>(R.id.rowManageAppUnused).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val intent = androidx.core.content.IntentCompat
                .createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName)
            runCatching { startActivity(intent) }
        }

        refreshStatus(view)
    }

    /**
     * Resolve the async "unused app restrictions" status and show the row
     * alongside the other permissions WHENEVER the device supports the feature
     * — whether the restriction is still ON (needs turning off) or already OFF.
     * The switch reads ON only once the restriction is disabled on the device
     * (the good state the user is aiming for). The row is hidden only when the
     * OS has no such setting (FEATURE_NOT_AVAILABLE / ERROR), since there would
     * be nothing to toggle. BLOCKING — the gate will not dismiss until the
     * restriction is disabled (or the device has no such setting), so users can
     * no longer skip past it while it is still on.
     */
    private fun refreshUnusedAppRow(root: View) {
        val ctx = context ?: return
        val row = root.findViewById<View>(R.id.rowManageAppUnused)
        val sw = root.findViewById<SwitchCompat>(R.id.switchManageAppUnused)
        val future = androidx.core.content.PackageManagerCompat
            .getUnusedAppRestrictionsStatus(ctx)
        future.addListener(
            {
                if (!isAdded || view == null) return@addListener
                val status = runCatching { future.get() }.getOrNull()
                // available = the device exposes this setting at all.
                // restrictionOn = the OS will hibernate/revoke the app if unused.
                val available: Boolean
                val restrictionOn: Boolean
                when (status) {
                    androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT,
                    androidx.core.content.UnusedAppRestrictionsConstants.API_30,
                    androidx.core.content.UnusedAppRestrictionsConstants.API_31 -> {
                        available = true; restrictionOn = true
                    }
                    androidx.core.content.UnusedAppRestrictionsConstants.DISABLED -> {
                        available = true; restrictionOn = false
                    }
                    else -> { // FEATURE_NOT_AVAILABLE / ERROR
                        available = false; restrictionOn = false
                    }
                }
                row.visibility = if (available) View.VISIBLE else View.GONE
                // ON only when the restriction is disabled in mobile settings.
                sw.isChecked = available && !restrictionOn
                // Satisfied when the device has no such setting, or the user has
                // actually disabled it. Once satisfied (and everything else is
                // granted) close the gate; it's the only async requirement.
                unusedAppSatisfied = !available || !restrictionOn
                if (unusedAppSatisfied && allGranted(ctx)) {
                    com.manjugroups.m_connect.notifications.PermissionAlertNotification.clear(ctx)
                    dismissAllowingStateLoss()
                }
            },
            ContextCompat.getMainExecutor(ctx),
        )
    }

    override fun onResume() {
        super.onResume()
        recheckAndMaybeDismiss()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ recheckAndMaybeDismiss() }, 500)
        handler.postDelayed({ recheckAndMaybeDismiss() }, 1500)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_FG_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        Toast.makeText(context, "Location permission is required. Please enable it in Settings.", Toast.LENGTH_LONG).show()
                        openAppDetailsSettings()
                    }
                }
                recheckAndMaybeDismiss()
            }
            REQUEST_BG_LOCATION -> {
                recheckAndMaybeDismiss()
            }
            REQUEST_ACTIVITY_RECOGNITION -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
                    ) {
                        // Permanently denied → the runtime dialog won't show
                        // again; send them to app settings so they're never
                        // locked on the non-dismissible sheet.
                        Toast.makeText(context, "Physical activity permission is required. Please enable it in Settings.", Toast.LENGTH_LONG).show()
                        openAppDetailsSettings()
                    }
                }
                recheckAndMaybeDismiss()
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun recheckAndMaybeDismiss() {
        if (!isAdded || isDetached) return
        val ctx = context ?: return
        val root = view ?: return
        if (allGranted(ctx) && unusedAppSatisfied) {
            // Everything's on now — take the ongoing red alert down with the
            // sheet so the two never disagree.
            com.manjugroups.m_connect.notifications.PermissionAlertNotification.clear(ctx)
            dismissAllowingStateLoss()
        } else {
            // Keep the out-of-app alert in step with what's still missing.
            com.manjugroups.m_connect.notifications.PermissionAlertNotification
                .update(ctx, missingPermissionKeys(ctx))
            refreshStatus(root)
        }
    }

    private fun showRevokeToast() {
        Toast.makeText(
            context,
            "To revoke this permission, please go to system App Settings.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshStatus(root: View) {
        val ctx = requireContext()
        val deviceLocationOk = isDeviceLocationEnabled(ctx)
        val fgOk = hasForegroundLocation(ctx)
        val bgOk = hasBackgroundLocation(ctx)
        val activityOk = hasActivityRecognition(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        val autostartOk = isAutostartEnabled(ctx)

        root.findViewById<SwitchCompat>(R.id.switchLocation).isChecked = deviceLocationOk && fgOk
        root.findViewById<SwitchCompat>(R.id.switchBgLocation).isChecked = bgOk
        root.findViewById<SwitchCompat>(R.id.switchActivityRecognition).isChecked = activityOk
        root.findViewById<SwitchCompat>(R.id.switchBatteryOpt).isChecked = batOk
        
        val autoRow = root.findViewById<View>(R.id.rowAutostart)
        if (isAutostartManaged()) {
            autoRow.visibility = View.VISIBLE
        } else {
            autoRow.visibility = View.GONE
        }
        root.findViewById<SwitchCompat>(R.id.switchAutostart).isChecked = autostartOk

        refreshUnusedAppRow(root)
    }

    // ── Settings launchers ──────────────────────────────────────

    private fun openDeviceLocationSettings() {
        val ctx = context ?: return
        if (!hasForegroundLocation(ctx)) {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_FG_LOCATION,
            )
        } else if (!isDeviceLocationEnabled(ctx)) {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .onFailure { openAppDetailsSettings() }
        }
    }

    private fun openBackgroundLocationSettings() {
        val ctx = context ?: return
        if (!hasForegroundLocation(ctx)) {
            Toast.makeText(ctx, "Please enable Location Access first.", Toast.LENGTH_LONG).show()
            return
        }
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

    private fun openActivityRecognitionSettings() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                REQUEST_ACTIVITY_RECOGNITION,
            )
        } else {
            // Auto-granted below Q — nothing to request; reflect state.
            recheckAndMaybeDismiss()
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
            } catch (_: Exception) { /* next */ }
        }
        openAppDetailsSettings()
    }

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

    private fun isAutostartEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("permissions_gate", Context.MODE_PRIVATE)
            .getBoolean("autostart_enabled", false)
    }

    private fun setAutostartEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences("permissions_gate", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("autostart_enabled", enabled)
            .apply()
    }
}
