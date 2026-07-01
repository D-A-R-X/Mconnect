package com.manjugroups.m_connect.update

import android.app.Activity
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Drives Google Play in-app updates for the app.
 *
 * Wiring (from the host activity, e.g. [com.manjugroups.m_connect.MainActivity]):
 *  - construct + [start] in `onCreate` (registers the result launcher BEFORE the
 *    activity is STARTED, then kicks the first availability check),
 *  - [onResume] in `onResume` (finishes a downloaded flexible update and resumes
 *    a stalled immediate update),
 *  - [destroy] in `onDestroy` (unregisters the install listener).
 *
 * Strategy:
 *  - **Immediate** (blocking, full-screen) for high-priority releases —
 *    `inAppUpdatePriority >= [HIGH_PRIORITY]`, set per-release via the Play
 *    Developer API. Use this for critical/security fixes.
 *  - **Flexible** (downloads in the background; we show a "Restart" snackbar
 *    when ready) for everything else.
 *
 * Safe off Google Play (dev builds / sideload): `appUpdateInfo` simply fails or
 * reports no update, and every call here degrades to a no-op.
 */
class InAppUpdateManager(
    private val activity: AppCompatActivity,
) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    // Registered during the activity's onCreate (before STARTED) so the contract
    // is valid by the time an update flow is launched.
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                // Cancelled / failed. Nothing is blocked — we re-check and
                // re-offer on the next app entry (start / onResume).
                Log.w(TAG, "In-app update flow not completed (resultCode=${result.resultCode})")
            }
        }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            promptCompleteFlexibleUpdate()
        }
    }
    private var listenerRegistered = false

    /** Register for install-state updates and run the first availability check. */
    fun start() {
        if (!listenerRegistered) {
            manager.registerListener(installListener)
            listenerRegistered = true
        }
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    // Already downloaded (a prior session) — nudge to install.
                    info.installStatus() == InstallStatus.DOWNLOADED ->
                        promptCompleteFlexibleUpdate()

                    // An immediate update Play already started but didn't finish.
                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        launchImmediate(info)

                    info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> Unit

                    info.updatePriority() >= HIGH_PRIORITY &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        launchImmediate(info)

                    // Flexible: show OUR branded "Update available" prompt on the
                    // current (home) screen. Tapping Update starts the real
                    // background download; the branded "restart" snackbar then
                    // fires once it finishes (see installListener). Re-runs on
                    // every launch, so it keeps prompting until the app is current.
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        showUpdateAvailableDialog { launchFlexible(info) }
                }
            }
            .addOnFailureListener { e ->
                // Most commonly: not installed from Play (dev/sideload).
                Log.d(TAG, "appUpdateInfo check failed: ${e.message}")
            }
    }

    /** Call from the host activity's onResume. */
    fun onResume() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    // A flexible update finished downloading while we were away —
                    // prompt to install so it doesn't just sit in storage.
                    info.installStatus() == InstallStatus.DOWNLOADED ->
                        promptCompleteFlexibleUpdate()

                    // An immediate update got interrupted — resume it.
                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        launchImmediate(info)
                }
            }
            .addOnFailureListener { /* ignore — off Play / transient */ }
    }

    /**
     * Our branded "Update available" prompt, shown on the current (home) screen
     * when Play has a flexible update. If the user dismisses it, it re-appears on
     * the next launch (start() re-checks) — so it persists until the app updates.
     */
    private fun showUpdateAvailableDialog(onUpdate: () -> Unit) {
        val view = android.view.LayoutInflater.from(activity)
            .inflate(com.manjugroups.m_connect.R.layout.dialog_app_update, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
        )
        view.findViewById<View>(com.manjugroups.m_connect.R.id.btnUpdateLater)
            .setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(com.manjugroups.m_connect.R.id.btnUpdateNow)
            .setOnClickListener {
                dialog.dismiss()
                onUpdate()
            }
        runCatching { dialog.show() }
        dialog.window?.setLayout(
            (320 * activity.resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** Branded "update ready to install" snackbar (restart to apply). */
    private fun showUpdateReadySnackbar(indefinite: Boolean, onRestart: () -> Unit) {
        val root: View = activity.findViewById(android.R.id.content) ?: return
        val snackbar = Snackbar.make(
            root,
            "Update downloaded — ready to install.",
            if (indefinite) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG,
        )
        snackbar.setAction("RESTART") { onRestart() }
        snackbar.setTextColor(android.graphics.Color.WHITE)
        snackbar.setActionTextColor(android.graphics.Color.parseColor("#7FB0FF"))
        val sbView = snackbar.view
        sbView.setBackgroundResource(com.manjugroups.m_connect.R.drawable.bg_update_snackbar)
        (sbView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { p ->
            val m = (12 * activity.resources.displayMetrics.density).toInt()
            p.setMargins(m, m, m, m)
            sbView.layoutParams = p
        }
        sbView.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(activity, com.manjugroups.m_connect.R.font.inter_medium)
            textSize = 14f
        }
        sbView.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(activity, com.manjugroups.m_connect.R.font.inter_semibold)
        }
        snackbar.show()
    }

    fun destroy() {
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    /** Fallback when the in-app update flow can't start: open the Play page. */
    private fun openPlayStore() {
        val pkg = activity.packageName
        val market = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=$pkg"),
        ).setPackage("com.android.vending")
        runCatching { activity.startActivity(market) }.onFailure {
            runCatching {
                activity.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                    ),
                )
            }
        }
    }

    private fun launchImmediate(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
            )
        }.onFailure { Log.w(TAG, "startUpdateFlow (immediate) failed: ${it.message}") }
    }

    private fun launchFlexible(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.onFailure {
            Log.w(TAG, "startUpdateFlow (flexible) failed: ${it.message}")
            openPlayStore()
        }
    }

    private fun promptCompleteFlexibleUpdate() {
        showUpdateReadySnackbar(indefinite = true) { manager.completeUpdate() }
    }

    companion object {
        private const val TAG = "InAppUpdate"
        /** inAppUpdatePriority (0..5) at/above which we force an IMMEDIATE update. */
        private const val HIGH_PRIORITY = 4
    }
}
