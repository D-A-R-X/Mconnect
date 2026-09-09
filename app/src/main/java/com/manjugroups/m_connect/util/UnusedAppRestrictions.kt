package com.manjugroups.m_connect.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.fragment.app.FragmentActivity

/**
 * Android's "Manage app if unused" / "Remove permissions if unused" setting
 * (a.k.a. app hibernation + permission auto-revoke) is ON by default on most
 * phones. When it kicks in it force-stops Mconnect and revokes its
 * permissions, which silently breaks background GeoTrack, push delivery and
 * the biometric-punch pipeline — exactly the "app keeps going inactive"
 * symptom field staff hit.
 *
 * This asks the user, once, to switch that off for Mconnect. We can't toggle
 * it programmatically (there's no such permission) — we can only detect it and
 * deep-link to the right system screen, which is what the Jetpack
 * [PackageManagerCompat] / [IntentCompat] helpers do across API levels.
 */
object UnusedAppRestrictions {

    internal data class UiState(
        val visible: Boolean,
        val satisfied: Boolean,
        val restrictionDisabled: Boolean,
    )

    private const val PREFS = "unused_app_restrictions"
    private const val KEY_ASKED = "asked_v1"

    /**
     * If restrictions are currently enabled and we haven't asked before,
     * show a one-time explainer that deep-links to the system setting.
     * Safe to call on every foreground — it self-gates on the async status
     * check and the "already asked" flag, and no-ops on devices/OS versions
     * that don't have the feature.
     */
    fun maybePrompt(activity: FragmentActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return

        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(activity)
        future.addListener(
            {
                if (activity.isFinishing || activity.isDestroyed) return@addListener
                val status = runCatching { future.get() }.getOrNull() ?: return@addListener
                when (status) {
                    // Nothing to do — feature absent, errored, or already off.
                    UnusedAppRestrictionsConstants.ERROR,
                    UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
                    UnusedAppRestrictionsConstants.DISABLED -> Unit
                    // Restrictions are ENABLED (any API-level variant) — prompt.
                    else -> settingsIntent(activity)?.let { intent ->
                        showDialog(activity, prefs, intent)
                    }
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun showDialog(
        activity: FragmentActivity,
        prefs: android.content.SharedPreferences,
        settingsIntent: Intent,
    ) {
        // Don't stack on top of the mandatory background-permissions gate;
        // leave KEY_ASKED unset so we retry on a later foreground.
        if (activity.supportFragmentManager.findFragmentByTag(
                "BackgroundPermissionsGateDialog",
            ) != null
        ) {
            return
        }

        prefs.edit().putBoolean(KEY_ASKED, true).apply()

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Keep Mconnect active")
            .setMessage(
                "Your phone can pause Mconnect and remove its permissions when " +
                    "it's unused, which stops attendance tracking and " +
                    "notifications from working in the background.\n\n" +
                    "On the next screen, turn OFF \"Manage app if unused\" " +
                    "(also called \"Pause app activity if unused\" or " +
                    "\"Remove permissions if unused\") for Mconnect.",
            )
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { activity.startActivity(settingsIntent) }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Returns the vendor/system screen only when Android can actually resolve
     * it. Some Android 11+ OEM builds omit this screen entirely even though the
     * platform API level normally supports app hibernation.
     */
    fun settingsIntent(context: Context): Intent? {
        val intent = runCatching {
            IntentCompat.createManageUnusedAppRestrictionsIntent(
                context,
                context.packageName,
            )
        }.getOrNull() ?: return null
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    internal fun uiState(status: Int?, canOpenSettings: Boolean): UiState {
        val enabled = status == UnusedAppRestrictionsConstants.API_30_BACKPORT ||
            status == UnusedAppRestrictionsConstants.API_30 ||
            status == UnusedAppRestrictionsConstants.API_31
        val disabled = status == UnusedAppRestrictionsConstants.DISABLED
        val supported = enabled || disabled
        val visible = supported && canOpenSettings
        return UiState(
            visible = visible,
            satisfied = !visible || disabled,
            restrictionDisabled = visible && disabled,
        )
    }
}
