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
import android.widget.TextView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.util.SettingsGuide
import com.manjugroups.m_connect.util.UnusedAppRestrictions

/**
 * Non-dismissible gate Bottom Sheet — premium mockup UI with Custom Switches.
 * Auto-dismisses when all mandatory permissions are granted.
 */
class BackgroundPermissionsGateDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "BackgroundPermissionsGateDialog"
        private const val ARG_TRACKED = "tracked"

        /** A gate for a geo-tracked staffer ([tracked] = true) or anyone else. */
        fun newInstance(tracked: Boolean) = BackgroundPermissionsGateDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_TRACKED, tracked) }
        }
        private const val REQUEST_BG_LOCATION = 1001
        private const val REQUEST_NOTIFICATIONS = 1008
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

        /** Precise (FINE) location specifically — coarse-only is NOT enough
         *  for tracking: approximate fixes (~2km) fail the capture accuracy
         *  gate, producing a Live staffer with a frozen pin. */
        fun hasPreciseLocation(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

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

        // Precise, not fine-or-coarse. This has to agree with
        // missingPermissionKeys() or the two contradict each other: a
        // coarse-only phone passed allGranted() (so the gate never opened and
        // never asked to upgrade) while missingPermissionKeys() reported
        // fine_location missing (so the ongoing red alert was posted). Tapping
        // that alert re-checks allGranted, finds it true and declines to show
        // the gate — an unfixable notification on a phone that, per the note on
        // hasPreciseLocation, was saving no GPS at all.
        /**
         * Defined AS "nothing is missing", not as a second hand-written list.
         *
         * These were two parallel lists and they drifted: allGranted accepted a
         * coarse-only grant while missingPermissionKeys demanded precise, so the
         * gate refused to open on a phone the alert was complaining about. Deriving
         * one from the other makes that class of bug impossible rather than fixed.
         */
        fun allGranted(ctx: Context, tracked: Boolean = true): Boolean =
            missingPermissionKeys(ctx, tracked).isEmpty()

        /**
         * The tracking permissions that are missing right now, as the same
         * string keys the ongoing red alert and the synced PERMISSION_MISSING
         * event use. Single source of truth so the in-app gate and the
         * out-of-app notification always agree on "any permission missing".
         * (Device-location-enabled is a GPS toggle, not a permission — it
         * gates the sheet but is surfaced separately as a GPS_DISABLED signal,
         * so it is intentionally not a key here.)
         */
        fun missingPermissionKeys(ctx: Context, tracked: Boolean = true): List<String> {
            val missing = mutableListOf<String>()
            // STRICTLY precise (FINE) — an approximate-only grant ("Precise
            // location" toggled off) used to pass this check silently while
            // every ~2km-accuracy fix failed the capture gate, so the staff
            // looked Live with a pin frozen on the punch-in and zero GPS
            // saved all day. Re-requesting FINE over a coarse-only grant
            // shows the OS precise-upgrade prompt, so the existing request
            // flow heals it.
            if (!isDeviceLocationEnabled(ctx)) missing.add("location_services")
            if (!hasPreciseLocation(ctx)) missing.add("fine_location")
            // The next three exist only to keep background tracking alive, so
            // only a geo-tracked staffer is asked for them. Everyone still needs
            // location on and precise (punch-in) and notifications.
            if (tracked) {
                if (!hasBackgroundLocation(ctx)) missing.add("background_location")
                if (!hasActivityRecognition(ctx)) missing.add("activity_recognition")
                if (!hasBatteryOptIgnored(ctx)) missing.add("battery_optimization")
            }
            // Last, because it is the one whose absence hides the rest: with
            // notifications off the ongoing alert cannot be posted at all, so a
            // phone with everything else wrong has no way to say so. The gate is
            // then the only channel left, which is why it belongs in this set.
            if (!PushTokenManager.hasNotificationPermission(ctx)) missing.add("notification")
            return missing
        }

        fun showIfNeeded(fm: FragmentManager, ctx: Context, tracked: Boolean = true) {
            val existing = fm.findFragmentByTag(TAG) as? BackgroundPermissionsGateDialog
            if (existing != null) {
                // Already asking for at least as much. The one case to replace:
                // a staffer whose tracking was just switched on is still looking
                // at the shorter untracked sheet.
                if (existing.tracked || !tracked) return
                existing.dismissAllowingStateLoss()
            }
            if (!allGranted(ctx, tracked)) {
                newInstance(tracked).show(fm, TAG)
                return
            }
            // "Manage app if unused" only protects background tracking.
            if (!tracked) return
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
                    if (
                        restrictionOn &&
                        UnusedAppRestrictions.settingsIntent(ctx) != null &&
                        fm.findFragmentByTag(TAG) == null
                    ) {
                        runCatching { newInstance(tracked = true).show(fm, TAG) }
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }
    }

    // On supported devices, "Manage app if unused" must be turned OFF before
    // the gate lets the user through. Unsupported/old OEM devices are released
    // synchronously, before the asynchronous status lookup, so a system option
    // they do not have can never lock them out of the app.
    private var unusedAppSatisfied = false

    /** Defaults to the full tracked set, so an existing caller is unchanged. */
    internal val tracked: Boolean
        get() = arguments?.getBoolean(ARG_TRACKED, true) ?: true

    private val permissionSetup = TrackingPermissionSetup(
        caller = this,
        activity = { activity },
        onProgress = { view?.let(::refreshStatus) },
        onFinished = { onAllowAllFinished() },
        tracked = { tracked },
    )

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

                // Force wrap_content so the sheet hugs its content on tall
                // screens, but cap it to ~92% of the screen so on short devices
                // the content scrolls inside (the layout root is a
                // NestedScrollView) instead of clipping the last rows.
                val lp = it.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.layoutParams = lp
                behavior.maxHeight =
                    (resources.displayMetrics.heightPixels * 0.92f).toInt()
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

        view.findViewById<View>(R.id.btnGateAllowAll).setOnClickListener {
            permissionSetup.start()
        }
        // Always-available manual route, for phones where both "Allow all"
        // and a row's own shortcut fail.
        view.findViewById<View>(R.id.btnGateManualSettings).setOnClickListener {
            SettingsGuide.allTracking(requireContext())
        }

        // Rows stay as the manual path: each opens just its own setting, for
        // phones where a step of "Allow all" is blocked or unsupported.
        view.findViewById<View>(R.id.rowLocation).setOnClickListener {
            val ctx = requireContext()
            val deviceLocationOk = isDeviceLocationEnabled(ctx)
            val preciseOk = hasPreciseLocation(ctx)
            if (!deviceLocationOk || !preciseOk) {
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

        view.findViewById<View>(R.id.rowNotifications).setOnClickListener {
            val ctx = requireContext()
            if (PushTokenManager.hasNotificationPermission(ctx)) {
                showRevokeToast()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS,
                )
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
                SettingsGuide.autostart(ctx, OemAutostart.candidateIntents())
            }
            refreshStatus(view)
        }

        // "Manage app if unused" (hibernation / auto-revoke) — deep-link to the
        // OS setting so the user turns it OFF, keeping the app alive.
        view.findViewById<View>(R.id.rowManageAppUnused).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val intent = UnusedAppRestrictions.settingsIntent(ctx)
            if (intent == null) {
                Toast.makeText(
                    ctx,
                    "This setting is not available on your device.",
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStatus(view)
                return@setOnClickListener
            }
            SettingsGuide.unusedApp(ctx, intent)
        }

        refreshStatus(view)
    }

    /**
     * Resolve the async "unused app restrictions" status and show the row
     * alongside the other permissions only when the device supports the feature
     * and exposes a settings activity for it.
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
        if (!tracked) {
            row.visibility = View.GONE
            unusedAppSatisfied = true
            if (allGranted(ctx, tracked = false)) dismissAllowingStateLoss()
            return
        }
        val settingsIntent = UnusedAppRestrictions.settingsIntent(ctx)
        if (settingsIntent == null) {
            row.visibility = View.GONE
            sw.isChecked = false
            unusedAppSatisfied = true
            if (allGranted(ctx)) {
                com.manjugroups.m_connect.notifications.PermissionAlertNotification.clear(ctx)
                dismissAllowingStateLoss()
            }
            return
        }
        val future = androidx.core.content.PackageManagerCompat
            .getUnusedAppRestrictionsStatus(ctx)
        future.addListener(
            {
                if (!isAdded || view == null) return@addListener
                val status = runCatching { future.get() }.getOrNull()
                val uiState = UnusedAppRestrictions.uiState(
                    status = status,
                    canOpenSettings = true,
                )
                row.visibility = if (uiState.visible) View.VISIBLE else View.GONE
                sw.isChecked = uiState.restrictionDisabled
                unusedAppSatisfied = uiState.satisfied
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
                        SettingsGuide.location(requireContext())
                    }
                }
                recheckAndMaybeDismiss()
            }
            REQUEST_BG_LOCATION -> {
                val ctx = context
                if (ctx != null && !hasBackgroundLocation(ctx) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ) {
                    // Android stops showing the prompt after refusals; the
                    // app's page is the only way left.
                    SettingsGuide.backgroundLocation(ctx)
                }
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
                        SettingsGuide.physicalActivity(requireContext())
                    }
                }
                recheckAndMaybeDismiss()
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun onAllowAllFinished() {
        val ctx = context ?: return
        recheckAndMaybeDismiss()
        if (isAdded && !(allGranted(ctx, tracked) && unusedAppSatisfied)) {
            Toast.makeText(
                ctx,
                "Some settings need to be turned on by hand. Tap each row that is still off.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun recheckAndMaybeDismiss() {
        if (!isAdded || isDetached) return
        val ctx = context ?: return
        val root = view ?: return
        if (allGranted(ctx, tracked) && unusedAppSatisfied) {
            // Everything's on now — take the ongoing red alert down with the
            // sheet so the two never disagree.
            com.manjugroups.m_connect.notifications.PermissionAlertNotification.clear(ctx)
            dismissAllowingStateLoss()
        } else {
            // Keep the out-of-app alert in step with what's still missing. It
            // says "tracking can't work", so an untracked staffer never gets it.
            if (tracked) {
                com.manjugroups.m_connect.notifications.PermissionAlertNotification
                    .update(ctx, missingPermissionKeys(ctx))
            } else {
                com.manjugroups.m_connect.notifications.PermissionAlertNotification.clear(ctx)
            }
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
        val preciseOk = hasPreciseLocation(ctx)
        val bgOk = hasBackgroundLocation(ctx)
        val activityOk = hasActivityRecognition(ctx)
        val batOk = hasBatteryOptIgnored(ctx)
        val autostartOk = isAutostartEnabled(ctx)
        root.findViewById<View>(R.id.btnGateAllowAll).isEnabled = !permissionSetup.isRunning

        root.findViewById<SwitchCompat>(R.id.switchLocation).isChecked = deviceLocationOk && preciseOk
        root.findViewById<SwitchCompat>(R.id.switchBgLocation).isChecked = bgOk
        root.findViewById<SwitchCompat>(R.id.switchActivityRecognition).isChecked = activityOk
        root.findViewById<SwitchCompat>(R.id.switchBatteryOpt).isChecked = batOk
        root.findViewById<SwitchCompat>(R.id.switchNotifications).isChecked =
            PushTokenManager.hasNotificationPermission(ctx)
        
        // Untracked staff see only what they are asked for.
        val trackingOnly = if (tracked) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.rowBgLocation).visibility = trackingOnly
        root.findViewById<View>(R.id.rowActivityRecognition).visibility = trackingOnly
        root.findViewById<View>(R.id.rowBatteryOpt).visibility = trackingOnly
        root.findViewById<TextView>(R.id.txtNotificationsDesc).text =
            if (tracked) "So the app can tell you the moment tracking stops working"
            else "So you get approvals, tasks and alerts on time"

        val autoRow = root.findViewById<View>(R.id.rowAutostart)
        if (tracked && isAutostartManaged()) {
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
        if (!hasPreciseLocation(ctx)) {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_FG_LOCATION,
            )
        } else if (!isDeviceLocationEnabled(ctx)) {
            // The Location page itself: its main switch is the only setting on
            // it. Only if this phone has no such page, the guided route.
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .onFailure { SettingsGuide.deviceLocation(ctx) }
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
            SettingsGuide.location(ctx)
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
                // No Allow/Deny dialog on this phone: the guided route to this
                // app's Battery row, not the phone-wide list of every app.
                SettingsGuide.battery(requireContext())
            }
    }

    private fun isAutostartManaged(): Boolean = OemAutostart.isManaged()

    private fun isAutostartEnabled(ctx: Context): Boolean = OemAutostart.isMarkedEnabled(ctx)

    private fun setAutostartEnabled(ctx: Context, enabled: Boolean) =
        OemAutostart.markEnabled(ctx, enabled)
}
