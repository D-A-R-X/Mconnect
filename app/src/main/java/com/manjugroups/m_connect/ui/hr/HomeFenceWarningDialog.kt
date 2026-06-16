package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.R

/**
 * Centred-modal popup that fires when the staff taps Clock In while
 * inside their home geofence. Replaces the brief top-of-screen Toast
 * with a full-attention card: illustration + "You are at Home!" headline,
 * a short explainer, and a single "Got It" CTA that dismisses.
 *
 * Cancelable on outside-touch and on the close (×) button. The dialog
 * carries no state; the parent screens just call [show] each time the
 * user mis-taps Clock-In.
 */
class HomeFenceWarningDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
        }
        // Transparent backdrop so the card's rounded corners stay clean
        // — the framework will dim behind it via the dialog window flag.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.dialog_home_fence_warning, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btnHomeFenceClose).setOnClickListener {
            dismissAllowingStateLoss()
        }
        view.findViewById<View>(R.id.btnHomeFenceGotIt).setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Constrain the dialog width to ~88% of the screen — keeps a
        // comfortable margin on every device size and lets the rounded
        // card visibly float instead of stretching edge-to-edge.
        val w = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog?.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        private const val TAG = "HomeFenceWarningDialog"

        /** One-call entry point used by HrDashboardFragment + ClockInAreaFragment. */
        fun show(fm: FragmentManager) {
            // Avoid stacking — if the dialog is already on screen, no-op.
            if (fm.findFragmentByTag(TAG) != null) return
            HomeFenceWarningDialog().show(fm, TAG)
        }
    }
}
