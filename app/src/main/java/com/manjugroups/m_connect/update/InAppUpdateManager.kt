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
import androidx.lifecycle.Lifecycle
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
 * Prefer FLEXIBLE so Play can download in the background after user consent.
 * The required-update page still blocks normal use until installation. A
 * downloaded update is completed automatically, including while backgrounded.
 * IMMEDIATE remains the fallback and is resumed if already in progress.
 * Only updates offered to this Play account are requested; a tester-only
 * latest build is not treated as a company-wide minimum.
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
            updateFlowRunning = false
            if (result.resultCode != Activity.RESULT_OK) {
                // Backed out of Play's screen: the update is still required,
                // so the page stays up with its Update button.
                Log.w(TAG, "Update flow not completed (resultCode=${result.resultCode})")
                emit(InAppUpdateUiState.Available(required = true))
            }
            checkForUpdate()
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
    private var checkingPlay = false
    private var updateFlowRunning = false
    private var completingUpdate = false
    private var destroyed = false

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
                if (!destroyed && info.installStatus() == InstallStatus.DOWNLOADED) installDownloaded()
            }
    }

    fun destroy() {
        destroyed = true
        availabilityJob?.cancel()
        availabilityJob = null
        updateAction = null
        if (listenerRegistered) {
            runCatching { manager.unregisterListener(installListener) }
            listenerRegistered = false
        }
    }

    private fun checkForUpdate() {
        if (destroyed || checkingPlay || updateFlowRunning || completingUpdate) return
        checkingPlay = true
        manager.appUpdateInfo
            .addOnCompleteListener { result ->
                checkingPlay = false
                if (destroyed) return@addOnCompleteListener
                if (result.isSuccessful) {
                    handleUpdateInfo(result.result)
                } else {
                    Log.d(TAG, "appUpdateInfo check failed: ${result.exception?.message}")
                    checkRemotePolicy()
                }
            }
    }

    private fun handleUpdateInfo(info: AppUpdateInfo) {
        val status = info.installStatus()
        val availability = info.updateAvailability()
        when (status) {
            InstallStatus.DOWNLOADED -> {
                installDownloaded()
                return
            }
            InstallStatus.INSTALLING -> {
                emit(InAppUpdateUiState.Installing)
                return
            }
            InstallStatus.PENDING -> {
                emit(InAppUpdateUiState.Preparing)
                return
            }
            InstallStatus.DOWNLOADING -> {
                emit(InAppUpdateUiState.Downloading(
                    downloadProgressPercentage(info.bytesDownloaded(), info.totalBytesToDownload()),
                ))
                return
            }
        }
        when (preferredPlayUpdateType(
            availability,
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
        )) {
            AppUpdateType.IMMEDIATE -> {
                updateAction = UpdateAction.PlayImmediate(info)
                if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    launchPlayUpdate(info, AppUpdateType.IMMEDIATE)
                } else {
                    emit(InAppUpdateUiState.Available(required = true))
                }
            }
            AppUpdateType.FLEXIBLE -> {
                updateAction = UpdateAction.PlayFlexible(info)
                emit(InAppUpdateUiState.Available(required = true))
            }
            else -> {
                if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                    showPlayStoreFallback(info)
                } else {
                    updateAction = null
                    emit(InAppUpdateUiState.Hidden)
                    checkRemotePolicy()
                }
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
            is UpdateAction.PlayImmediate -> launchPlayUpdate(action.info, AppUpdateType.IMMEDIATE)
            is UpdateAction.PlayFlexible -> launchPlayUpdate(action.info, AppUpdateType.FLEXIBLE)
            is UpdateAction.External -> runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
            }.onFailure { Log.w(TAG, "Opening update URL failed: ${it.message}") }
            UpdateAction.Restart -> installDownloaded()
            null -> checkForUpdate()
        }
    }

    /** A downloaded (flexible) update is required: install it now. */
    private fun installDownloaded() {
        if (destroyed || completingUpdate) return
        completingUpdate = true
        updateAction = UpdateAction.Restart
        emit(InAppUpdateUiState.Installing)
        manager.completeUpdate().addOnFailureListener { error ->
            completingUpdate = false
            Log.w(TAG, "Completing downloaded update failed: ${error.message}")
            emit(InAppUpdateUiState.ReadyToRestart)
        }
    }

    private fun emit(state: InAppUpdateUiState) {
        activity.runOnUiThread { if (!destroyed) onUiStateChanged(state) }
    }

    private fun launchPlayUpdate(info: AppUpdateInfo, type: Int) {
        if (destroyed || updateFlowRunning || completingUpdate) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            emit(InAppUpdateUiState.Available(required = true))
            return
        }
        updateFlowRunning = true
        emit(InAppUpdateUiState.Preparing)
        val started = runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(type).build(),
            )
        }.onFailure {
            Log.w(TAG, "Starting Play update failed: ${it.message}")
        }.getOrDefault(false)
        if (!started) {
            updateFlowRunning = false
            showPlayStoreFallback(info)
        }
    }

    private fun showPlayStoreFallback(info: AppUpdateInfo) {
        updateAction = UpdateAction.External(PLAY_STORE_URL)
        emit(InAppUpdateUiState.ExternalRequired("build ${info.availableVersionCode()}"))
    }

    companion object {
        private const val TAG = "InAppUpdate"
        private const val FOREGROUND_CHECK_INTERVAL_MS = 60 * 1000L
        private const val REMOTE_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect"
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
    return installedBuildNumber < minimumBuild
}

internal fun preferredPlayUpdateType(
    availability: Int,
    flexibleAllowed: Boolean,
    immediateAllowed: Boolean,
): Int? = when {
    availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> AppUpdateType.IMMEDIATE
    availability != UpdateAvailability.UPDATE_AVAILABLE -> null
    flexibleAllowed -> AppUpdateType.FLEXIBLE
    immediateAllowed -> AppUpdateType.IMMEDIATE
    else -> null
}
