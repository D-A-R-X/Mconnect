package com.manjugroups.m_connect.ui.chat

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentGroupInfoBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChannelData
import com.manjugroups.m_connect.network.ChannelIdRequest
import com.manjugroups.m_connect.network.ChannelMemberRequest
import com.manjugroups.m_connect.network.ChannelMemberData
import com.manjugroups.m_connect.network.ConversationMemberRequest
import com.manjugroups.m_connect.network.SetChannelRoleRequest
import com.manjugroups.m_connect.network.SetConversationRoleRequest
import com.manjugroups.m_connect.network.SetMuteRequest
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.network.UpdateChannelRequest
import com.manjugroups.m_connect.network.UpdateGroupConversationRequest
import com.manjugroups.m_connect.ui.common.AppBottomSheets
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WhatsApp-style group info for both group kinds. Channels get the full
 * management surface (edit name/description/photo, add/remove members,
 * admin roles, hard delete); legacy group-dm conversations get everything
 * their backend supports too — rename/description, add/remove members,
 * admin roles (creator is always admin) — minus group photo and hard
 * delete, which only exist for channels.
 */
class GroupInfoFragment : Fragment() {

    companion object {
        fun forChannel(channelId: String, title: String) = GroupInfoFragment().apply {
            arguments = Bundle().apply {
                putString("channelId", channelId)
                putString("title", title)
            }
        }

        fun forConversation(conversationId: String, title: String) = GroupInfoFragment().apply {
            arguments = Bundle().apply {
                putString("conversationId", conversationId)
                putString("title", title)
            }
        }
    }

    private var _binding: FragmentGroupInfoBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()
    private val session by lazy { SessionManager(requireContext()) }

    private val channelId: String? get() = arguments?.getString("channelId")
    private val conversationId: String? get() = arguments?.getString("conversationId")

    private var channel: ChannelData? = null
    private var members: List<ChannelMemberData> = emptyList()
    private var myRole: String? = null
    // Legacy group-dm state (conversation-backed groups).
    private var conversationCreatedBy: String? = null
    private var conversationDescription: String? = null

