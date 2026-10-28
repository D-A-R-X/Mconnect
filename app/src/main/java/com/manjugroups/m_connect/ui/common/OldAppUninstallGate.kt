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
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * Non-dismissible notice while the old sideloaded Mconnect is still installed.
 *
 * Builds before June 2026 used the app id `com.manjugroups.m_connect`, so they
 * install next to the Play app. Both log in and both run tracking under
 * different device ids, so the staff member drops off "active" on Geo Track
 * Live. The app cannot remove another app itself: the button opens Android's
 * own "Uninstall this app?" dialog, and the notice closes once it is gone.
 */
class OldAppUninstallGate : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "OldAppUninstallGate"
        const val OLD_PACKAGE = "com.manjugroups.m_connect"

        fun isOldAppInstalled(ctx: Context): Boolean = try {
            ctx.packageManager.getPackageInfo(OLD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /**
         * Shows the notice while the old app is installed and closes it once
         * it is gone. Returns true while the old app is still on the phone, so
         * callers can hold back other startup sheets until it is removed.
         */
        fun showIfNeeded(fm: FragmentManager, ctx: Context): Boolean {
            val existing = fm.findFragmentByTag(TAG) as? OldAppUninstallGate
            if (!isOldAppInstalled(ctx)) {
                existing?.dismissAllowingStateLoss()
                return false
            }
            if (existing == null && !fm.isStateSaved) {
                runCatching { OldAppUninstallGate().show(fm, TAG) }
            }
            return true
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
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
        view.findViewById<View>(R.id.btnUninstallOldApp).setOnClickListener { openUninstall() }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system dialog: close once the old app is gone.
        if (!isOldAppInstalled(requireContext())) dismissAllowingStateLoss()
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
