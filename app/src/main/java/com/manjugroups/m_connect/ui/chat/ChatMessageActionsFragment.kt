package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.manjugroups.m_connect.databinding.FragmentChatMessageActionsBinding

import com.manjugroups.m_connect.R

class ChatMessageActionsFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ChatMessageActionsDialogTheme)
    }


    interface Callback {
        fun onReply(messageId: String)
        fun onReact(messageId: String, emoji: String)
        fun onCopy(text: String)
        fun onDelete(messageId: String)
        fun onForward(messageId: String)
        fun onInfo(messageId: String) {}
        fun onSelectMore(messageId: String) {}
    }

    private var _binding: FragmentChatMessageActionsBinding? = null
    private val binding get() = _binding!!
    private var callback: Callback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatMessageActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val body = arguments?.getString("body") ?: ""
        val messageId = arguments?.getString("messageId") ?: ""
        binding.tvMessagePreview.text = body

        binding.btnReply.setOnClickListener {
            val id = arguments?.getString("messageId") ?: ""
            callback?.onReply(id)
            dismiss()
        }
        binding.btnForward.setOnClickListener {
            callback?.onForward(messageId)
            dismiss()
        }
        binding.btnCopy.setOnClickListener {
            callback?.onCopy(body)
            dismiss()
        }
        binding.btnDelete.setOnClickListener {
            callback?.onDelete(messageId)
            dismiss()
        }
        
        val reactions = listOf(
            binding.reactFire to "🔥",
            binding.reactClap to "👏",
            binding.reactHeart to "❤️",
            binding.reactSmile to "😊",
            binding.reactAngry to "😫",
            binding.reactThumb to "👍"
        )
        
        reactions.forEach { (view, emoji) ->
            view.setOnClickListener {
                callback?.onReact(messageId, emoji)
                dismiss()
            }
        }
    }

    fun setCallback(cb: Callback) {
        this.callback = cb
    }

    companion object {
        fun newInstance(messageId: String, body: String) = ChatMessageActionsFragment().apply {
            arguments = Bundle().apply {
                putString("messageId", messageId)
                putString("body", body)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setElevation(0f)
            }
            val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
