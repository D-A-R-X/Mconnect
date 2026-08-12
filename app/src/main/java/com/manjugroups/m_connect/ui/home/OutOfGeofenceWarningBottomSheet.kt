package com.manjugroups.m_connect.ui.home

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

/**
 * Out-of-geofence completion warning sheet. Shown when the field staff swipes
 * to complete but their fresh GPS fix is well away from the client's saved
 * location. Captures a REQUIRED reason (surfaced to the approving GM) and lets
 * them Cancel or Complete. [onComplete] fires with the reason; [onCancel] fires
 * on any non-complete dismissal (Cancel, back, tap-outside).
 */
class OutOfGeofenceWarningBottomSheet : BottomSheetDialogFragment() {

    var onComplete: ((reason: String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    private var completed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_out_of_geofence_warning, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val distanceLabel = arguments?.getString(ARG_DISTANCE)?.trim().orEmpty()
        view.findViewById<TextView>(R.id.tvGeofenceMessage).text =
            (if (distanceLabel.isNotEmpty()) {
                "You appear to be $distanceLabel from the client's saved location. "
            } else {
                "You appear to be away from the client's saved location. "
            }) + "Add a reason and Complete — it will need your GM's approval."

        val reason = view.findViewById<EditText>(R.id.etGeofenceReason)
        val error = view.findViewById<TextView>(R.id.tvGeofenceError)

        view.findViewById<View>(R.id.btnGeofenceCancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnGeofenceComplete).setOnClickListener {
            val text = reason.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            error.visibility = View.GONE
            completed = true
            onComplete?.invoke(text)
            dismissAllowingStateLoss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!completed) onCancel?.invoke()
        super.onDismiss(dialog)
    }

    companion object {
        private const val ARG_DISTANCE = "distance"

        fun newInstance(distanceLabel: String): OutOfGeofenceWarningBottomSheet =
            OutOfGeofenceWarningBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_DISTANCE, distanceLabel) }
            }
    }
}
