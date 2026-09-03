package com.manjugroups.m_connect.update

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
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
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MobileAppVersionResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val api: ApiService,
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
                pendingUpdateInfo = null
                checkForUpdate()
            }
        }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            if (isForeground) handleDownloadedUpdate() else completeDownloadedUpdateInBackground()
        }
    }

    private var listenerRegistered = false
    private var isForeground = false
    private var safetyJob: Job? = null
    private var availabilityJob: Job? = null
    private var visibleDialog: AlertDialog? = null
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var remotePolicy: RequiredRemoteUpdate? = restoreRemotePolicy()
    private var lastRemoteCheckMs = 0L

    /** Register callbacks in onCreate; availability is checked in onResume. */
    fun start() {
        if (!listenerRegistered) {
            manager.registerListener(installListener)
            listenerRegistered = true
        }
    }

    fun onResume() {
        isForeground = true
        checkForUpdate()
        availabilityJob?.cancel()
        availabilityJob = activity.lifecycleScope.launch {
            while (isActive) {
                delay(FOREGROUND_CHECK_INTERVAL_MS)
                checkForUpdate()
            }
        }
    }

    /** Re-evaluate a cached update when navigation or operational state changes. */
    fun onHostStateChanged() {
        if (!isForeground || !isUiIdle()) return
        pendingUpdateInfo?.let(::handleUpdateInfoInForeground) ?: checkForUpdate()
    }

    private fun checkForUpdate() {
        manager.appUpdateInfo
            .addOnSuccessListener(::handleUpdateInfoInForeground)
            .addOnFailureListener { error ->
                Log.d(TAG, "appUpdateInfo check failed: ${error.message}")
                checkRemotePolicy()
            }
    }

    /** Complete a downloaded update only after the host enters the background. */
    fun onAppBackgrounded() {
        isForeground = false
        availabilityJob?.cancel()
        availabilityJob = null
        visibleDialog?.dismiss()
        visibleDialog = null
        if (!isUiIdle()) return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    completeDownloadedUpdateInBackground()
                }
            }
            .addOnFailureListener { /* Retry at the next safe app entry. */ }
    }

    fun destroy() {
        safetyJob?.cancel()
        safetyJob = null
        availabilityJob?.cancel()
        availabilityJob = null
        pendingUpdateInfo = null
        visibleDialog?.dismiss()
        visibleDialog = null
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    private fun handleUpdateInfoInForeground(info: AppUpdateInfo) {
        val hasActionableUpdate =
            info.installStatus() == InstallStatus.DOWNLOADED ||
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
                (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ||
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)))
        pendingUpdateInfo = info.takeIf { hasActionableUpdate }
        if (!hasActionableUpdate) checkRemotePolicy()
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

            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                runWhenSafe {
                    showUpdateDialog(
                        title = "Update required",
                        message = "A new version of MConnect is required to continue.",
                        actionLabel = "Update now",
                    ) { launchImmediate(info) }
                }
        }
    }

    private fun checkRemotePolicy() {
        remotePolicy?.let(::showRemotePolicyWhenSafe)
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRemoteCheckMs != 0L && now - lastRemoteCheckMs < REMOTE_CHECK_INTERVAL_MS) return
        lastRemoteCheckMs = now
        activity.lifecycleScope.launch {
            val response = runCatching {
                api.getMobileAppVersion(
                    platform = "android",
                    currentVersion = BuildConfig.VERSION_NAME,
                    buildNumber = BuildConfig.VERSION_CODE,
                    appVersionHeader = BuildConfig.VERSION_NAME,
                    appBuildHeader = BuildConfig.VERSION_CODE,
                )
            }.getOrNull() ?: return@launch
            remotePolicy = response.toRequiredUpdate()
            persistRemotePolicy(remotePolicy)
            remotePolicy?.let(::showRemotePolicyWhenSafe)
        }
    }

    private fun showRemotePolicyWhenSafe(policy: RequiredRemoteUpdate) {
        runWhenSafe {
            showUpdateDialog(
                title = "Update required",
                message = "MConnect ${policy.version} is ready. Update the app to continue.",
                actionLabel = "Update now",
            ) {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policy.updateUrl)))
                }.onFailure { Log.w(TAG, "Opening update URL failed: ${it.message}") }
            }
        }
    }

    private fun MobileAppVersionResponse.toRequiredUpdate(): RequiredRemoteUpdate? {
        if (!requiresMandatoryMobileUpdate(this, BuildConfig.VERSION_CODE)) return null
        val candidateVersion = minimumSupportedVersion ?: latestVersion ?: "new version"
        val candidateBuild = minimumSupportedBuildNumber ?: latestBuildNumber ?: return null
        val url = updateUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RequiredRemoteUpdate(candidateVersion, candidateBuild, url)
    }

    private fun restoreRemotePolicy(): RequiredRemoteUpdate? {
        val prefs = activity.getSharedPreferences(REMOTE_PREFS, Activity.MODE_PRIVATE)
        val version = prefs.getString(KEY_REMOTE_VERSION, null) ?: return null
        val build = prefs.getInt(KEY_REMOTE_BUILD, 0).takeIf { it > 0 }
        val url = prefs.getString(KEY_REMOTE_URL, null) ?: return null
        if (build == null || build <= BuildConfig.VERSION_CODE) {
            prefs.edit().clear().apply()
            return null
        }
        return RequiredRemoteUpdate(version, build, url)
    }

    private fun persistRemotePolicy(policy: RequiredRemoteUpdate?) {
        activity.getSharedPreferences(REMOTE_PREFS, Activity.MODE_PRIVATE).edit().apply {
            if (policy == null) {
                clear()
            } else {
                putString(KEY_REMOTE_VERSION, policy.version)
                if (policy.buildNumber == null) remove(KEY_REMOTE_BUILD)
                else putInt(KEY_REMOTE_BUILD, policy.buildNumber)
                putString(KEY_REMOTE_URL, policy.updateUrl)
            }
        }.apply()
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

    private fun completeDownloadedUpdateInBackground() {
        if (isForeground || !isUiIdle()) return
        runWhenSafe(requireForeground = false) {
            manager.completeUpdate()
                .addOnFailureListener { error ->
                    Log.w(TAG, "Background update completion failed: ${error.message}")
                }
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
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        visibleDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.findViewById<TextView>(R.id.txtUpdateTitle).text = title
        view.findViewById<TextView>(R.id.txtUpdateMessage).text = message
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
        private const val FOREGROUND_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val REMOTE_CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val REMOTE_PREFS = "mandatory_app_update"
        private const val KEY_REMOTE_VERSION = "version"
        private const val KEY_REMOTE_BUILD = "build"
        private const val KEY_REMOTE_URL = "url"
    }

    private data class RequiredRemoteUpdate(
        val version: String,
        val buildNumber: Int?,
        val updateUrl: String,
    )
}

internal fun requiresMandatoryMobileUpdate(
    policy: MobileAppVersionResponse,
    installedBuildNumber: Int,
): Boolean {
    if (!policy.success) return false
    val minimumBuild = policy.minimumSupportedBuildNumber
        ?: policy.latestBuildNumber?.takeIf { policy.updateRequired == true }
        ?: return false
    return policy.updateRequired == true || installedBuildNumber < minimumBuild
}
