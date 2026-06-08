package com.manjugroups.m_connect.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentChatContactInfoBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
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
        binding.avatarContainer.clipToOutline = true
        binding.btnBack.setOnClickListener { navigateUp() }

        val title = arguments?.getString("title").orEmpty().ifBlank { "Chat" }

        val channelId = arguments?.getString("channelId")
        val conversationId = arguments?.getString("conversationId")
        val otherStaffId = arguments?.getString("otherStaffId")

        binding.tvHeaderTitle.text = title
        binding.tvHeaderSubtitle.text = "Last seen recently"

        binding.btnMessage.setOnClickListener { navigateUp() }
        binding.btnCreateGroup.setOnClickListener {
            toast("Create group feature coming soon")
        }

        setupMuteRow(channelId, conversationId)
        
        binding.rowMedia.setOnClickListener {
            val subtitle = binding.tvHeaderSubtitle.text?.toString().orEmpty()
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatMediaFragment.newInstance(channelId, conversationId, title, subtitle)
                )
                .addToBackStack(null)
                .commit()
        }
        
        // Search button in header behaves same as search row
        binding.btnSearch.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatSearchFragment.newInstance(channelId, conversationId, title)
                )
                .addToBackStack(null)
                .commit()
        }

        // Phone button in header dial digits
        binding.btnPhone.setOnClickListener {
            dialPhone()
        }

        // Hydrate from local cache before showing skeleton so the page paints
        // instantly when re-entering the contact info screen.
        val hydrated = hydrateFromCache(channelId, conversationId, otherStaffId, title)
        if (!hydrated) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.GONE
        } else {
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            when {
                channelId != null -> loadChannelInfo(channelId, title)
                conversationId != null -> loadConversationInfo(conversationId, otherStaffId, title)
                else -> {
                    SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                    binding.contentScroll.visibility = View.VISIBLE
                    render(title, "Conversation details are not available yet.")
                }
            }
        }
    }

    private fun dialPhone() {
        val phone = binding.tvPhone.text?.toString()
        if (!phone.isNullOrBlank() && phone != "+1 (555) 123-4567") {
            val digits = phone.filter { it.isDigit() || it == '+' }
            runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
            }
        }
    }

    private fun hydrateFromCache(
        channelId: String?,
        conversationId: String?,
        otherStaffId: String?,
        fallbackTitle: String
    ): Boolean {
        val ctx = context?.applicationContext ?: return false
        return when {
            channelId != null -> {
                val channel = ChatMetadataCache.loadChannel(ctx, channelId) ?: return false
                val title = channel.name?.ifBlank { null } ?: fallbackTitle
                val type = channel.type?.replaceFirstChar { it.uppercase() } ?: "Channel"
                binding.tvCompany.text = type
                val membersStr = (channel.memberCount ?: 0).let {
                    if (it > 0) "$it members" else ""
                }
                binding.tvRole.text = membersStr
                binding.tvHeaderSubtitle.text = membersStr
                binding.statusDot.visibility = View.GONE
                render(title, channel.description?.ifBlank { null }
                    ?: "Team updates and discussions live here.")
                true
            }
            conversationId != null -> {
                val conversation = ChatMetadataCache.loadConversation(ctx, conversationId) ?: return false
                val contactId = otherStaffId
                    ?: conversation.participants?.firstOrNull {
                        it.id != null && it.id != com.manjugroups.m_connect.auth.SessionManager(requireContext()).staffId
                    }?.id
                val staff = contactId?.let { ChatMetadataCache.loadStaff(ctx, it) }
                renderStaff(conversation, staff, fallbackTitle)
                true
            }
            else -> false
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
            if (channel != null) {
                context?.applicationContext?.let { ChatMetadataCache.saveChannel(it, channelId, channel) }
            }
            val title = channel?.name?.ifBlank { null } ?: fallbackTitle
            val memberCount = channel?.memberCount ?: 0
            val type = channel?.type?.replaceFirstChar { it.uppercase() } ?: "Channel"

            binding.tvCompany.text = type
            val membersStr = if (memberCount > 0) "$memberCount members" else ""
            binding.tvRole.text = membersStr
            binding.tvHeaderSubtitle.text = membersStr
            binding.statusDot.visibility = View.GONE

            val about = channel?.description?.ifBlank { null }
                ?: "Team updates and discussions live here."

            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.VISIBLE
            render(title, about)
        }.onFailure {
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.VISIBLE
            if (!hasLoadedData) render(fallbackTitle, "Channel details are not available right now.")
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
            Triple(conversation, staff, contactId)
        }.onSuccess { (conversation, staff, contactId) ->
            context?.applicationContext?.let { ctx ->
                if (conversation != null) ChatMetadataCache.saveConversation(ctx, conversationId, conversation)
                if (staff != null && !contactId.isNullOrBlank()) ChatMetadataCache.saveStaff(ctx, contactId, staff)
            }
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.VISIBLE
            renderStaff(conversation, staff, fallbackTitle)
        }.onFailure {
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.contentScroll.visibility = View.VISIBLE
            if (!hasLoadedData) render(fallbackTitle, "Contact details are not available right now.")
        }
    }

    private fun renderStaff(
        conversation: com.manjugroups.m_connect.network.ConversationData?,
        staff: com.manjugroups.m_connect.network.StaffFullData?,
        fallbackTitle: String
    ) {
        val title = staff?.name?.ifBlank { null }
            ?: conversation?.displayName?.ifBlank { null }
            ?: fallbackTitle

        binding.tvHeaderTitle.text = title
        binding.tvCompany.text = staff?.company ?: "Stark Industries"
        binding.tvRole.text = staff?.designation ?: staff?.role ?: "Senior Design Manager"
        binding.tvPhone.text = staff?.phone ?: "+1 (555) 123-4567"
        binding.tvEmail.text = staff?.email ?: "alicia.rochefort@starkindustries.com"
        binding.tvAddress.text = staff?.address ?: "Ashok Nagar Main Road"

        val photoUrl = staff?.photo
        if (!photoUrl.isNullOrBlank()) {
            binding.ivAvatarPhoto.visibility = View.VISIBLE
            binding.ivAvatarPhoto.load(photoUrl)
        } else {
            binding.ivAvatarPhoto.visibility = View.GONE
        }

        val contactId = staff?.id ?: otherStaffId()
        if (!contactId.isNullOrBlank()) {
            binding.statusDot.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val presence = api.getPresence(requireSession(), staffId = contactId).presence
                    if (_binding == null) return@launch
                    val isOnline = presence?.status == "online"
                    binding.tvHeaderSubtitle.text = if (isOnline) {
                        "Online"
                    } else {
                        formatLastSeen(presence?.lastSeenAt)
                    }
                    binding.statusDot.visibility = if (isOnline) View.VISIBLE else View.GONE
                }
            }
        } else {
            binding.tvHeaderSubtitle.text = "Last seen recently"
            binding.statusDot.visibility = View.GONE
        }

        val about = staff?.department
            ?: "Start a conversation and keep your project communication in one place."
        render(title, about)
    }

    private fun otherStaffId(): String? {
        return arguments?.getString("otherStaffId")
    }

    private fun formatLastSeen(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "Last seen recently"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        if (diff < 60_000L) return "Last seen just now"
        
        val calNow = java.util.Calendar.getInstance()
        val calThen = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) == calThen.get(java.util.Calendar.DAY_OF_YEAR)
                
        val time = java.text.SimpleDateFormat("h:mma", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        return if (sameDay) "Last seen Today $time" else {
            val day = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            "Last seen $day $time"
        }
    }

    private fun render(title: String, about: String) {
        if (_binding == null) return
        hasLoadedData = true
        val initials = title.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "C" }

        binding.tvAvatar.text = initials
        binding.tvTitle.text = title
        binding.tvHeaderTitle.text = title
        binding.tvAbout.text = about
    }

    private fun requireSession(): String {
        return com.manjugroups.m_connect.auth.SessionManager(requireContext()).bearerToken
    }

    private fun setupMuteRow(channelId: String?, conversationId: String?) {
        if (channelId == null && conversationId == null) {
            binding.rowMute.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val muted = runCatching {
                when {
                    channelId != null -> api.getChannel(requireSession(), channelId).channel?.muted == true
                    conversationId != null -> api.getConversation(requireSession(), conversationId).conversation?.muted == true
                    else -> false
                }
            }.getOrDefault(false)
            if (_binding == null) return@launch
            binding.switchMute.isChecked = muted
            binding.tvMuteLabel.text = if (muted) "Muted" else "Set as silent"
        }
        binding.rowMute.setOnClickListener {
            val target = !binding.switchMute.isChecked
            binding.switchMute.isChecked = target
            binding.tvMuteLabel.text = if (target) "Muted" else "Set as silent"
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    if (channelId != null) {
                        api.setChannelMute(
                            requireSession(),
                            com.manjugroups.m_connect.network.SetMuteRequest(
                                channelId = channelId,
                                muted = target
                            )
                        )
                    } else if (conversationId != null) {
                        api.setConversationMute(
                            requireSession(),
                            com.manjugroups.m_connect.network.SetMuteRequest(
                                conversationId = conversationId,
                                muted = target
                            )
                        )
                    } else null
                }.isSuccess
                if (_binding == null) return@launch
                if (!ok) {
                    binding.switchMute.isChecked = !target
                    binding.tvMuteLabel.text = if (!target) "Muted" else "Set as silent"
                    toast("Couldn't update mute")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onDestroyView()
        _binding = null
    }
}
