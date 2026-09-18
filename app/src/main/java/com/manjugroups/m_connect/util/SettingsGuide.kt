package com.manjugroups.m_connect.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Sends staff to a system setting with the exact taps written out, and never
 * leaves them without a way to get there.
 *
 * Android does not let an app open the page for one permission (that needs
 * GRANT_RUNTIME_PERMISSIONS, which only system apps hold), and the App info
 * row highlight is ignored on newer stock Android and on ColorOS. So wherever
 * the exact page cannot be opened directly, this shows the steps first, then:
 *
 * 1. tries each [targets] intent in order (most specific first),
 * 2. falls back to this app's App info page,
 * 3. then to the phone's main Settings,
 * 4. and if even that fails, says so; the steps (which always include the
 *    manual route from the Settings app) stay on screen to follow by hand.
 */
object SettingsGuide {

    fun show(
        context: Context,
        title: String,
        steps: List<String>,
        vararg targets: Intent,
    ) {
        val numbered = steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
        val message = numbered +
            "\n\nIf the page does not open on your phone: open the Settings app → " +
            "Apps → M-connect, then follow the same steps."
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open settings") { _, _ -> open(context, title, message, targets.toList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the first target that exists on this phone; see the class doc. */
    fun open(context: Context, title: String, message: String, targets: List<Intent>) {
        val chain = targets + AppSettingsDeepLink.appInfo(context) + Intent(Settings.ACTION_SETTINGS)
        for (intent in chain) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(context, "Open your phone's Settings app and follow these steps.", Toast.LENGTH_LONG).show()
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Guides for the tracking permissions ─────────────────────────────

    fun location(context: Context) = show(
        context,
        "Turn on Location permission",
        listOf(
            "Tap Permissions",
            "Tap Location",
            "Choose \"Allow all the time\" (or \"Allow only while using the app\")",
            "Turn on \"Use precise location\"",
        ),
        AppSettingsDeepLink.permissions(context),
    )

    fun backgroundLocation(context: Context) = show(
        context,
        "Allow location all the time",
        listOf(
            "Tap Permissions",
            "Tap Location",
            "Choose \"Allow all the time\"",
        ),
        AppSettingsDeepLink.permissions(context),
    )

    fun physicalActivity(context: Context) = show(
        context,
        "Allow physical activity",
        listOf(
            "Tap Permissions",
            "Tap Physical activity",
            "Choose \"Allow\"",
        ),
        AppSettingsDeepLink.permissions(context),
    )

    fun battery(context: Context) = show(
        context,
        "Remove battery restriction",
        listOf(
            "Tap Battery usage (or Battery)",
            "Choose \"Unrestricted\", or turn on \"Allow background activity\"",
        ),
        AppSettingsDeepLink.battery(context),
    )

    fun unusedApp(context: Context, direct: Intent?) = show(
        context,
        "Keep M-connect active",
        listOf(
            "Find \"Manage app if unused\" (also called \"Pause app activity if unused\" or \"Remove permissions if unused\")",
            "Turn it OFF",
        ),
        *listOfNotNull(direct).toTypedArray(),
    )

    fun autostart(context: Context, makerPages: List<Intent>) = show(
        context,
        "Allow auto-start",
        listOf(
            "Find M-connect in the list (or open its App info)",
            "Turn on \"Auto-start\" / \"Auto-launch\" / \"Allow background activity\"",
        ),
        *makerPages.toTypedArray(),
    )

    fun deviceLocation(context: Context) = show(
        context,
        "Turn on Location",
        listOf("Turn on \"Use location\" (Location)"),
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    )

    /** Everything, for the "Open M-connect settings" link on the sheet. */
    fun allTracking(context: Context) = show(
        context,
        "Set up permissions by hand",
        listOf(
            "Permissions → Location → \"Allow all the time\" + \"Use precise location\"",
            "Permissions → Physical activity → \"Allow\"",
            "Battery usage → \"Unrestricted\" / \"Allow background activity\"",
            "Turn OFF \"Manage app if unused\"",
            "Notifications → Allow",
        ),
        AppSettingsDeepLink.appInfo(context, AppSettingsDeepLink.KEY_PERMISSIONS),
    )
}
