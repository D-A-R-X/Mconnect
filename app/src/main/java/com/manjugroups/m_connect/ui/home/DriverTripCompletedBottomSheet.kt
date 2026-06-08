package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import java.io.File

class DriverTripCompletedBottomSheet : BottomSheetDialogFragment() {

    private lateinit var session: SessionManager
    private var visitId: String = ""

    private lateinit var tvStartTripTime: TextView
    private lateinit var tvStartKmBadge: TextView
    private lateinit var ivStartPhoto: ImageView
    private lateinit var cardStartPhoto: View
    private lateinit var btnExpandStartPhoto: View

    private lateinit var tvEndTripTime: TextView
    private lateinit var tvEndKmBadge: TextView
    private lateinit var ivEndPhoto: ImageView
    private lateinit var cardEndPhoto: View
    private lateinit var btnExpandEndPhoto: View

    private lateinit var tvTotalDistance: TextView
    private lateinit var btnClose: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_driver_trip_completed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        visitId = requireArguments().getString(ARG_VISIT_ID).orEmpty()

        tvStartTripTime = view.findViewById(R.id.tvStartTripTime)
        tvStartKmBadge = view.findViewById(R.id.tvStartKmBadge)
        ivStartPhoto = view.findViewById(R.id.ivStartPhoto)
        cardStartPhoto = view.findViewById(R.id.cardStartPhoto)
        btnExpandStartPhoto = view.findViewById(R.id.btnExpandStartPhoto)

        tvEndTripTime = view.findViewById(R.id.tvEndTripTime)
        tvEndKmBadge = view.findViewById(R.id.tvEndKmBadge)
        ivEndPhoto = view.findViewById(R.id.ivEndPhoto)
        cardEndPhoto = view.findViewById(R.id.cardEndPhoto)
        btnExpandEndPhoto = view.findViewById(R.id.btnExpandEndPhoto)

        tvTotalDistance = view.findViewById(R.id.tvTotalDistance)
        btnClose = view.findViewById(R.id.btnClose)

        val tvCompletedSubtitle = view.findViewById<TextView>(R.id.tvCompletedSubtitle)
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val firstName = rawName.split(" ").firstOrNull()?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "User"
        tvCompletedSubtitle.text = "Great job, $firstName! You've completed this trip."

        val trip = session.getDriverTrip(visitId)
        if (trip != null) {
            tvStartTripTime.text = trip.startTime
            tvStartKmBadge.text = "${trip.startKm} Km"
            if (trip.startImage.isNotEmpty()) {
                val file = File(trip.startImage)
                if (file.exists()) {
                    ivStartPhoto.imageTintList = null
                    ivStartPhoto.clearColorFilter()
                    ivStartPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivStartPhoto.load(file)
                    btnExpandStartPhoto.visibility = View.VISIBLE
                    cardStartPhoto.setOnClickListener {
                        showPhotoPreview(trip.startImage)
                    }
                } else {
                    ivStartPhoto.scaleType = ImageView.ScaleType.CENTER
                    ivStartPhoto.setImageResource(R.drawable.ic_image_outline)
                    ivStartPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                    btnExpandStartPhoto.visibility = View.GONE
                    cardStartPhoto.setOnClickListener(null)
                }
            } else {
                ivStartPhoto.scaleType = ImageView.ScaleType.CENTER
                ivStartPhoto.setImageResource(R.drawable.ic_image_outline)
                ivStartPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                btnExpandStartPhoto.visibility = View.GONE
                cardStartPhoto.setOnClickListener(null)
            }

            tvEndTripTime.text = trip.endTime
            tvEndKmBadge.text = "${trip.endKm} Km"
            if (trip.endImage.isNotEmpty()) {
                val file = File(trip.endImage)
                if (file.exists()) {
                    ivEndPhoto.imageTintList = null
                    ivEndPhoto.clearColorFilter()
                    ivEndPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivEndPhoto.load(file)
                    btnExpandEndPhoto.visibility = View.VISIBLE
                    cardEndPhoto.setOnClickListener {
                        showPhotoPreview(trip.endImage)
                    }
                } else {
                    ivEndPhoto.scaleType = ImageView.ScaleType.CENTER
                    ivEndPhoto.setImageResource(R.drawable.ic_image_outline)
                    ivEndPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                    btnExpandEndPhoto.visibility = View.GONE
                    cardEndPhoto.setOnClickListener(null)
                }
            } else {
                ivEndPhoto.scaleType = ImageView.ScaleType.CENTER
                ivEndPhoto.setImageResource(R.drawable.ic_image_outline)
                ivEndPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                btnExpandEndPhoto.visibility = View.GONE
                cardEndPhoto.setOnClickListener(null)
            }

            tvTotalDistance.text = "${trip.totalDistance} Km"
        } else {
            tvStartTripTime.text = "—"
            tvStartKmBadge.text = "— Km"
            ivStartPhoto.scaleType = ImageView.ScaleType.CENTER
            ivStartPhoto.setImageResource(R.drawable.ic_image_outline)
            ivStartPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
            btnExpandStartPhoto.visibility = View.GONE
            cardStartPhoto.setOnClickListener(null)

            tvEndTripTime.text = "—"
            tvEndKmBadge.text = "— Km"
            ivEndPhoto.scaleType = ImageView.ScaleType.CENTER
            ivEndPhoto.setImageResource(R.drawable.ic_image_outline)
            ivEndPhoto.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
            btnExpandEndPhoto.visibility = View.GONE
            cardEndPhoto.setOnClickListener(null)

            tvTotalDistance.text = "0.0 Km"
        }

        btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun showPhotoPreview(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) return

        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_preview, null)
        val imageView = view.findViewById<ImageView>(R.id.ivPreview)
        val closeBtn = view.findViewById<View>(R.id.btnPreviewClose)

        imageView.load(file)
        val dialog = builder.setView(view).create()
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    companion object {
        private const val ARG_VISIT_ID = "arg_visit_id"

        fun newInstance(visitId: String): DriverTripCompletedBottomSheet {
            return DriverTripCompletedBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, visitId)
                }
            }
        }
    }
}
