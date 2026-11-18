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
 *
 * The requirement is saved on the device ([RequiredUpdateStore]) and shown
 * synchronously when the activity starts and resumes, before any network or
 * Play answer, so closing/reopening the app, starting offline or Play's check
 * briefly saying "no update" can't make the page disappear. It clears only
 * when the installed build reaches the required one, or the server drops its
 * minimum ([RequiredUpdatePolicy]).
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
    private var required: RequiredUpdate? = RequiredUpdateStore.load(activity, BuildConfig.VERSION_CODE)
    private var lastRemoteCheckMs = 0L
    private var checkingPlay = false
    private var updateFlowRunning = false
    private var completingUpdate = false
    private var destroyed = false
    /** Play's last check said there is no update for this account. */
    private var playSaysNoUpdate = false

    /** Register callbacks in onCreate; availability is checked in onResume. */
    fun start() {
        if (!listenerRegistered) {
            manager.registerListener(installListener)
            listenerRegistered = true
        }
        // A saved requirement shows on the very first frame, before any check.
        showStoredRequirement()
    }

    fun onResume() {
        showStoredRequirement()
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
        if (availability == UpdateAvailability.UPDATE_AVAILABLE ||
            availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        ) {
            playSaysNoUpdate = false
            rememberRequirement(
                RequiredUpdatePolicy.withPlayOffer(
                    required,
                    info.availableVersionCode(),
                    PLAY_STORE_URL,
                    BuildConfig.VERSION_CODE,
                    System.currentTimeMillis(),
                ),
            )
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
                    // Play's check often says "no update" for a while after a
                    // reopen (cached / staged rollout). A saved requirement
                    // still stands; only a satisfied or dropped one hides.
                    playSaysNoUpdate = true
                    if (!showStoredRequirement()) {
                        updateAction = null
                        emit(InAppUpdateUiState.Hidden)
                    }
                    checkRemotePolicy()
                }
            }
        }
    }

    private fun checkRemotePolicy() {
        showStoredRequirement()
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
            // Offline / failed answer: nothing changes, the saved requirement stands.
            if (!response.success || destroyed) return@launch
            val server = serverRequirement(response, BuildConfig.VERSION_CODE, System.currentTimeMillis())
            var next = RequiredUpdatePolicy.withServer(required, server, BuildConfig.VERSION_CODE)
            if (playSaysNoUpdate) {
                next = RequiredUpdatePolicy.withPlayNoUpdate(
                    next,
                    serverHasNoMinimum = server == null,
                    installedBuild = BuildConfig.VERSION_CODE,
                    nowMs = System.currentTimeMillis(),
                )
            }
            rememberRequirement(next)
            if (!showStoredRequirement() && updateAction is UpdateAction.External) {
                // The server dropped its minimum and Play has nothing: release the page.
                updateAction = null
                emit(InAppUpdateUiState.Hidden)
            }
        }
    }

    private fun rememberRequirement(next: RequiredUpdate?) {
        if (next == required) return
        required = next
        RequiredUpdateStore.save(activity, next)
    }

    /** Shows the saved requirement (if any); true when the page is up because of it. */
    private fun showStoredRequirement(): Boolean {
        val active = RequiredUpdatePolicy.enforce(required, BuildConfig.VERSION_CODE) ?: return false
        // A live Play flow (download / install / Play button) keeps its own state.
        if (updateAction is UpdateAction.PlayFlexible || updateAction is UpdateAction.PlayImmediate ||
            updateAction == UpdateAction.Restart || updateFlowRunning || completingUpdate
        ) return true
        updateAction = UpdateAction.External(active.url)
        emit(InAppUpdateUiState.ExternalRequired(active.version))
        return true
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
        internal const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect"
    }

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

/**
 * The server's forced minimum as a [RequiredUpdate], or null when this build is
 * allowed. A minimum without a store link still forces the update (Play page).
 */
internal fun serverRequirement(
    policy: MobileAppVersionResponse,
    installedBuildNumber: Int,
    nowMs: Long,
): RequiredUpdate? {
    if (!requiresMandatoryMobileUpdate(policy, installedBuildNumber)) return null
    val build = policy.minimumSupportedBuildNumber ?: policy.latestBuildNumber ?: return null
    val version = (policy.minimumSupportedVersion ?: policy.latestVersion)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "$it ($build)" }
        ?: "build $build"
    return RequiredUpdate(
        build = build,
        version = version,
        url = policy.updateUrl?.trim()?.takeIf { it.isNotEmpty() } ?: InAppUpdateManager.PLAY_STORE_URL,
        fromServer = true,
        fromPlay = false,
        savedAtMs = nowMs,
    )
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
