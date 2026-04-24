package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentChatListBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChannelData
import com.manjugroups.m_connect.network.ConversationData
import com.manjugroups.m_connect.network.CreateChannelRequest
import com.manjugroups.m_connect.network.CreateGroupConversationRequest
import com.manjugroups.m_connect.network.StartDmRequest
import com.manjugroups.m_connect.network.StaffData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class ChatListFragment : Fragment() {

    private enum class ChatFilter { ALL, DIRECT, CHANNELS }

    private data class ChatListItem(
        val id: String,
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val timestamp: Long?,
        val unreadCount: Int,
        val avatarText: String,
        val avatarSeed: Int
    ) {
        enum class Kind { DIRECT, CHANNEL }
    }

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var refreshJob: Job? = null
    private var activeStaffCache: List<StaffData> = emptyList()
    private var allChannels: List<ChannelData> = emptyList()
    private var allConversations: List<ConversationData> = emptyList()
    private var chatSearchQuery: String = ""
    private var activeFilter: ChatFilter = ChatFilter.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.chipAll.setOnClickListener { switchFilter(ChatFilter.ALL) }
        binding.chipDirect.setOnClickListener { switchFilter(ChatFilter.DIRECT) }
        binding.chipChannels.setOnClickListener { switchFilter(ChatFilter.CHANNELS) }
        binding.btnNewChat.setOnClickListener { showNewChatOptions() }
        binding.etSearchChats.doAfterTextChanged {
            chatSearchQuery = it?.toString().orEmpty().trim()
            renderCurrentList()
        }

        renderFilterState()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        startRefreshLoop()
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                loadData()
                delay(5_000)
            }
        }
    }

    private fun switchFilter(filter: ChatFilter) {
        if (activeFilter == filter) return
        activeFilter = filter
        renderFilterState()
        renderCurrentList()
    }

    private fun renderFilterState() {
        bindFilterChip(binding.chipAll, activeFilter == ChatFilter.ALL)
        bindFilterChip(binding.chipDirect, activeFilter == ChatFilter.DIRECT)
        bindFilterChip(binding.chipChannels, activeFilter == ChatFilter.CHANNELS)
    }

    private fun bindFilterChip(view: TextView, isActive: Boolean) {
        view.setBackgroundResource(
            if (isActive) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive
        )
        view.setTextColor(
            resolveColor(
                if (isActive) R.attr.colorForegroundInverse else R.attr.colorForegroundMuted
            )
        )
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val conversations = api.getConversations(session.bearerToken).conversations
                val channels = api.getChannels(session.bearerToken).channels
                conversations to channels
            }.onSuccess { (conversations, channels) ->
                allConversations = conversations
                allChannels = channels
                renderCurrentList()
            }.onFailure {
                renderEmptyState(
                    title = "Unable to load chats",
                    subtitle = "Pull to refresh later or try opening chat again."
                )
            }
        }
    }

    private fun renderCurrentList() {
        val items = buildItems()
        binding.chatList.removeAllViews()

        if (items.isEmpty()) {
            val message = when {
                chatSearchQuery.isNotBlank() -> "Try a different keyword or clear your search."
                activeFilter == ChatFilter.CHANNELS -> "Create or join a channel to see it here."
                activeFilter == ChatFilter.DIRECT -> "Use the plus button to start a conversation."
                else -> "Use the plus button to start a conversation or create a channel."
            }
            renderEmptyState(
                title = "No chats found",
                subtitle = message
            )
            return
        }

        showListState()
        items.forEach { item ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_chat, binding.chatList, false)

            bindAvatar(
                row.findViewById(R.id.avatarContainer),
                row.findViewById(R.id.tvChatAvatar),
                item.avatarText,
                item.avatarSeed
            )
            row.findViewById<TextView>(R.id.tvChatName).text = item.title
            row.findViewById<TextView>(R.id.tvChatLastMsg).text = item.subtitle
            bindTimestamp(row.findViewById(R.id.tvChatTime), item.timestamp)
            bindUnreadBadge(row.findViewById(R.id.tvUnread), item.unreadCount)

            row.setOnClickListener {
                val fragment = when (item.kind) {
                    ChatListItem.Kind.DIRECT ->
                        ChatMessagesFragment.forConversation(item.id, item.title)
                    ChatListItem.Kind.CHANNEL ->
                        ChatMessagesFragment.forChannel(item.id, item.title)
                }

                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            }

            binding.chatList.addView(row)
        }
    }

    private fun buildItems(): List<ChatListItem> {
        val query = chatSearchQuery.lowercase(Locale.getDefault())

        val conversationItems = allConversations.mapNotNull { conversation ->
            val id = conversation.id ?: return@mapNotNull null
            val title = conversation.displayName?.ifBlank { null } ?: "Chat"
            val subtitle = conversation.lastMessage?.body?.ifBlank { null }
                ?: conversation.participants.orEmpty()
                    .joinToString(", ") { it.name.orEmpty() }
                    .ifBlank { "Tap to start chatting" }
            ChatListItem(
                id = id,
                kind = ChatListItem.Kind.DIRECT,
                title = title,
                subtitle = subtitle,
                timestamp = conversation.lastMessageAt,
                unreadCount = conversation.unreadCount ?: 0,
                avatarText = initialsFor(title),
                avatarSeed = title.length
            )
        }

        val channelItems = allChannels.mapNotNull { channel ->
            val id = channel.id ?: return@mapNotNull null
            val title = channel.name?.ifBlank { null } ?: "Channel"
            val descriptor = buildString {
                append("# ")
                append(channel.description?.takeIf { it.isNotBlank() } ?: title)
                val memberCount = channel.memberCount ?: 0
                if (memberCount > 0) {
                    append(" • ")
                    append(memberCount)
                    append(" members")
                }
            }
            ChatListItem(
                id = id,
                kind = ChatListItem.Kind.CHANNEL,
                title = title,
                subtitle = descriptor,
                timestamp = null,
                unreadCount = channel.unreadCount ?: 0,
                avatarText = "#",
                avatarSeed = title.length + 7
            )
        }

        val filtered = (conversationItems + channelItems)
            .filter { item ->
                when (activeFilter) {
                    ChatFilter.ALL -> true
                    ChatFilter.DIRECT -> item.kind == ChatListItem.Kind.DIRECT
                    ChatFilter.CHANNELS -> item.kind == ChatListItem.Kind.CHANNEL
                }
            }
            .filter { item ->
                if (query.isBlank()) {
                    true
                } else {
                    "${item.title} ${item.subtitle}"
                        .lowercase(Locale.getDefault())
                        .contains(query)
                }
            }
            .sortedWith(
                compareByDescending<ChatListItem> { it.unreadCount > 0 }
                    .thenByDescending { it.timestamp ?: Long.MIN_VALUE }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )

        return filtered
    }

    private fun bindUnreadBadge(view: TextView, unreadCount: Int) {
        if (unreadCount > 0) {
            view.text = if (unreadCount > 99) "99+" else unreadCount.toString()
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun renderEmptyState(title: String, subtitle: String) {
        binding.chatList.removeAllViews()
        binding.emptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = title
        binding.tvEmptyStateSubtitle.text = subtitle
    }

    private fun showListState() {
        binding.emptyState.visibility = View.GONE
    }

    private fun showNewChatOptions() {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_new_chat, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)

        val searchField = content.findViewById<EditText>(R.id.etSearchPeople)
        val peopleList = content.findViewById<LinearLayout>(R.id.peopleList)
        val emptyState = content.findViewById<TextView>(R.id.tvEmptyPeople)
        val directCard = content.findViewById<View>(R.id.cardDirectMessage)
        val groupCard = content.findViewById<View>(R.id.cardGroupConversation)
        val channelCard = content.findViewById<View>(R.id.cardNewChannel)

        var people: List<StaffData> = emptyList()

        fun renderPeople() {
            val query = searchField.text?.toString().orEmpty().trim()
            val filtered = people.filter { member ->
                if (query.isBlank()) return@filter true
                val haystack = listOfNotNull(
                    member.name,
                    member.designation,
                    member.department,
                    member.employeeId
                ).joinToString(" ").lowercase(Locale.getDefault())
                haystack.contains(query.lowercase(Locale.getDefault()))
            }

            peopleList.removeAllViews()
            if (filtered.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                return
            }

            emptyState.visibility = View.GONE
            filtered.forEachIndexed { index, member ->
                val item = layoutInflater.inflate(R.layout.item_chat_contact, peopleList, false)
                val initials = initialsFor(member.name ?: "User")
                bindAvatar(
                    item.findViewById(R.id.avatarContainer),
                    item.findViewById(R.id.tvAvatar),
                    initials,
                    index + (member.name?.length ?: 0)
                )
                item.findViewById<TextView>(R.id.tvName).text = member.name ?: "User"
                item.findViewById<TextView>(R.id.tvSubtitle).text =
                    listOfNotNull(member.designation, member.department)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" • ")
                        ?: "Tap to start a direct message"
                item.setOnClickListener {
                    dialog.dismiss()
                    startDirectMessage(member)
                }
                peopleList.addView(item)
            }
        }

        directCard.setOnClickListener {
            searchField.requestFocus()
        }
        groupCard.setOnClickListener {
            dialog.dismiss()
            promptGroupConversationName()
        }
        channelCard.setOnClickListener {
            dialog.dismiss()
            promptChannelName()
        }
        searchField.doAfterTextChanged { renderPeople() }

        emptyState.text = "Loading people..."
        emptyState.visibility = View.VISIBLE
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = false
        dialog.behavior.isDraggable = true

        withActiveStaff { staff ->
            people = staff
            emptyState.text = "No people match your search."
            renderPeople()
        }
    }

    private fun promptGroupConversationName() {
        promptForText(
            title = "Group conversation",
            hint = "Optional group name",
            positiveLabel = "Next"
        ) { name ->
            pickMultipleStaff(
                title = "Choose members",
                positiveLabel = "Create"
            ) { selectedStaff ->
                createGroupConversation(
                    memberIds = selectedStaff.mapNotNull { it.id },
                    displayName = name.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    private fun promptChannelName() {
        promptForText(
            title = "Channel name",
            hint = "Team updates",
            positiveLabel = "Next"
        ) { rawName ->
            val name = rawName.trim()
            if (name.isBlank()) {
                toast("Channel name is required")
                return@promptForText
            }
            promptChannelType { type ->
                if (type == "private") {
                    pickMultipleStaff(
                        title = "Invite members",
                        positiveLabel = "Create"
                    ) { selectedStaff ->
                        createChannel(
                            name = name,
                            type = type,
                            memberIds = selectedStaff.mapNotNull { it.id }
                        )
                    }
                } else {
                    createChannel(name = name, type = type, memberIds = emptyList())
                }
            }
        }
    }

    private fun promptChannelType(onSelected: (String) -> Unit) {
        val labels = arrayOf("Public channel", "Private channel")
        val values = arrayOf("public", "private")
        var selectedIndex = 0
        AlertDialog.Builder(requireContext())
            .setTitle("Channel visibility")
            .setSingleChoiceItems(labels, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Continue") { _, _ ->
                onSelected(values[selectedIndex])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMultipleStaff(
        title: String,
        positiveLabel: String,
        onSelected: (List<StaffData>) -> Unit
    ) {
        withActiveStaff { staff ->
            val labels = staff.map { member ->
                listOfNotNull(member.name, member.designation).joinToString(" • ")
            }.toTypedArray()
            val checked = BooleanArray(staff.size)

            AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(positiveLabel) { _, _ ->
                    val selected = staff.filterIndexed { index, _ -> checked[index] }
                    if (selected.isEmpty()) {
                        toast("Select at least one person")
                        return@setPositiveButton
                    }
                    onSelected(selected)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun withActiveStaff(onLoaded: (List<StaffData>) -> Unit) {
        if (activeStaffCache.isNotEmpty()) {
            onLoaded(activeStaffCache)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getStaff(session.bearerToken, status = "active")
            }.onSuccess { response ->
                val currentStaffId = session.staffId
                activeStaffCache = response.staff.filter { it.id != null && it.id != currentStaffId }
                if (activeStaffCache.isEmpty()) {
                    toast("No staff available")
                    return@onSuccess
                }
                onLoaded(activeStaffCache)
            }.onFailure {
                toast("Unable to load staff")
            }
        }
    }

    private fun startDirectMessage(staff: StaffData) {
        val otherStaffId = staff.id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.startDm(session.bearerToken, StartDmRequest(otherStaffId))
            }.onSuccess { response ->
                val conversationId = response.conversationId
                if (!response.success || conversationId == null) {
                    toast("Unable to start direct message")
                    return@onSuccess
                }
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.fragmentContainer,
                        ChatMessagesFragment.forConversation(
                            id = conversationId,
                            name = staff.name ?: "Chat"
                        )
                    )
                    .addToBackStack(null)
                    .commit()
            }.onFailure {
                toast("Unable to start direct message")
            }
        }
    }

    private fun createGroupConversation(memberIds: List<String>, displayName: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.createGroupConversation(
                    session.bearerToken,
                    CreateGroupConversationRequest(
                        memberIds = memberIds,
                        name = displayName
                    )
                )
            }.onSuccess { response ->
                val conversationId = response.conversationId
                if (!response.success || conversationId == null) {
                    toast("Unable to create conversation")
                    return@onSuccess
                }
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.fragmentContainer,
                        ChatMessagesFragment.forConversation(
                            id = conversationId,
                            name = displayName ?: "Group chat"
                        )
                    )
                    .addToBackStack(null)
                    .commit()
            }.onFailure {
                toast("Unable to create conversation")
            }
        }
    }

    private fun createChannel(name: String, type: String, memberIds: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.createChannel(
                    session.bearerToken,
                    CreateChannelRequest(
                        name = name,
                        type = type,
                        memberIds = memberIds.ifEmpty { null }
                    )
                )
            }.onSuccess { response ->
                val channelId = response.channelId
                if (!response.success || channelId == null) {
                    toast("Unable to create channel")
                    return@onSuccess
                }
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.fragmentContainer,
                        ChatMessagesFragment.forChannel(
                            id = channelId,
                            name = name
                        )
                    )
                    .addToBackStack(null)
                    .commit()
            }.onFailure {
                toast("Unable to create channel")
            }
        }
    }

    private fun promptForText(
        title: String,
        hint: String,
        positiveLabel: String,
        onPositive: (String) -> Unit
    ) {
        val input = EditText(requireContext()).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(resolveColor(R.attr.colorForegroundPrimary))
            setHintTextColor(resolveColor(R.attr.colorForegroundMuted))
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positiveLabel) { _, _ ->
                onPositive(input.text?.toString().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindTimestamp(view: TextView, millis: Long?) {
        if (millis == null || millis <= 0L) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun bindAvatar(container: View, label: TextView, text: String, seed: Int) {
        val palette = avatarPalette(seed)
        container.background?.mutate()?.setTint(palette.first)
        label.setTextColor(palette.second)
        label.text = text
    }

    private fun avatarPalette(seed: Int): Pair<Int, Int> {
        return when (seed.mod(4)) {
            0 -> resolveColor(R.attr.colorAccentLight) to resolveColor(R.attr.colorAccentPrimary)
            1 -> resolveColor(R.attr.colorInfoLight) to resolveColor(R.attr.colorInfo)
            2 -> resolveColor(R.attr.colorSuccessLight) to resolveColor(R.attr.colorSuccess)
            else -> resolveColor(R.attr.colorWarningLight) to resolveColor(R.attr.colorWarning)
        }
    }

    private fun initialsFor(name: String): String =
        name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { name.take(1).uppercase(Locale.getDefault()) }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun resolveColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        super.onDestroyView()
        _binding = null
    }
}
