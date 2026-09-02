package com.manjugroups.m_connect.update

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.manjugroups.m_connect.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives Google Play flexible updates without interrupting operational work.
 *
 * The host supplies two fail-closed gates: the UI must be on its idle Home root,
 * and the operational gate must confirm that attendance, tracking, field work,
 * calls, and offline queues are inactive. Release priority never bypasses them.
 *
 * Dev and sideloaded builds remain safe: Play reports no update and this becomes
 * a no-op. A flexible update still requires Google's one-time user consent.
 */
class InAppUpdateManager(
    private val activity: AppCompatActivity,
    private val isUiIdle: () -> Boolean,
    private val isOperationallyIdle: suspend () -> Boolean,
) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "In-app update flow not completed (resultCode=${result.resultCode})")
            }
        }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            handleDownloadedUpdate()
        }
    }

    private var listenerRegistered = false
    private var isForeground = false
    private var safetyJob: Job? = null
    private var visibleDialog: AlertDialog? = null

    /** Register callbacks in onCreate; availability is checked in onResume. */
    fun start() {
        if (!listenerRegistered) {
            manager.registerListener(installListener)
            listenerRegistered = true
        }
    }

    fun onResume() {
        isForeground = true
        if (!isUiIdle()) return
        manager.appUpdateInfo
            .addOnSuccessListener(::handleUpdateInfoInForeground)
            .addOnFailureListener { error ->
                Log.d(TAG, "appUpdateInfo check failed: ${error.message}")
            }
    }

    /** Complete a downloaded update only after the host enters the background. */
    fun onAppBackgrounded() {
        isForeground = false
        visibleDialog?.dismiss()
        visibleDialog = null
        if (!isUiIdle()) return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    runWhenSafe(requireForeground = false) {
                        manager.completeUpdate()
                            .addOnFailureListener { error ->
                                Log.w(TAG, "Background update completion failed: ${error.message}")
                            }
                    }
                }
            }
            .addOnFailureListener { /* Retry at the next safe app entry. */ }
    }

    fun destroy() {
        safetyJob?.cancel()
        safetyJob = null
        visibleDialog?.dismiss()
        visibleDialog = null
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    private fun handleUpdateInfoInForeground(info: AppUpdateInfo) {
        when {
            info.installStatus() == InstallStatus.DOWNLOADED ->
                handleDownloadedUpdate()

            // An older version may already have started an immediate flow.
            info.updateAvailability() ==
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                runWhenSafe { launchImmediate(info) }

            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                runWhenSafe {
                    showUpdateDialog(
                        title = "Update available",
                        message = "A new version of MConnect is available. It will download in the background.",
                        actionLabel = "Update",
                    ) { launchFlexible(info) }
                }
        }
    }

    private fun handleDownloadedUpdate() {
        if (!isForeground) return
        runWhenSafe {
            showUpdateDialog(
                title = "App restart needed to install new updates",
                message = "The update is ready. Restart MConnect now to use the latest version.",
                actionLabel = "Restart now",
            ) { manager.completeUpdate() }
        }
    }

    private fun runWhenSafe(
        requireForeground: Boolean = true,
        action: () -> Unit,
    ) {
        if (!isUiIdle() || !hasExpectedVisibility(requireForeground)) return
        safetyJob?.cancel()
        safetyJob = activity.lifecycleScope.launch {
            val safe = runCatching { isOperationallyIdle() }.getOrDefault(false)
            if (!safe || !isUiIdle() || !hasExpectedVisibility(requireForeground)) return@launch
            action()
        }
    }

    private fun hasExpectedVisibility(requireForeground: Boolean): Boolean =
        if (requireForeground) isForeground else !isForeground

    private fun showUpdateDialog(
        title: String,
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        if (!isForeground || visibleDialog?.isShowing == true) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        val dialog = AlertDialog.Builder(activity).setView(view).create()
        visibleDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.findViewById<TextView>(R.id.txtUpdateTitle).text = title
        view.findViewById<TextView>(R.id.txtUpdateMessage).text = message
        view.findViewById<View>(R.id.btnUpdateLater).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnUpdateNow).apply {
            text = actionLabel
            setOnClickListener {
                dialog.dismiss()
                onAction()
            }
        }
        dialog.setOnDismissListener {
            if (visibleDialog === dialog) visibleDialog = null
        }
        runCatching { dialog.show() }.onFailure {
            if (visibleDialog === dialog) visibleDialog = null
        }
        dialog.window?.setLayout(
            (320 * activity.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun launchImmediate(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
            )
        }.onFailure { Log.w(TAG, "Resuming immediate update failed: ${it.message}") }
    }

    private fun launchFlexible(info: AppUpdateInfo) {
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.onFailure { Log.w(TAG, "Starting flexible update failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "InAppUpdate"
    }
}
