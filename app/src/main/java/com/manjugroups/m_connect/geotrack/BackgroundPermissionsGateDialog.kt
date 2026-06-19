package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R
import java.util.Locale

/**
 * Non-dismissible gate dialog — premium 3D-illustrated UI that blocks
 * the app until the required background permissions are in place.
 *
 * Auto-dismisses the moment all checks pass (via onResume + delayed
 * re-checks for OEM skins that propagate state asynchronously).
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

        fun showIfNeeded(fm: FragmentManager, ctx: android.content.Context) {
            if (allGranted(ctx)) return
            if (fm.findFragmentByTag(TAG) != null) return
            BackgroundPermissionsGateDialog().show(fm, TAG)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────

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
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.CENTER)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Click the whole row OR the badge to open the relevant settings.
        view.findViewById<View>(R.id.rowDeviceLocation).setOnClickListener {
            openDeviceLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixDeviceLocation).setOnClickListener {
            openDeviceLocationSettings()
        }

        view.findViewById<View>(R.id.rowBgLocation).setOnClickListener {
            openBackgroundLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixBgLocation).setOnClickListener {
            openBackgroundLocationSettings()
        }

        view.findViewById<View>(R.id.rowBatteryOpt).setOnClickListener {
            openBatteryOptimizationSettings()
        }
        view.findViewById<View>(R.id.btnFixBatteryOpt).setOnClickListener {
            openBatteryOptimizationSettings()
        }

        view.findViewById<View>(R.id.rowAutostart).setOnClickListener {
            openOemAutostartSettings()
        }
        view.findViewById<View>(R.id.btnFixAutostart).setOnClickListener {
            openOemAutostartSettings()
        }

        // Hidden Continue — auto-dismiss handles closing.
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
                openAppDetailsSettings()
            }
            recheckAndMaybeDismiss()
        }
    }

    // ── Internal ──────────────────────────────────────────────────

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
            !deviceLocationOk -> "Turn on Location/GPS in phone settings to continue."
            !fgOk -> "Tap Location Access and select \"Allow all the time\" for Mconnect."
            !bgOk && !batOk -> "Enable background location and unrestricted battery use."
            !bgOk -> "Set Location to \"Allow all the time\", then come back."
            !batOk -> "Set Battery to Unrestricted, then come back."
            else -> "All set — closing."
        }
    }

    /**
     * Styles each badge between "Enable" (outline, no checkmark) and
     * "✓ Enabled" (outline + green check circle) states.
     */
    private fun refreshStatus(root: View) {
        val ctx = requireContext()
        val deviceLocationOk = isDeviceLocationEnabled(ctx)
        val fgOk = hasForegroundLocation(ctx)
        val bgOk = hasBackgroundLocation(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        val needsAutostart = isAutostartManaged()

        // Each row's badge is a LinearLayout containing [checkIcon, text].
        fun styleBadge(
            badge: LinearLayout,
            checkIcon: ImageView,
            label: TextView,
            granted: Boolean,
        ) {
            if (granted) {
                label.text = "Enabled"
                checkIcon.visibility = View.VISIBLE
                badge.setBackgroundResource(R.drawable.bg_gate_badge_enabled)
                label.setTextColor(Color.parseColor("#10B981"))
            } else {
                label.text = "Enable"
                checkIcon.visibility = View.GONE
                badge.setBackgroundResource(R.drawable.bg_gate_btn_enable)
                label.setTextColor(Color.parseColor("#10B981"))
            }
        }

        styleBadge(
            root.findViewById(R.id.badgeDeviceLocation),
            root.findViewById(R.id.iconDeviceLocationCheck),
            root.findViewById(R.id.btnFixDeviceLocation),
            deviceLocationOk,
        )

        val bgGranted = if (!fgOk) false else bgOk
        styleBadge(
            root.findViewById(R.id.badgeBgLocation),
            root.findViewById(R.id.iconBgLocationCheck),
            root.findViewById(R.id.btnFixBgLocation),
            bgGranted,
        )

        styleBadge(
            root.findViewById(R.id.badgeBatteryOpt),
            root.findViewById(R.id.iconBatteryOptCheck),
            root.findViewById(R.id.btnFixBatteryOpt),
            batOk,
        )

        val autoRow = root.findViewById<View>(R.id.rowAutostart)
        if (needsAutostart) {
            autoRow.visibility = View.VISIBLE
            styleBadge(
                root.findViewById(R.id.badgeAutostart),
                root.findViewById(R.id.iconAutostartCheck),
                root.findViewById(R.id.btnFixAutostart),
                false, // can't programmatically check autostart
            )
        } else {
            autoRow.visibility = View.GONE
        }
    }

    // ── Settings launchers ──────────────────────────────────────

    private fun openDeviceLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure { openAppDetailsSettings() }
    }

    /**
     * On Android 11+ (R+), [requestPermissions] for
     * ACCESS_BACKGROUND_LOCATION opens the system's per-app location
     * permission screen with "Allow all the time". On Q, shows a dialog.
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
}
