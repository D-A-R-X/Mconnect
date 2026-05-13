package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentChatContactInfoBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

class ChatContactInfoFragment : Fragment() {

    companion object {
        fun newInstance(
            channelId: String?,
            conversationId: String?,
            title: String,
            otherStaffId: String?
        ) = ChatContactInfoFragment().apply {
            arguments = Bundle().apply {
                putString("channelId", channelId)
                putString("conversationId", conversationId)
                putString("title", title)
                putString("otherStaffId", otherStaffId)
            }
        }
    }

    private var _binding: FragmentChatContactInfoBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()
    private var hasLoadedData = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatContactInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        val title = arguments?.getString("title").orEmpty().ifBlank { "Chat" }
        render(
            title = title,
            about = "Loading details..."
        )

        val channelId = arguments?.getString("channelId")
        val conversationId = arguments?.getString("conversationId")
        val otherStaffId = arguments?.getString("otherStaffId")

        binding.btnMessage.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnCreateGroup.setOnClickListener {
            toast("Create group feature coming soon")
        }

        binding.rowMedia.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatMediaFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commit()
        }
        binding.rowSearch.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatSearchFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commit()
        }

        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)

        viewLifecycleOwner.lifecycleScope.launch {
            when {
                channelId != null -> loadChannelInfo(channelId, title)
                conversationId != null -> loadConversationInfo(conversationId, otherStaffId, title)
                else -> render(title, "Conversation details are not available yet.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private suspend fun loadChannelInfo(channelId: String, fallbackTitle: String) {
        runCatching {
            api.getChannel(requireSession(), channelId).channel
        }.onSuccess { channel ->
            val title = channel?.name?.ifBlank { null } ?: fallbackTitle
            val memberCount = channel?.memberCount ?: 0
            val type = channel?.type?.replaceFirstChar { it.uppercase() } ?: "Channel"
            
            binding.tvCompany.text = type
            binding.tvRole.text = if (memberCount > 0) "$memberCount members" else ""
            
            val about = channel?.description?.ifBlank { null }
                ?: "Team updates and discussions live here."
            render(title, about)
        }.onFailure {
            render(fallbackTitle, "Channel details are not available right now.")
        }
    }

    private suspend fun loadConversationInfo(
        conversationId: String,
        otherStaffId: String?,
        fallbackTitle: String
    ) {
        runCatching {
            val conversation = api.getConversation(requireSession(), conversationId).conversation
            val contactId = otherStaffId
                ?: conversation?.participants?.firstOrNull { it.id != null && it.id != com.manjugroups.m_connect.auth.SessionManager(requireContext()).staffId }?.id
                ?: conversation?.participants?.firstOrNull()?.id
            val staff = contactId?.let { api.getStaffDetail(requireSession(), it).staff }
            conversation to staff
        }.onSuccess { (conversation, staff) ->
            val title = staff?.name?.ifBlank { null }
                ?: conversation?.displayName?.ifBlank { null }
                ?: fallbackTitle
            
            binding.tvCompany.text = staff?.company ?: "Stark Industries"
            binding.tvRole.text = staff?.designation ?: staff?.role ?: "Senior Design Manager"
            binding.tvPhone.text = staff?.phone ?: "+1 (555) 123-4567"
            binding.tvEmail.text = staff?.email ?: "alicia.rochefort@starkindustries.com"
            binding.tvAddress.text = staff?.address ?: "Ashok Nagar Main Road"

            val about = staff?.department ?: "Start a conversation and keep your project communication in one place."
            render(title, about)
        }.onFailure {
            render(fallbackTitle, "Contact details are not available right now.")
        }
    }

    private fun render(title: String, about: String) {
        if (_binding == null) return
        if (!hasLoadedData) {
            hasLoadedData = true
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
        }
        val initials = title.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "C" }

        binding.tvAvatar.text = initials
        binding.tvTitle.text = title
        binding.tvAbout.text = about
    }

    private fun requireSession(): String {
        return com.manjugroups.m_connect.auth.SessionManager(requireContext()).bearerToken
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onDestroyView()
        _binding = null
    }
}
