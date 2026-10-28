package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.ApiService

/**
 * Non-dismissible notice while the old sideloaded Mconnect is still installed.
 *
 * Builds before June 2026 used the app id `com.manjugroups.m_connect`, so they
 * install next to the Play app. Both log in and both run tracking under
 * different device ids, so the staff member drops off "active" on Geo Track
 * Live. The app cannot remove another app itself: the button opens Android's
 * own "Uninstall this app?" dialog, and the notice closes once it is gone.
 *
 * Web Settings → Mobile → Old Mconnect app can turn the notice off, and allow
 * "Can't uninstall? Continue" after a failed attempt (company-managed phones
 * that forbid uninstalling). The last values read are kept on the phone, so a
 * failed fetch never changes behaviour.
 */
class OldAppUninstallGate : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "OldAppUninstallGate"
        const val OLD_PACKAGE = "com.manjugroups.m_connect"
        private const val PREFS = "old_app_prompt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ALLOW_CONTINUE = "allow_continue"
        private const val STATE_ATTEMPTED = "attempted"

        /** Chose "Continue" this app run; asked again on the next app start. */
        @Volatile
        private var continuedThisRun = false

        fun isOldAppInstalled(ctx: Context): Boolean = try {
            ctx.packageManager.getPackageInfo(OLD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, true)

        private fun allowsContinue(ctx: Context) = prefs(ctx).getBoolean(KEY_ALLOW_CONTINUE, true)

        /** Whether the notice holds this phone: old app present, notice on, not continued. */
        fun isBlocking(ctx: Context): Boolean =
            !continuedThisRun && isEnabled(ctx) && isOldAppInstalled(ctx)

        /**
         * Shows the notice while it should hold this phone and closes it
         * otherwise. Returns true while it is holding the phone, so callers
         * can hold back other startup sheets.
         */
        fun showIfNeeded(fm: FragmentManager, ctx: Context): Boolean {
            val existing = fm.findFragmentByTag(TAG) as? OldAppUninstallGate
            if (!isBlocking(ctx)) {
                existing?.dismissAllowingStateLoss()
                return false
            }
            if (existing == null && !fm.isStateSaved) {
                runCatching { OldAppUninstallGate().show(fm, TAG) }
            }
            return true
        }

        /**
         * Reads the Settings → Mobile switches (only on phones that have the
         * old app). Returns true when they were read.
         */
        suspend fun refreshPolicy(ctx: Context, api: ApiService = ApiService.create()): Boolean {
            if (!isOldAppInstalled(ctx)) return false
            val response = runCatching {
                api.getMobileAppVersion(
                    platform = "android",
                    currentVersion = BuildConfig.VERSION_NAME,
                    buildNumber = BuildConfig.VERSION_CODE,
                    appVersionHeader = BuildConfig.VERSION_NAME,
                    appBuildHeader = BuildConfig.VERSION_CODE,
                )
            }.getOrNull()?.takeIf { it.success } ?: return false
            prefs(ctx).edit()
                .putBoolean(KEY_ENABLED, response.oldAppPromptEnabled ?: true)
                .putBoolean(KEY_ALLOW_CONTINUE, response.oldAppPromptAllowContinue ?: true)
                .apply()
            return true
        }
    }

    /** Tapped Uninstall this time the notice was shown. */
    private var attempted = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        attempted = savedInstanceState?.getBoolean(STATE_ATTEMPTED) ?: false
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        isCancelable = false
        dialog.setOnShowListener { shown ->
            (shown as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { sheet ->
                    sheet.setBackgroundResource(android.R.color.transparent)
                    BottomSheetBehavior.from(sheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                        isDraggable = false
                    }
                }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_old_app_uninstall_gate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnUninstallOldApp).setOnClickListener {
            attempted = true
            openUninstall()
        }
        view.findViewById<View>(R.id.btnContinueWithOldApp).setOnClickListener {
            continuedThisRun = true
            dismissAllowingStateLoss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ATTEMPTED, attempted)
    }

    override fun onResume() {
        super.onResume()
        // Back from the system dialog: close once the old app is gone (or the
        // notice was switched off), else offer Continue when allowed.
        if (!isBlocking(requireContext())) {
            dismissAllowingStateLoss()
            return
        }
        val canContinue = attempted && allowsContinue(requireContext())
        view?.findViewById<View>(R.id.btnContinueWithOldApp)?.visibility =
            if (canContinue) View.VISIBLE else View.GONE
        if (canContinue) {
            view?.findViewById<TextView>(R.id.tvOldAppHint)?.text =
                "Couldn't uninstall it? Tell HR, and continue for now. You'll be asked again next time."
        }
    }

    private fun openUninstall() {
        val uri = Uri.parse("package:$OLD_PACKAGE")
        try {
            startActivity(Intent(Intent.ACTION_DELETE, uri))
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds lack the uninstall screen: the old app's settings
            // page has an Uninstall button too.
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
        } catch (_: SecurityException) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
        }
    }
}
