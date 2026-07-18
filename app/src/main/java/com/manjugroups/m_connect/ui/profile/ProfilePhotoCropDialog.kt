package com.manjugroups.m_connect.ui.profile

import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.databinding.DialogProfilePhotoCropBinding
import com.manjugroups.m_connect.ui.chat.MediaEditView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen crop dialog for avatars. Forces a 1:1 aspect ratio so the output
 * fits a circular avatar tile.
 *
 * Despite the package, this is shared: the profile editor and the chat group
 * info screen both route their picked image through it. Nothing here is
 * profile-specific — it takes a Uri and hands back a cropped Bitmap.
 */
class ProfilePhotoCropDialog : DialogFragment() {

    fun interface Listener {
        fun onCropApplied(bitmap: Bitmap)
    }

    private var _binding: DialogProfilePhotoCropBinding? = null
    private val binding get() = _binding!!
    private var listener: Listener? = null
    private var sourceUri: Uri? = null

    fun setSource(uri: Uri) {
        sourceUri = uri
    }

    fun setListener(l: Listener) {
        listener = l
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        _binding = DialogProfilePhotoCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cropEditView.cropAspectRatio = 1f
        binding.cropEditView.mode = MediaEditView.Mode.CROP

        val uri = sourceUri ?: run { dismiss(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val bm = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            if (_binding == null) return@launch
            if (bm == null) { dismiss(); return@launch }
            binding.cropEditView.setBitmap(bm)
            // setBitmap clears mode → restore CROP and re-init the rect.
            binding.cropEditView.cropAspectRatio = 1f
            binding.cropEditView.mode = MediaEditView.Mode.CROP
        }

        binding.btnCropClose.setOnClickListener { dismiss() }
        binding.btnCropApply.setOnClickListener {
            binding.cropEditView.applyCrop()
            val result = binding.cropEditView.getResult()
            if (result != null) listener?.onCropApplied(result)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