    private val pickGroupPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) uploadGroupPhoto(uri)
        }

    // Unified across both group kinds: channel roles come from the members
    // endpoint; conversation roles from participants (creator maps to admin).
    private val isAdminMember: Boolean get() = myRole == "admin"
    private val isCreator: Boolean get() = channel?.createdBy != null && channel?.createdBy == session.staffId

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.avatarContainer.clipToOutline = true
        binding.btnBack.setOnClickListener { navigateUp() }

        val title = arguments?.getString("title").orEmpty().ifBlank { "Group" }
        renderIdentity(title, null, null)

        binding.rowMedia.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatMediaFragment.newInstance(channelId, conversationId, currentTitle(), ""),
                )
                .addToBackStack(null)
                .commit()
        }
        setupMuteRow()
        binding.btnEditGroup.setOnClickListener { showEditNameDescription() }
        binding.rowDescription.setOnClickListener { if (isAdminMember) showEditNameDescription(focusDescription = true) }
        binding.btnChangePhoto.setOnClickListener {
            pickGroupPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.rowAddMember.setOnClickListener { showAddMemberPicker() }
        binding.rowLeaveGroup.setOnClickListener { confirmLeave() }
        binding.rowDeleteGroup.setOnClickListener { confirmDelete() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        // The outgoing thread's onPause can re-show the bar AFTER our hide
        // (fragment transactions may resume the incoming screen first) —
        // re-assert once the transaction has fully settled.
        view?.post {
            if (isResumed) (activity as? MainActivity)?.setTabBarVisible(false)
        }
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onDestroyView()
        _binding = null
    }

    private fun currentTitle(): String =
        channel?.name?.ifBlank { null }
            ?: arguments?.getString("title").orEmpty().ifBlank { "Group" }

    private fun refresh() {
        val chId = channelId
        val convId = conversationId
        viewLifecycleOwner.lifecycleScope.launch {
            if (chId != null) {
                val detail = runCatching { api.getChannel(session.bearerToken, chId).channel }.getOrNull()
                val memberRows = runCatching {
                    api.getChannelMembers(session.bearerToken, chId).members
                }.getOrNull().orEmpty()
                if (_binding == null) return@launch
                channel = detail ?: channel
                members = memberRows
                myRole = memberRows.firstOrNull { it.staffId == session.staffId }?.role
                renderChannel()
            } else if (convId != null) {
                val conv = runCatching {
                    api.getConversation(session.bearerToken, convId).conversation
                }.getOrNull()
                if (_binding == null) return@launch
                conversationCreatedBy = conv?.createdBy
                conversationDescription = conv?.description
                val rows = conv?.participants.orEmpty().map { p ->
                    ChannelMemberData(
                        id = p.id, staffId = p.id,
                        // Creator is always an admin, even on rows written
                        // before roles existed.
                        role = p.role
                            ?: "admin".takeIf { p.id != null && p.id == conv?.createdBy },
                        staffName = p.name, profilePhoto = p.photo,
                    )
                }
                members = rows
                myRole = rows.firstOrNull { it.staffId == session.staffId }?.role
                renderConversation(
                    conv?.displayName?.ifBlank { null } ?: currentTitle(),
                    rows,
                )
            }
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private fun renderIdentity(title: String, photoUrl: String?, meta: String?) {
        binding.tvGroupName.text = title
        binding.tvGroupInitials.text = title.split(" ")
            .filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "G" }
        binding.tvGroupMeta.text = meta ?: "Group"
        val resolved = ProfilePhotos.resolve(photoUrl)
        if (!resolved.isNullOrBlank()) {
            binding.ivGroupPhoto.visibility = View.VISIBLE
            binding.tvGroupInitials.visibility = View.GONE
            binding.ivGroupPhoto.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            binding.ivGroupPhoto.visibility = View.GONE
            binding.tvGroupInitials.visibility = View.VISIBLE
        }
    }

    private fun renderChannel() {
        val ch = channel ?: return
        val count = if (members.isNotEmpty()) members.size else (ch.memberCount ?: 0)
        renderIdentity(currentTitle(), ch.avatarUrl, "Group · $count members")

        val creatorName = members.firstOrNull { it.staffId == ch.createdBy }?.staffName
        binding.tvCreatedBy.visibility = if (creatorName.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvCreatedBy.text = "Created by ${creatorName ?: ""}".trim()

        val desc = ch.description?.ifBlank { null }
        binding.tvDescription.text = desc
            ?: if (isAdminMember) "Add group description" else "No description"

        binding.btnEditGroup.visibility = if (isAdminMember) View.VISIBLE else View.GONE
        binding.btnChangePhoto.visibility = if (isAdminMember) View.VISIBLE else View.GONE
        binding.rowAddMember.visibility = if (isAdminMember) View.VISIBLE else View.GONE

        binding.tvMembersHeader.text = "$count members"
        renderMemberRows(members, manageable = true)

        // WhatsApp rule the user asked for: the creator can hard-delete the
        // group once everyone else has left.
        val aloneCreator = isCreator && members.none { it.staffId != session.staffId }
        binding.rowDeleteGroup.visibility = if (aloneCreator) View.VISIBLE else View.GONE
        binding.deleteDivider.visibility = if (aloneCreator) View.VISIBLE else View.GONE
    }

    private fun renderConversation(title: String, rows: List<ChannelMemberData>) {
        renderIdentity(title, null, "Group · ${rows.size} members")

        val creatorName = rows.firstOrNull { it.staffId == conversationCreatedBy }?.staffName
        binding.tvCreatedBy.visibility = if (creatorName.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvCreatedBy.text = "Created by ${creatorName ?: ""}".trim()

        binding.tvDescription.text = conversationDescription?.ifBlank { null }
            ?: if (isAdminMember) "Add group description" else "No description"

        binding.btnEditGroup.visibility = if (isAdminMember) View.VISIBLE else View.GONE
        // Legacy group-dm conversations have no avatar/hard-delete backend.
        binding.btnChangePhoto.visibility = View.GONE
        binding.rowDeleteGroup.visibility = View.GONE
        binding.rowAddMember.visibility = if (isAdminMember) View.VISIBLE else View.GONE
        binding.tvMembersHeader.text = "${rows.size} members"
        renderMemberRows(rows, manageable = true)
    }

    private fun renderMemberRows(rows: List<ChannelMemberData>, manageable: Boolean) {
        val container = binding.memberListContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val sorted = rows.sortedWith(
            compareByDescending<ChannelMemberData> { it.role == "admin" }
                .thenBy { it.staffName?.lowercase().orEmpty() }
        )
        for (member in sorted) {
            val row = inflater.inflate(R.layout.item_group_member, container, false)
            val isMe = member.staffId == session.staffId
            row.findViewById<TextView>(R.id.tvMemberName).text =
                if (isMe) "You" else member.staffName ?: "Member"
            row.findViewById<TextView>(R.id.tvMemberSubtitle).text =
                member.staffDesignation?.ifBlank { null } ?: member.staffRole ?: ""
            row.findViewById<TextView>(R.id.tvAdminBadge).visibility =
                if (member.role == "admin") View.VISIBLE else View.GONE

            val initials = row.findViewById<TextView>(R.id.tvMemberInitials)
            val photo = row.findViewById<ImageView>(R.id.ivMemberPhoto)
            row.findViewById<View>(R.id.memberAvatarContainer).clipToOutline = true
            initials.text = (member.staffName ?: "M").split(" ")
                .filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "M" }
            val resolved = ProfilePhotos.resolve(member.profilePhoto)
            if (!resolved.isNullOrBlank()) {
                photo.visibility = View.VISIBLE
                initials.visibility = View.GONE
                photo.load(resolved) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            }

            if (manageable && !isMe) {
                row.setOnClickListener { showMemberActions(member) }
            }
            container.addView(row)
        }
    }

    // ── Member management (channels) ────────────────────────────────────

    private fun showMemberActions(member: ChannelMemberData) {
        val name = member.staffName ?: "Member"
        val options = mutableListOf(
            AppBottomSheets.Option("Message $name", R.drawable.ic_nav_chat) {
                openDm(member)
            },
        )
        if (isAdminMember) {
            // The conversation creator is always an admin — no dismissing.
            val isConversationCreator =
                conversationId != null && member.staffId == conversationCreatedBy
            if (member.role == "admin") {
                if (!isConversationCreator) {
                    options += AppBottomSheets.Option("Dismiss as admin", R.drawable.ic_auth_user) {
                        setRole(member, "member")
                    }
                }
            } else {
                options += AppBottomSheets.Option("Make group admin", R.drawable.ic_auth_user) {
                    setRole(member, "admin")
                }
            }
            if (!isConversationCreator) {
                options += AppBottomSheets.Option(
                    "Remove from group", R.drawable.ic_chat_delete, destructive = true,
                ) { confirmRemove(member) }
            }
        }
        AppBottomSheets.showOptions(requireContext(), name, options)
    }

    private fun openDm(member: ChannelMemberData) {
        val staffId = member.staffId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val convId = runCatching {
                api.startDm(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.StartDmRequest(staffId),
                ).conversationId
            }.getOrNull()
            if (_binding == null || convId.isNullOrBlank()) return@launch
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.fragmentContainer,
                    ChatMessagesFragment.forConversation(convId, member.staffName ?: "Chat"),
                )
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setRole(member: ChannelMemberData, role: String) {
        val staffId = member.staffId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                when {
                    channelId != null -> api.setChannelRole(
                        session.bearerToken,
                        SetChannelRoleRequest(channelId = channelId!!, targetStaffId = staffId, role = role),
                    )
                    conversationId != null -> api.setConversationRole(
                        session.bearerToken,
                        SetConversationRoleRequest(
                            conversationId = conversationId!!, targetStaffId = staffId, role = role,
                        ),
                    )
                    else -> return@launch
                }
            }
            if (_binding == null) return@launch
            if (result.isSuccess) refresh() else toast("Couldn't change the role")
        }
    }

    private fun confirmRemove(member: ChannelMemberData) {
        AppBottomSheets.showConfirm(
            requireContext(),
            title = "Remove ${member.staffName ?: "member"}?",
            message = "They'll no longer see this group's messages.",
            confirmLabel = "Remove",
            destructive = true,
        ) {
            val staffId = member.staffId ?: return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    when {
                        channelId != null -> api.removeChannelMember(
                            session.bearerToken,
                            ChannelMemberRequest(channelId = channelId!!, staffId = staffId),
                        )
                        conversationId != null -> api.removeConversationMember(
                            session.bearerToken,
                            ConversationMemberRequest(
                                conversationId = conversationId!!, staffId = staffId,
                            ),
                        )
                        else -> return@launch
                    }
                }.isSuccess
                if (_binding == null) return@launch
                if (ok) refresh() else toast("Couldn't remove the member")
            }
        }
    }

    private fun showAddMemberPicker() {
        if (channelId == null && conversationId == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val staff = runCatching {
                api.getStaff(session.bearerToken, status = "active").staff
            }.getOrNull().orEmpty()
            if (_binding == null) return@launch
            val existing = members.mapNotNull { it.staffId }.toSet()
            val candidates = staff.filter { it.id !in existing && it.id != session.staffId }
            if (candidates.isEmpty()) {
                toast("Everyone is already in this group")
                return@launch
            }
            SearchableSelectionDialog.show(
                context = requireContext(),
                title = "Add member",
                options = candidates.map { s ->
                    SearchableOption(s, s.name ?: "Staff", s.designation ?: s.role)
                },
                emptyMessage = "No staff found",
            ) { picked ->
                val staffId = picked.id ?: return@show
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = runCatching {
                        when {
                            channelId != null -> api.addChannelMember(
                                session.bearerToken,
                                ChannelMemberRequest(channelId = channelId!!, staffId = staffId),
                            )
                            else -> api.addConversationMember(
                                session.bearerToken,
                                ConversationMemberRequest(
                                    conversationId = conversationId!!, staffId = staffId,
                                ),
                            )
                        }
                    }.isSuccess
                    if (_binding == null) return@launch
                    if (ok) refresh() else toast("Couldn't add the member")
                }
            }
        }
    }

    // ── Group identity editing (channels, admin) ────────────────────────

    private fun showEditNameDescription(focusDescription: Boolean = false) {
        if (!isAdminMember) return
        if (channelId == null && conversationId == null) return
        val currentName = channel?.name ?: currentTitle()
        val currentDesc = channel?.description ?: conversationDescription
        AppBottomSheets.showTextInput(
            requireContext(),
            title = "Edit group",
            fields = listOf(
                "Group name" to currentName,
                "Group description" to currentDesc,
            ),
        ) { values ->
            val name = values.getOrNull(0).orEmpty()
            val desc = values.getOrNull(1).orEmpty()
            if (name.isBlank()) {
                toast("Group name can't be empty")
                return@showTextInput
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    when {
                        channelId != null -> api.updateChannel(
                            session.bearerToken,
                            UpdateChannelRequest(
                                channelId = channelId!!,
                                name = name.takeIf { it != channel?.name },
                                description = desc.takeIf { it != (channel?.description ?: "") },
                            ),
                        )
                        else -> api.updateGroupConversation(
                            session.bearerToken,
                            UpdateGroupConversationRequest(
                                conversationId = conversationId!!,
                                name = name.takeIf { it != currentName },
                                description = desc.takeIf { it != (conversationDescription ?: "") },
                            ),
                        )
                    }
                }.isSuccess
                if (_binding == null) return@launch
                if (ok) refresh() else toast("Couldn't update the group")
            }
        }
    }

    private fun uploadGroupPhoto(uri: Uri) {
        val chId = channelId ?: return
        val ctx = context ?: return
        binding.avatarUploadOverlay.visibility = View.VISIBLE
        binding.btnChangePhoto.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val cr = ctx.contentResolver
                    val mime = cr.getType(uri) ?: "image/jpeg"
                    val tmp = java.io.File.createTempFile("group_", ".img", ctx.cacheDir)
                    cr.openInputStream(uri).use { input ->
                        tmp.outputStream().use { output -> input?.copyTo(output) }
                    }
                    val uploaded = StorageUploader.upload(
                        api, session.bearerToken, tmp, contentType = mime,
                    )
                    tmp.delete()
                    uploaded
                }
            }.getOrNull()
            val storageId = result?.storageId
            if (_binding == null) return@launch
            if (storageId.isNullOrBlank()) {
                binding.avatarUploadOverlay.visibility = View.GONE
                binding.btnChangePhoto.isEnabled = true
                toast(result?.errorMessage ?: "Couldn't upload the photo")
                return@launch
            }
            val saved = runCatching {
                api.updateChannel(
                    session.bearerToken,
                    UpdateChannelRequest(channelId = chId, avatarStorageId = storageId),
                )
            }.getOrNull()
            if (_binding == null) return@launch
            binding.avatarUploadOverlay.visibility = View.GONE
            binding.btnChangePhoto.isEnabled = true
            if (saved?.success == true) refresh() else toast("Couldn't update the group photo")
        }
    }

    // ── Mute / leave / delete ───────────────────────────────────────────

    private fun setupMuteRow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val muted = runCatching {
                when {
                    channelId != null ->
                        api.getChannel(session.bearerToken, channelId!!).channel?.muted == true
                    conversationId != null ->
                        api.getConversation(session.bearerToken, conversationId!!)
                            .conversation?.muted == true
                    else -> false
                }
            }.getOrDefault(false)
            if (_binding == null) return@launch
            binding.switchMute.isChecked = muted
        }
        binding.rowMute.setOnClickListener {
            val target = !binding.switchMute.isChecked
            binding.switchMute.isChecked = target
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    if (channelId != null) {
                        api.setChannelMute(
                            session.bearerToken,
                            SetMuteRequest(channelId = channelId, muted = target),
                        )
                    } else {
                        api.setConversationMute(
                            session.bearerToken,
                            SetMuteRequest(conversationId = conversationId, muted = target),
                        )
                    }
                }.isSuccess
                if (_binding == null) return@launch
                if (!ok) {
                    binding.switchMute.isChecked = !target
                    toast("Couldn't update mute")
                }
            }
        }
    }

    private fun confirmLeave() {
        AppBottomSheets.showConfirm(
            requireContext(),
            title = "Exit group?",
            message = "You'll stop receiving messages from \"${currentTitle()}\".",
            confirmLabel = "Exit group",
            destructive = true,
        ) { leaveGroup() }
    }

    private fun leaveGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                when {
                    channelId != null ->
                        api.leaveChannel(session.bearerToken, ChannelIdRequest(channelId!!))
                    conversationId != null ->
                        api.removeConversationMember(
                            session.bearerToken,
                            ConversationMemberRequest(
                                conversationId = conversationId!!,
                                staffId = session.staffId.orEmpty(),
                            ),
                        )
                    else -> null
                }
            }.getOrNull() != null
            if (_binding == null) return@launch
            if (ok) backToChatList() else toast("Couldn't exit the group")
        }
    }

    private fun confirmDelete() {
        AppBottomSheets.showConfirm(
            requireContext(),
            title = "Delete group?",
            message = "\"${currentTitle()}\" and its full message history will be permanently deleted.",
            confirmLabel = "Delete group",
            destructive = true,
        ) {
            val chId = channelId ?: return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    api.deleteChannel(session.bearerToken, ChannelIdRequest(chId))
                }.isSuccess
                if (_binding == null) return@launch
                if (ok) backToChatList() else toast("Couldn't delete the group")
            }
        }
    }

    /** Pops both this screen and the thread beneath it back to the list. */
    private fun backToChatList() {
        parentFragmentManager.popBackStack()
        parentFragmentManager.popBackStack()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
