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
 * Makes every available update MANDATORY.
 *
 * Any update Google Play offers, or a minimum version required by the backend
 * (`/api/mobile/app-version`), puts up a full-screen page with a single Update
 * button: no cancel, no close, Back is blocked. It shows on any screen, even
 * mid-shift.
 *
 * This used to be an optional "Update available" card that appeared only on the
 * idle Home screen once attendance, tracking and offline queues were all quiet,
 * so staff who stay clocked in all day almost never saw it. Forcing it is safe:
 * punches, GeoTrack points and events are stored on the device and survive the
 * update, and BootReceiver restarts tracking on MY_PACKAGE_REPLACED.
 *
 * Play's IMMEDIATE flow is preferred (Play drives download and install
 * full-screen). If only FLEXIBLE is allowed, the page shows the download and
 * installs as soon as it finishes. Backing out of Play's screen simply returns
 * to the page. Dev and sideloaded builds get no Play update; for them only the
 * backend policy applies.
 */
class InAppUpdateManager(
    private val activity: AppCompatActivity,
    private val api: ApiService,
    private val onUiStateChanged: (InAppUpdateUiState) -> Unit,
) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                // Backed out of Play's screen: the update is still required,
                // so the page stays up with its Update button.
                Log.w(TAG, "Update flow not completed (resultCode=${result.resultCode})")
                emit(InAppUpdateUiState.Available(required = true))
                checkForUpdate()
            }
        }

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.PENDING -> emit(InAppUpdateUiState.Preparing)
            InstallStatus.DOWNLOADING -> emit(
                InAppUpdateUiState.Downloading(
                    downloadProgressPercentage(state.bytesDownloaded(), state.totalBytesToDownload()),
                ),
            )
            InstallStatus.DOWNLOADED -> installDownloaded()
            InstallStatus.INSTALLING -> emit(InAppUpdateUiState.Installing)
            InstallStatus.INSTALLED -> emit(InAppUpdateUiState.Hidden)
            InstallStatus.CANCELED, InstallStatus.FAILED -> {
                emit(InAppUpdateUiState.Available(required = true))
                checkForUpdate()
            }
        }
    }

    private var listenerRegistered = false
    private var availabilityJob: Job? = null
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
        checkForUpdate()
        availabilityJob?.cancel()
        availabilityJob = activity.lifecycleScope.launch {
            while (isActive) {
                delay(FOREGROUND_CHECK_INTERVAL_MS)
                checkForUpdate()
            }
        }
    }

    fun onAppBackgrounded() {
        availabilityJob?.cancel()
        availabilityJob = null
        // A flexible update that finished downloading is installed right away.
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.installStatus() == InstallStatus.DOWNLOADED) installDownloaded()
            }
    }

    fun destroy() {
        availabilityJob?.cancel()
        availabilityJob = null
        updateAction = null
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    private fun checkForUpdate() {
        manager.appUpdateInfo
            .addOnSuccessListener(::handleUpdateInfo)
            .addOnFailureListener { error ->
                Log.d(TAG, "appUpdateInfo check failed: ${error.message}")
                checkRemotePolicy()
            }
    }

    private fun handleUpdateInfo(info: AppUpdateInfo) {
        val status = info.installStatus()
        val availability = info.updateAvailability()
        when {
            status == InstallStatus.DOWNLOADED -> installDownloaded()
            status == InstallStatus.INSTALLING -> emit(InAppUpdateUiState.Installing)
            status == InstallStatus.PENDING || status == InstallStatus.DOWNLOADING ->
                emit(InAppUpdateUiState.Preparing)
            // An immediate flow already in progress (e.g. the app was killed
            // mid-update): resume it.
            availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                updateAction = UpdateAction.PlayImmediate(info)
                launchImmediate(info)
            }
            availability == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> {
                updateAction = UpdateAction.PlayImmediate(info)
                emit(InAppUpdateUiState.Available(required = true))
            }
            availability == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                updateAction = UpdateAction.PlayFlexible(info)
                emit(InAppUpdateUiState.Available(required = true))
            }
            else -> {
                updateAction = null
                emit(InAppUpdateUiState.Hidden)
                checkRemotePolicy()
            }
        }
    }

    private fun checkRemotePolicy() {
        remotePolicy?.let(::showRemotePolicy)
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
            remotePolicy?.let(::showRemotePolicy)
        }
    }

    private fun showRemotePolicy(policy: RequiredRemoteUpdate) {
        updateAction = UpdateAction.External(policy.updateUrl)
        emit(InAppUpdateUiState.ExternalRequired(policy.version))
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

    /** The page's green button. */
    fun performPrimaryAction() {
        when (val action = updateAction) {
            is UpdateAction.PlayImmediate -> launchImmediate(action.info)
            is UpdateAction.PlayFlexible -> launchFlexible(action.info)
            is UpdateAction.External -> runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
            }.onFailure { Log.w(TAG, "Opening update URL failed: ${it.message}") }
            UpdateAction.Restart -> installDownloaded()
            null -> checkForUpdate()
        }
    }

    /** A downloaded (flexible) update is required: install it now. */
    private fun installDownloaded() {
        updateAction = UpdateAction.Restart
        emit(InAppUpdateUiState.Installing)
        manager.completeUpdate().addOnFailureListener { error ->
            Log.w(TAG, "Completing downloaded update failed: ${error.message}")
            emit(InAppUpdateUiState.ReadyToRestart)
        }
    }

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
            Log.w(TAG, "Starting immediate update failed: ${it.message}")
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
            emit(InAppUpdateUiState.Available(required = true))
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
