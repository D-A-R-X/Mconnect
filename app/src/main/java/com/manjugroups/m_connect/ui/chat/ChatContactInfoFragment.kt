package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentChatContactInfoBinding
import com.manjugroups.m_connect.network.ApiService
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
            meta = "Loading details...",
            about = "Loading details..."
        )

        val channelId = arguments?.getString("channelId")
        val conversationId = arguments?.getString("conversationId")
        val otherStaffId = arguments?.getString("otherStaffId")

        // Wire Media + Search rows to the new fragments.
        view.findViewById<View>(R.id.rowMedia)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatMediaFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commit()
        }
        view.findViewById<View>(R.id.rowSearch)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatSearchFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commit()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            when {
                channelId != null -> loadChannelInfo(channelId, title)
                conversationId != null -> loadConversationInfo(conversationId, otherStaffId, title)
                else -> render(title, "Chat", "Conversation details are not available yet.")
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
            val meta = buildString {
                append(channel?.type?.replaceFirstChar { it.uppercase() } ?: "Channel")
                if (memberCount > 0) {
                    append(" • ")
                    append(memberCount)
                    append(" members")
                }
            }
            val about = channel?.description?.ifBlank { null }
                ?: "Team updates and discussions live here."
            render(title, meta, about)
        }.onFailure {
            render(fallbackTitle, "Channel", "Channel details are not available right now.")
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
                ?: conversation?.participants?.firstOrNull()?.id
            val staff = contactId?.let { api.getStaffDetail(requireSession(), it).staff }
            conversation to staff
        }.onSuccess { (conversation, staff) ->
            val title = staff?.name?.ifBlank { null }
                ?: conversation?.displayName?.ifBlank { null }
                ?: fallbackTitle
            val meta = listOfNotNull(
                staff?.phone,
                staff?.designation,
                staff?.department
            ).joinToString(" • ").ifBlank { "Direct message" }
            val about = listOfNotNull(
                staff?.company,
                staff?.branch,
                staff?.email
            ).joinToString(" • ").ifBlank {
                "Start a conversation and keep your project communication in one place."
            }
            render(title, meta, about)
        }.onFailure {
            render(fallbackTitle, "Direct message", "Contact details are not available right now.")
        }
    }

    private fun render(title: String, meta: String, about: String) {
        if (_binding == null) return
        val initials = title.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "C" }

        binding.tvAvatar.text = initials
        binding.tvTitle.text = title
        binding.tvMeta.text = meta
        binding.tvAbout.text = about
    }

    private fun requireSession(): String {
        return com.manjugroups.m_connect.auth.SessionManager(requireContext()).bearerToken
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onDestroyView()
        _binding = null
    }
}
