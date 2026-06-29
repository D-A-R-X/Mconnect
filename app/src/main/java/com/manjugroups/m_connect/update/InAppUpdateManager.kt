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
                    // An immediate update Play already started but didn't finish.
                    info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        launchImmediate(info)

                    info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> Unit

                    info.updatePriority() >= HIGH_PRIORITY &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        launchImmediate(info)

                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        launchFlexible(info)
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

    fun destroy() {
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
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
        }.onFailure { Log.w(TAG, "startUpdateFlow (flexible) failed: ${it.message}") }
    }

    private fun promptCompleteFlexibleUpdate() {
        val root: View = activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(
            root,
            "An update has just been downloaded.",
            Snackbar.LENGTH_INDEFINITE,
        ).apply {
            setAction("RESTART") { manager.completeUpdate() }
            show()
        }
    }

    companion object {
        private const val TAG = "InAppUpdate"
        /** inAppUpdatePriority (0..5) at/above which we force an IMMEDIATE update. */
        private const val HIGH_PRIORITY = 4
    }
}
