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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        isCancelable = false
        // Soft keyboard isn't relevant here — keep it suppressed so the
        // dialog never shifts when something incidentally focuses.
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
        // Transparent window background so the XML's rounded-corner
        // bg_gate_dialog drawable is visible (otherwise the default
        // dialog theme draws a rectangular white/grey surface behind it).
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnFixBgLocation).setOnClickListener {
            openBackgroundLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixDeviceLocation).setOnClickListener {
            openDeviceLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixBatteryOpt).setOnClickListener {
            openBatteryOptimizationSettings()
        }
        view.findViewById<View>(R.id.btnFixAutostart).setOnClickListener {
            openOemAutostartSettings()
        }
        // Manual Continue button — re-runs the gate check on demand. On
        // some OEM skins (Samsung One UI, Xiaomi MIUI, etc.) the
        // PowerManager.isIgnoringBatteryOptimizations flag updates
        // asynchronously after the user enables "Unrestricted", and
        // onResume can fire before the propagation lands. Giving the
        // user an explicit "Continue" gives them a recovery path
        // instead of being trapped behind a dialog that never closes.
        view.findViewById<View>(R.id.btnGateContinue).setOnClickListener {
            val ctx = requireContext()
            if (allGranted(ctx)) {
                dismissAllowingStateLoss()
            } else {
                val msg = missingReasonMessage(ctx)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                view.let { refreshStatus(it) }
            }
        }
        refreshStatus(view)
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate each time the dialog comes back to the foreground.
        // If both checks pass (user toggled them ON in Settings and
        // returned), auto-close so they don't have to hunt for a Done
        // button that doesn't exist.
        recheckAndMaybeDismiss()
        // OEM skins sometimes propagate the battery-optimization flag a
        // few hundred ms after the Settings activity is dismissed. Schedule
        // two additional re-checks so the dialog still self-closes even
        // when the system state lags behind the user.
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ recheckAndMaybeDismiss() }, 500)
        handler.postDelayed({ recheckAndMaybeDismiss() }, 1500)
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
                "Set Location to Allow all the time, then tap Continue."
            !batOk ->
                "Set Battery to Unrestricted, then tap Continue. If you already did, force-close Settings and try again."
            else -> "All set — closing."
        }
    }

    /**
     * Styles each permission row's "Enable" / "✓ Enabled" pill button
     * and updates the privacy-note status text at the bottom.
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
        val autoHint = root.findViewById<TextView>(R.id.tvAutostartHint)
        val status = root.findViewById<TextView>(R.id.tvGateStatus)

        // Style helper: flips a pill between "Enable" (outline) and
        // "✓ Enabled" (filled green-tinted) states.
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

        // Foreground location is the prerequisite for "Allow all the
        // time" being available at all — when the user denied at the
        // initial OS prompt, no amount of Settings poking surfaces the
        // background-location toggle. Surface the issue here.
        if (!fgOk) {
            stylePill(locBtn, false)
        } else {
            stylePill(locBtn, bgOk)
        }

        stylePill(batBtn, batOk)

        // OEM autostart row — only show on Xiaomi / Vivo / Oppo /
        // Realme / Honor where the OS kills background services
        // regardless of the standard "ignore battery optimisation"
        // grant. We can't programmatically check whether autostart is
        // ON for our package (no public API), so the row stays as a
        // permanent "Enable" CTA the user can tap.
        if (needsAutostart) {
            autoRow.visibility = View.VISIBLE
            autoHint.text = "Helps the app start\nautomatically when needed."
            stylePill(autoBtn, false)
        } else {
            autoRow.visibility = View.GONE
        }

        // Privacy note doubles as status text — shows a friendly
        // message when everything is done, or the default privacy text.
        status.text = when {
            !deviceLocationOk -> "Turn on Location/GPS in phone settings to continue."
            !fgOk -> "Tap Enable and select \"Allow all the time\" under Location."
            !bgOk && !batOk -> "Background location + battery both need attention."
            !bgOk -> "Background location still needs \"Allow all the time\"."
            !batOk -> "Battery optimisation still needs to be off."
            needsAutostart -> "Also enable Auto Start — tap Enable above."
            else -> "We respect your privacy and\nonly use these permissions to\nenhance your experience."
        }
    }

    /**
     * Send the user to our app's location-permission detail page so
     * they can pick "Allow all the time" without having to navigate
     * through Android's nested Settings tree.
     */
    private fun openBackgroundLocationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        runCatching { startActivity(intent) }
    }

    private fun openDeviceLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", requireContext().packageName, null)
                        },
                    )
                }
            }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                // A handful of OEMs reject the per-app request intent.
                // Fall back to the system battery-optimization list so
                // the user can find our app manually.
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
    }

    /**
     * Best-effort launch of the OEM-specific autostart-permission
     * screen. Tries the known component name for each manufacturer's
     * Security / Permissions app; falls through to the app details
     * page if every direct target fails. Public hint dialog explains
     * what to look for so the user doesn't get lost.
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
                // Try the next candidate.
            }
        }
        // Final fallback: app details page. Better than a dead button.
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", ctx.packageName, null)
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

    companion object {
        private const val TAG = "BackgroundPermissionsGateDialog"

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
}
