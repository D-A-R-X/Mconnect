package com.manjugroups.m_connect.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Intents that land on the exact system setting a staff member has to change,
 * with that row highlighted, instead of the top of "App info".
 *
 * Android has no public intent for "this app's Location permission" or "this
 * app's battery page". What it does have is the Settings highlight contract:
 * App info opened with `:settings:fragment_args_key` scrolls to the preference
 * with that key and flashes it. The keys below are AOSP's App info preference
 * keys. Phones whose Settings ignore the extra (some OEM skins) still open on
 * this app's App info page, so the fallback is never worse than before; each
 * caller also shows a one-line "where to tap" toast for that case.
 */
object AppSettingsDeepLink {
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

    /** App info row keys (AOSP `app_info_settings.xml`). */
    const val KEY_PERMISSIONS = "permission_settings"
    const val KEY_BATTERY = "battery"
    const val KEY_NOTIFICATIONS = "notification_settings"
    const val KEY_UNUSED_APP = "hibernation_switch"

    /** This app's App info page, scrolled to and highlighting [highlightKey]. */
    fun appInfo(context: Context, highlightKey: String? = null): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .also { if (highlightKey != null) highlight(it, highlightKey) }

    /** App info → Permissions highlighted (location, physical activity, camera…). */
    fun permissions(context: Context): Intent = appInfo(context, KEY_PERMISSIONS)

    /**
     * Battery. The direct "Allow / Deny" dialog when the phone has it; this is
     * the fallback, App info → Battery highlighted — not the phone-wide list of
     * every app's optimisation that the old fallback opened.
     */
    fun battery(context: Context): Intent = appInfo(context, KEY_BATTERY)

    /** This app's notification page itself (no App info hop). */
    fun notifications(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appInfo(context, KEY_NOTIFICATIONS)
        }

    /**
     * Adds the highlight to an intent that opens App info, e.g. the "Manage app
     * if unused" intent, which on Android 12+ is App info itself.
     */
    fun highlight(intent: Intent, key: String): Intent {
        intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        return intent
    }
}
