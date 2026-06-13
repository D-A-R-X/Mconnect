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
import android.widget.Toast
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment.PendingAttachment

class MediaPreviewBottomSheet : BottomSheetDialogFragment() {

    interface MediaPreviewListener {
        fun onMediaSend(attachments: List<PendingAttachment>, caption: String)
        fun onAddMoreClicked()
        fun onPreviewCancelled()
    }

    private var listener: MediaPreviewListener? = null
    private val selectedAttachments = mutableListOf<PendingAttachment>()
    private var currentAttachment: PendingAttachment? = null
    private var isSent = false

    private var isMediaListVisible = false
    private val localMediaItems = mutableListOf<LocalPreviewItem>()
    private var localMediaAdapter: LocalPreviewAdapter? = null

    fun setListener(listener: MediaPreviewListener) {
        this.listener = listener
    }

    fun setAttachment(attachment: PendingAttachment) {
        this.selectedAttachments.clear()
        this.selectedAttachments.add(attachment)
        this.currentAttachment = attachment
    }

    fun setAttachments(attachments: List<PendingAttachment>) {
        this.selectedAttachments.clear()
        this.selectedAttachments.addAll(attachments)
        this.currentAttachment = attachments.firstOrNull()
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

        // Load initial preview
        currentAttachment?.let { showAttachmentPreview(it) }

        btnSend.setOnClickListener {
            val caption = etCaption.text?.toString()?.trim().orEmpty()
            if (selectedAttachments.isNotEmpty()) {
                isSent = true
                listener?.onMediaSend(selectedAttachments, caption)
            }
            dismiss()
        }

        btnAdd.setOnClickListener {
            toggleLocalMediaList(view)
        }
    }

    private fun showAttachmentPreview(media: PendingAttachment) {
        currentAttachment = media
        val view = view ?: return
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain) ?: return
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon) ?: return

        val isVideo = media.fileType.startsWith("video/")
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

    private fun toggleLocalMediaList(view: View) {
        val rvList = view.findViewById<RecyclerView>(R.id.rvPreviewLocalMedia) ?: return
        val imgAddIcon = view.findViewById<ImageView>(R.id.imgPreviewAddIcon) ?: return

        if (isMediaListVisible) {
            isMediaListVisible = false
            rvList.visibility = View.GONE
            imgAddIcon.setImageResource(R.drawable.ic_add_plus)
        } else {
            if (hasStoragePermission()) {
                isMediaListVisible = true
                rvList.visibility = View.VISIBLE
                imgAddIcon.setImageResource(R.drawable.ic_sheet_close)
                loadGalleryMedia(rvList)
            } else {
                listener?.onAddMoreClicked()
                dismiss()
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        val context = context ?: return false
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun loadGalleryMedia(recyclerView: RecyclerView) {
        val mediaList = queryGalleryMedia()
        localMediaItems.clear()
        localMediaItems.addAll(mediaList)

        if (localMediaAdapter == null) {
            recyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            localMediaAdapter = LocalPreviewAdapter(localMediaItems) { clickedItem ->
                handleGalleryItemClick(clickedItem)
            }
            recyclerView.adapter = localMediaAdapter
        } else {
            localMediaAdapter?.notifyDataSetChanged()
        }
    }

    private fun handleGalleryItemClick(clickedItem: LocalPreviewItem) {
        val index = localMediaItems.indexOf(clickedItem)
        if (index == -1) return

        val uriStr = clickedItem.uri.toString()
        val existingIndex = selectedAttachments.indexOfFirst { it.uri.toString() == uriStr }

        if (existingIndex != -1) {
            selectedAttachments.removeAt(existingIndex)
            if (currentAttachment?.uri?.toString() == uriStr) {
                if (selectedAttachments.isNotEmpty()) {
                    showAttachmentPreview(selectedAttachments.last())
                } else {
                    dismiss()
                    return
                }
            }
        } else {
            val meta = readAttachmentMeta(clickedItem.uri)
            if (meta != null) {
                val maxSize = 15L * 1024L * 1024L
                if (meta.fileSize > maxSize) {
                    Toast.makeText(requireContext(), "${meta.fileName} is larger than 15 MB", Toast.LENGTH_SHORT).show()
                    return
                }
                selectedAttachments.add(meta)
                showAttachmentPreview(meta)
            }
        }

        // Sync local list selection status
        for (i in localMediaItems.indices) {
            val item = localMediaItems[i]
            val isSel = selectedAttachments.any { it.uri.toString() == item.uri.toString() }
            localMediaItems[i] = item.copy(isSelected = isSel)
        }
        localMediaAdapter?.notifyDataSetChanged()
    }

    private fun queryGalleryMedia(): List<LocalPreviewItem> {
        val list = mutableListOf<LocalPreviewItem>()
        val context = context ?: return list

        val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imagesProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(imagesUri, imagesProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (cursor.moveToNext() && count < 25) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(imagesUri, id)
                    val isSelected = selectedAttachments.any { it.uri.toString() == contentUri.toString() }
                    list.add(LocalPreviewItem(uri = contentUri, isVideo = false, isSelected = isSelected))
                    count++
                }
            }
        }

        val videosUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videosProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )

        runCatching {
            context.contentResolver.query(videosUri, videosProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                var count = 0
                while (cursor.moveToNext() && count < 10) {
                    val id = cursor.getLong(idCol)
                    val durationMs = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                    val durationStr = formatDuration(durationMs)
                    val contentUri = ContentUris.withAppendedId(videosUri, id)
                    val isSelected = selectedAttachments.any { it.uri.toString() == contentUri.toString() }
                    list.add(LocalPreviewItem(uri = contentUri, isVideo = true, durationStr = durationStr, isSelected = isSelected))
                    count++
                }
            }
        }

        return list.sortedByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun readAttachmentMeta(uri: Uri): PendingAttachment? {
        val context = context ?: return null
        val resolver = context.contentResolver
        var name = "File-${System.currentTimeMillis()}"
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        var size = 0L

        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mime == "application/octet-stream" || mime.isBlank()) {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> mime
            }
        }

        return PendingAttachment(uri, name, mime, size)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!isSent) {
            listener?.onPreviewCancelled()
        }
    }

    data class LocalPreviewItem(
        val uri: Uri,
        val isVideo: Boolean,
        val durationStr: String? = null,
        val isSelected: Boolean
    )

    private inner class LocalPreviewAdapter(
        private val items: List<LocalPreviewItem>,
        private val onItemClick: (LocalPreviewItem) -> Unit
    ) : RecyclerView.Adapter<LocalPreviewAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val borderContainer: View = view.findViewById(R.id.borderContainer)
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val badgeSelected: View = view.findViewById(R.id.badgeSelected)
            val videoBadge: View = view.findViewById(R.id.videoBadge)
            val tvVideoDuration: TextView = view.findViewById(R.id.tvVideoDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_local_media, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ivThumbnail.load(item.uri) {
                crossfade(true)
                placeholder(R.drawable.bg_chat_media_placeholder)
                error(R.drawable.bg_chat_media_placeholder)
            }

            if (item.isSelected) {
                holder.borderContainer.background = androidx.core.content.ContextCompat.getDrawable(
                    holder.itemView.context, R.drawable.bg_chat_local_media_border
                )
                holder.badgeSelected.visibility = View.VISIBLE
            } else {
                holder.borderContainer.background = null
                holder.badgeSelected.visibility = View.GONE
            }

            if (item.isVideo) {
                holder.videoBadge.visibility = View.VISIBLE
                holder.tvVideoDuration.text = item.durationStr ?: "0:00"
            } else {
                holder.videoBadge.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
