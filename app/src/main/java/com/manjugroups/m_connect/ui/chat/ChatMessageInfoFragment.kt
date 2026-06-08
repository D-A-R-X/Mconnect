package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentChatMessageInfoBinding
import com.manjugroups.m_connect.databinding.ItemChatMessageInfoRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageInfoFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentChatMessageInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatMessageInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        val body = args.getString(ARG_BODY).orEmpty()
        val sentMillis = args.getLong(ARG_SENT_MILLIS, 0L)
        val senderName = args.getString(ARG_SENDER)
        val isMine = args.getBoolean(ARG_IS_MINE, false)
        val isEdited = args.getBoolean(ARG_IS_EDITED, false)
        val replyCount = args.getInt(ARG_REPLY_COUNT, 0)
        val attachmentCount = args.getInt(ARG_ATTACHMENT_COUNT, 0)

        binding.tvInfoBody.text = body.ifBlank { "(no text)" }
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        binding.tvInfoBubbleTime.text = if (sentMillis > 0) timeFmt.format(Date(sentMillis)) else ""

        if (!isMine) {
            binding.infoBubble.setBackgroundResource(R.drawable.bg_chat_bubble_received)
            val onSurface = ContextCompat.getColor(requireContext(), R.color.chat_text_primary)
            val onSurfaceMuted = ContextCompat.getColor(requireContext(), R.color.chat_text_secondary)
            binding.tvInfoBody.setTextColor(onSurface)
            binding.tvInfoBubbleTime.setTextColor(onSurfaceMuted)
            (binding.infoBubble.parent as? View)?.let {
                val lp = binding.infoBubble.layoutParams as android.widget.FrameLayout.LayoutParams
                lp.gravity = android.view.Gravity.START
                binding.infoBubble.layoutParams = lp
            }
        }

        val fullFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        if (sentMillis > 0) {
            addStatusRow(
                container = binding.infoStatusContainer,
                iconRes = R.drawable.ic_chat_check_double,
                iconTint = 0xFF98A2B3.toInt(),
                label = "Sent",
                value = fullFmt.format(Date(sentMillis))
            )
        }

        if (!senderName.isNullOrBlank()) {
            addStatusRow(
                container = binding.infoMetaContainer,
                iconRes = R.drawable.ic_chat_users,
                iconTint = 0xFF98A2B3.toInt(),
                label = if (isMine) "Sent by you" else "From",
                value = senderName
            )
        }

        if (isEdited) {
            addStatusRow(
                container = binding.infoMetaContainer,
                iconRes = R.drawable.ic_chat_reply,
                iconTint = 0xFF98A2B3.toInt(),
                label = "Edited",
                value = "Last edited timestamp not available"
            )
        }

        if (replyCount > 0) {
            addStatusRow(
                container = binding.infoMetaContainer,
                iconRes = R.drawable.ic_chat_reply,
                iconTint = 0xFF98A2B3.toInt(),
                label = "Replies",
                value = replyCount.toString()
            )
        }

        if (attachmentCount > 0) {
            addStatusRow(
                container = binding.infoMetaContainer,
                iconRes = R.drawable.ic_chat_attach,
                iconTint = 0xFF98A2B3.toInt(),
                label = "Attachments",
                value = attachmentCount.toString()
            )
        }
    }

    private fun addStatusRow(
        container: LinearLayout,
        iconRes: Int,
        iconTint: Int,
        label: String,
        value: String
    ) {
        val rowBinding = ItemChatMessageInfoRowBinding.inflate(
            LayoutInflater.from(container.context), container, false
        )
        rowBinding.ivInfoRowIcon.setImageResource(iconRes)
        rowBinding.ivInfoRowIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        rowBinding.tvInfoRowLabel.text = label
        rowBinding.tvInfoRowValue.text = value
        container.addView(rowBinding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_BODY = "body"
        private const val ARG_SENT_MILLIS = "sentMillis"
        private const val ARG_SENDER = "sender"
        private const val ARG_IS_MINE = "isMine"
        private const val ARG_IS_EDITED = "isEdited"
        private const val ARG_REPLY_COUNT = "replyCount"
        private const val ARG_ATTACHMENT_COUNT = "attachmentCount"

        fun newInstance(
            body: String,
            sentMillis: Long,
            sender: String?,
            isMine: Boolean,
            isEdited: Boolean,
            replyCount: Int,
            attachmentCount: Int
        ) = ChatMessageInfoFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_BODY, body)
                putLong(ARG_SENT_MILLIS, sentMillis)
                putString(ARG_SENDER, sender)
                putBoolean(ARG_IS_MINE, isMine)
                putBoolean(ARG_IS_EDITED, isEdited)
                putInt(ARG_REPLY_COUNT, replyCount)
                putInt(ARG_ATTACHMENT_COUNT, attachmentCount)
            }
        }
    }
}
