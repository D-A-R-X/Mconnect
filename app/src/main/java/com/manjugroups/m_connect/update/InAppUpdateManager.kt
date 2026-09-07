package com.manjugroups.m_connect.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    private val onUiStateChanged: (InAppUpdateUiState) -> Unit,
) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "In-app update flow not completed (resultCode=${result.resultCode})")
                updateAction = null
                emit(InAppUpdateUiState.Hidden)
                pendingUpdateInfo = null
                checkForUpdate()
            }
        }

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.PENDING -> emit(InAppUpdateUiState.Preparing)
            InstallStatus.DOWNLOADING -> emit(
                InAppUpdateUiState.Downloading(
                    downloadProgressPercentage(
                        state.bytesDownloaded(),
                        state.totalBytesToDownload(),
                    ),
                ),
            )
            InstallStatus.DOWNLOADED -> {
                if (isForeground) handleDownloadedUpdate()
                else completeDownloadedUpdateInBackground()
            }
            InstallStatus.INSTALLING -> emit(InAppUpdateUiState.Installing)
            InstallStatus.INSTALLED -> emit(InAppUpdateUiState.Hidden)
            InstallStatus.CANCELED, InstallStatus.FAILED -> {
                updateAction = null
                emit(InAppUpdateUiState.Hidden)
                checkForUpdate()
            }
        }
    }

    private var listenerRegistered = false
    private var isForeground = false
    private var safetyJob: Job? = null
    private var availabilityJob: Job? = null
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var updateAction: UpdateAction? = null
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

    /** Install a downloaded update in the background only after all work is idle. */
    fun onAppBackgrounded() {
        isForeground = false
        availabilityJob?.cancel()
        availabilityJob = null
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    completeDownloadedUpdateInBackground()
                }
            }
            .addOnFailureListener { /* Retry on the next background or foreground check. */ }
    }

    fun destroy() {
        safetyJob?.cancel()
        safetyJob = null
        availabilityJob?.cancel()
        availabilityJob = null
        pendingUpdateInfo = null
        updateAction = null
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    private fun handleUpdateInfoInForeground(info: AppUpdateInfo) {
        val installStatus = info.installStatus()
        val hasActionableUpdate =
            installStatus == InstallStatus.PENDING ||
                installStatus == InstallStatus.DOWNLOADING ||
                installStatus == InstallStatus.DOWNLOADED ||
                installStatus == InstallStatus.INSTALLING ||
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
                (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ||
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)))
        pendingUpdateInfo = info.takeIf { hasActionableUpdate }
        if (!hasActionableUpdate) {
            updateAction = null
            emit(InAppUpdateUiState.Hidden)
            checkRemotePolicy()
        }
        when {
            installStatus == InstallStatus.PENDING -> emit(InAppUpdateUiState.Preparing)

            installStatus == InstallStatus.DOWNLOADING -> emit(InAppUpdateUiState.Preparing)

            installStatus == InstallStatus.INSTALLING -> emit(InAppUpdateUiState.Installing)

            installStatus == InstallStatus.DOWNLOADED ->
                handleDownloadedUpdate()

            // An older version may already have started an immediate flow.
            info.updateAvailability() ==
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                runWhenSafe { launchImmediate(info) }

            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                runWhenSafe {
                    updateAction = UpdateAction.PlayFlexible(info)
                    emit(InAppUpdateUiState.Available(required = false))
                }

            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                runWhenSafe {
                    updateAction = UpdateAction.PlayImmediate(info)
                    emit(InAppUpdateUiState.Available(required = true))
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
            updateAction = UpdateAction.External(policy.updateUrl)
            emit(InAppUpdateUiState.ExternalRequired(policy.version))
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
        updateAction = UpdateAction.Restart
        emit(InAppUpdateUiState.ReadyToRestart)
    }

    fun performPrimaryAction() {
        when (val action = updateAction) {
            is UpdateAction.PlayFlexible -> runWhenSafe { launchFlexible(action.info) }
            is UpdateAction.PlayImmediate -> runWhenSafe { launchImmediate(action.info) }
            is UpdateAction.External -> runWhenSafe {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
                }.onFailure { Log.w(TAG, "Opening update URL failed: ${it.message}") }
            }
            UpdateAction.Restart -> requestSafeRestart()
            null -> Unit
        }
    }

    private fun requestSafeRestart() {
        if (!isForeground) return
        emit(InAppUpdateUiState.CheckingRestartSafety)
        safetyJob?.cancel()
        safetyJob = activity.lifecycleScope.launch {
            val safe = isUiIdle() && runCatching { isOperationallyIdle() }.getOrDefault(false)
            if (!safe || !isForeground) {
                emit(InAppUpdateUiState.WaitingForIdle)
                return@launch
            }
            emit(InAppUpdateUiState.Installing)
            manager.completeUpdate().addOnFailureListener { error ->
                Log.w(TAG, "Completing downloaded update failed: ${error.message}")
                emit(InAppUpdateUiState.ReadyToRestart)
            }
        }
    }

    private fun completeDownloadedUpdateInBackground() {
        if (isForeground) return
        runWhenSafe(requireForeground = false) {
            manager.completeUpdate().addOnFailureListener { error ->
                Log.w(TAG, "Background update completion failed: ${error.message}")
                emit(InAppUpdateUiState.ReadyToRestart)
            }
        }
    }

    private fun runWhenSafe(
        requireForeground: Boolean = true,
        action: () -> Unit,
    ) {
        if ((requireForeground && !isUiIdle()) || !hasExpectedVisibility(requireForeground)) return
        safetyJob?.cancel()
        safetyJob = activity.lifecycleScope.launch {
            val safe = runCatching { isOperationallyIdle() }.getOrDefault(false)
            if (
                !safe ||
                (requireForeground && !isUiIdle()) ||
                !hasExpectedVisibility(requireForeground)
            ) return@launch
            action()
        }
    }

    private fun hasExpectedVisibility(requireForeground: Boolean): Boolean =
        if (requireForeground) isForeground else !isForeground

    private fun emit(state: InAppUpdateUiState) {
        activity.runOnUiThread { onUiStateChanged(state) }
    }

    private fun launchImmediate(info: AppUpdateInfo) {
        emit(InAppUpdateUiState.Preparing)
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
            )
        }.onFailure {
            Log.w(TAG, "Resuming immediate update failed: ${it.message}")
            emit(InAppUpdateUiState.Available(required = true))
        }
    }

    private fun launchFlexible(info: AppUpdateInfo) {
        emit(InAppUpdateUiState.Preparing)
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.onFailure {
            Log.w(TAG, "Starting flexible update failed: ${it.message}")
            emit(InAppUpdateUiState.Available(required = false))
        }
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

    private sealed interface UpdateAction {
        data class PlayFlexible(val info: AppUpdateInfo) : UpdateAction
        data class PlayImmediate(val info: AppUpdateInfo) : UpdateAction
        data class External(val url: String) : UpdateAction
        data object Restart : UpdateAction
    }
}

sealed interface InAppUpdateUiState {
    data object Hidden : InAppUpdateUiState
    data class Available(val required: Boolean) : InAppUpdateUiState
    data object Preparing : InAppUpdateUiState
    data class Downloading(val progressPercent: Int?) : InAppUpdateUiState
    data object ReadyToRestart : InAppUpdateUiState
    data object CheckingRestartSafety : InAppUpdateUiState
    data object WaitingForIdle : InAppUpdateUiState
    data object Installing : InAppUpdateUiState
    data class ExternalRequired(val version: String) : InAppUpdateUiState
}

internal fun downloadProgressPercentage(downloadedBytes: Long, totalBytes: Long): Int? {
    if (downloadedBytes < 0L || totalBytes <= 0L) return null
    return ((downloadedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
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
