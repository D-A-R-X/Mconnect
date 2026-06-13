package com.manjugroups.m_connect.ui.chat

import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment.PendingAttachment

class MediaPreviewBottomSheet : BottomSheetDialogFragment() {

    interface MediaPreviewListener {
        fun onMediaSend(attachment: PendingAttachment, caption: String)
    }

    private var listener: MediaPreviewListener? = null
    private var attachment: PendingAttachment? = null

    fun setListener(listener: MediaPreviewListener) {
        this.listener = listener
    }

    fun setAttachment(attachment: PendingAttachment) {
        this.attachment = attachment
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_media_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain)
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon)
        val etCaption = view.findViewById<EditText>(R.id.etPreviewCaption)
        val tvUserName = view.findViewById<TextView>(R.id.tvPreviewUserName)
        val btnSend = view.findViewById<View>(R.id.btnPreviewSend)

        // Set user name from SessionManager
        val session = SessionManager(requireContext())
        tvUserName.text = session.userName ?: "User"

        attachment?.let { media ->
            // Load media preview using Coil (supports video frames natively)
            imgPreview.load(media.uri)

            val isVideo = media.fileType.startsWith("video/")
            imgPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
        }

        btnSend.setOnClickListener {
            val caption = etCaption.text?.toString()?.trim().orEmpty()
            attachment?.let { media ->
                listener?.onMediaSend(media, caption)
            }
            dismiss()
        }
    }
}
