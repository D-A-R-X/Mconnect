package com.manjugroups.m_connect.geotrack

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
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
 *  • Each row shows an "Open" button that launches the system settings
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
        // A DialogFragment window defaults to WRAP_CONTENT width, which lets
        // our wrapping body text collapse the dialog to the width of its
        // longest word (the "narrow strip" bug where buttons render as blank
        // pills). Pin the window to most of the screen width so match_parent
        // children lay out correctly.
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnFixBgLocation).setOnClickListener {
            openBackgroundLocationSettings()
        }
        view.findViewById<View>(R.id.btnFixBatteryOpt).setOnClickListener {
            openBatteryOptimizationSettings()
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
        val bgOk = hasBackgroundLocation(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        return when {
            !bgOk && !batOk ->
                "Enable background location (Allow all the time) and unrestricted battery use to continue."
            !bgOk ->
                "Set Location to Allow all the time, then tap Continue."
            !batOk ->
                "Set Battery to Unrestricted, then tap Continue. If you already did, force-close Settings and try again."
            else -> "All set — closing."
        }
    }

    private fun refreshStatus(root: View) {
        val ctx = requireContext()
        val bgOk = hasBackgroundLocation(ctx)
        val batOk = hasBatteryOptIgnored(ctx)

        val locBtn = root.findViewById<TextView>(R.id.btnFixBgLocation)
        val batBtn = root.findViewById<TextView>(R.id.btnFixBatteryOpt)
        val status = root.findViewById<TextView>(R.id.tvGateStatus)

        locBtn.text = if (bgOk) "Enabled" else "Open"
        locBtn.alpha = if (bgOk) 0.5f else 1f
        locBtn.isEnabled = !bgOk

        batBtn.text = if (batOk) "Enabled" else "Open"
        batBtn.alpha = if (batOk) 0.5f else 1f
        batBtn.isEnabled = !batOk

        status.text = when {
            bgOk && batOk -> "All set — closing…"
            bgOk -> "Battery optimisation still needs to be off."
            batOk -> "Background location still needs Allow all the time."
            else -> "Both must be enabled before you can continue."
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

    companion object {
        private const val TAG = "BackgroundPermissionsGateDialog"

        fun hasBackgroundLocation(ctx: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasBatteryOptIgnored(ctx: android.content.Context): Boolean {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        fun allGranted(ctx: android.content.Context): Boolean =
            hasBackgroundLocation(ctx) && hasBatteryOptIgnored(ctx)

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
