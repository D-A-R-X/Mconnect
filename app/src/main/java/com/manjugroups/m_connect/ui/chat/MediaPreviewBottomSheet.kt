package com.manjugroups.m_connect.ui.chat

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
        fun onAddMoreClicked()
        fun onPreviewCancelled()
    }

    private var listener: MediaPreviewListener? = null
    private var attachment: PendingAttachment? = null
    private var isSent = false

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

    override fun onStart() {
        super.onStart()
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
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

        // Intercept system back press/gesture to dismiss preview and return to camera
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                }
            }
        )

        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain)
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon)
        val etCaption = view.findViewById<EditText>(R.id.etPreviewCaption)
        val tvUserName = view.findViewById<TextView>(R.id.tvPreviewUserName)
        val btnSend = view.findViewById<View>(R.id.btnPreviewSend)
        val btnAdd = view.findViewById<View>(R.id.btnPreviewAdd)

        // Set user name from SessionManager
        val session = SessionManager(requireContext())
        tvUserName.text = session.userName ?: "User"

        attachment?.let { media ->
            val isVideo = media.fileType.startsWith("video/")
            // Load media preview using Coil (supports video frames natively with Decoder Factory)
            imgPreview.load(media.uri) {
                crossfade(true)
                placeholder(R.drawable.bg_chat_media_placeholder)
                error(R.drawable.bg_chat_media_placeholder)
                if (isVideo) {
                    decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                }
            }
            imgPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
        }

        btnSend.setOnClickListener {
            val caption = etCaption.text?.toString()?.trim().orEmpty()
            attachment?.let { media ->
                isSent = true
                listener?.onMediaSend(media, caption)
            }
            dismiss()
        }

        btnAdd.setOnClickListener {
            // Dismiss and notify to launch gallery media picker
            listener?.onAddMoreClicked()
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!isSent) {
            listener?.onPreviewCancelled()
        }
    }
}
