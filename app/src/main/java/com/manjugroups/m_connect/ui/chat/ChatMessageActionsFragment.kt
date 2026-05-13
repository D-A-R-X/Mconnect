package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.databinding.FragmentChatMessageActionsBinding

class ChatMessageActionsFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentChatMessageActionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatMessageActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val body = arguments?.getString("body") ?: ""
        binding.tvMessagePreview.text = body

        binding.btnReply.setOnClickListener { dismiss() }
        binding.btnForward.setOnClickListener { dismiss() }
        binding.btnCopy.setOnClickListener { dismiss() }
        binding.btnDelete.setOnClickListener { dismiss() }
        
        listOf(binding.reactFire, binding.reactClap, binding.reactHeart, binding.reactSmile, binding.reactAngry, binding.reactThumb).forEach { 
            it.setOnClickListener { dismiss() }
        }
    }

    companion object {
        fun newInstance(body: String) = ChatMessageActionsFragment().apply {
            arguments = Bundle().apply {
                putString("body", body)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
