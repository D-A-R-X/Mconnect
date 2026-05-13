package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.MainActivity
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
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatListFragment : Fragment() {

    private enum class ChatFilter { ALL, UNREAD, FAVOURITES, GROUPS, DM }

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
    private var favouriteIds: MutableSet<String> = mutableSetOf()
    private var hasLoadedOnce: Boolean = false

    private lateinit var chatListAdapter: ChatListAdapter

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

        setupRecyclerView()
        setupHeader()
        setupActions()

        binding.chipAll.setOnClickListener { switchFilter(ChatFilter.ALL) }
        binding.chipUnread.setOnClickListener { switchFilter(ChatFilter.UNREAD) }
        binding.chipChannels.setOnClickListener { switchFilter(ChatFilter.GROUPS) }
        binding.chipDirect.setOnClickListener { switchFilter(ChatFilter.DM) }
        binding.chipFavourites.setOnClickListener { switchFilter(ChatFilter.FAVOURITES) }

        binding.btnChatMenu.setOnClickListener { showNewChatOptions() }
        binding.btnEmptyAction.setOnClickListener { handleEmptyCta() }

        binding.etSearchChats.doAfterTextChanged {
            chatSearchQuery = it?.toString().orEmpty().trim()
            renderCurrentList()
        }

        renderFilterState()
        loadData()
    }

    private fun setupRecyclerView() {
        chatListAdapter = ChatListAdapter(
            onItemClick = { item ->
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
            },
            onItemLongClick = { anchor, item ->
                showChatActionMenu(anchor, item)
            },
            avatarBinder = { container, label, text, seed ->
                bindAvatar(container, label, text, seed)
            },
            timestampBinder = { view, millis ->
                bindTimestamp(view, millis)
            }
        )
        binding.rvChatList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatListAdapter
        }
    }

    private fun setupHeader() {
        val name = (session.userName ?: "User").ifBlank { "User" }
        binding.tvChatAvatarInitial.text = name.first().uppercase()
    }

    private fun setupActions() {
        // Any additional header actions
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(true)
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
        bindFilterChip(binding.chipUnread, activeFilter == ChatFilter.UNREAD)
        bindFilterChip(binding.chipChannels, activeFilter == ChatFilter.GROUPS)
        bindFilterChip(binding.chipDirect, activeFilter == ChatFilter.DM)
        bindFilterChip(binding.chipFavourites, activeFilter == ChatFilter.FAVOURITES)
    }

    private fun bindFilterChip(view: TextView, isActive: Boolean) {
        view.setBackgroundResource(
            if (isActive) R.drawable.bg_chat_filter_active
            else R.drawable.bg_chat_filter_inactive
        )
        view.setTextColor(
            if (isActive) ContextCompat.getColor(requireContext(), R.color.lt_foreground_inverse)
            else ContextCompat.getColor(requireContext(), R.color.chat_text_secondary)
        )
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!hasLoadedOnce) {
                SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            }
            runCatching {
                val conversations = api.getConversations(session.bearerToken).conversations
                val channels = api.getChannels(session.bearerToken).channels
                conversations to channels
            }.onSuccess { (conversations, channels) ->
                allConversations = conversations
                allChannels = channels
                hasLoadedOnce = true
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                renderCurrentList()
            }.onFailure {
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                if (!hasLoadedOnce) {
                    showEmptyState(
                        title = "Unable to load chats",
                        subtitle = "Pull to refresh later or try opening chat again."
                    )
                }
            }
        }
    }

    private fun renderCurrentList() {
        if (_binding == null) return
        val items = buildItems()

        if (items.isEmpty()) {
            binding.rvChatList.visibility = View.GONE
            renderEmptyForActiveFilter()
            return
        }

        showListState()
        binding.rvChatList.visibility = View.VISIBLE
        chatListAdapter.submitList(items)
    }

    private fun showChatActionMenu(anchor: View, item: ChatListItem) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        val isFav = favouriteIds.contains(item.id)
        popup.menu.add(if (isFav) "Remove from Favourites" else "Add to Favourites")
        popup.menu.add("Delete Chat")
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.title) {
                "Delete Chat" -> toast("Deleting ${item.title}")
                "Add to Favourites" -> {
                    favouriteIds.add(item.id)
                    renderCurrentList()
                    toast("Added ${item.title} to Favourites")
                }
                "Remove from Favourites" -> {
                    favouriteIds.remove(item.id)
                    renderCurrentList()
                    toast("Removed ${item.title} from Favourites")
                }
            }
            true
        }
        popup.show()
    }

    private fun buildItems(): List<ChatListItem> {
        val query = chatSearchQuery.lowercase(Locale.getDefault())

        val conversationItems = allConversations.mapNotNull { conversation ->
            val id = conversation.id ?: return@mapNotNull null
            val title = conversation.displayName?.ifBlank { null } ?: "Chat"
            val subtitle = conversation.lastMessagePreview?.ifBlank { null }
                ?: conversation.lastMessage?.body?.ifBlank { null }
                ?: "No messages yet"
            
            val lastActive = conversation.lastMessageAt ?: 0L
            val isOnline = System.currentTimeMillis() - lastActive < 5L * 60L * 1000L

            ChatListItem(
                id = id,
                kind = ChatListItem.Kind.DIRECT,
                title = title,
                subtitle = subtitle,
                timestamp = conversation.lastMessageAt,
                unreadCount = conversation.unreadCount ?: 0,
                avatarText = initialsFor(title),
                avatarSeed = title.length,
                isMuted = conversation.muted ?: false,
                isOnline = isOnline
            )
        }

        val channelItems = allChannels.mapNotNull { channel ->
            val id = channel.id ?: return@mapNotNull null
            val title = channel.name?.ifBlank { null } ?: "Channel"
            val descriptor = channel.lastMessagePreview?.ifBlank { null }
                ?: channel.description?.ifBlank { null }
                ?: run {
                    val memberCount = channel.memberCount ?: 0
                    if (memberCount > 0) "$memberCount members" else "Channel"
                }
            ChatListItem(
                id = id,
                kind = ChatListItem.Kind.CHANNEL,
                title = title,
                subtitle = descriptor,
                timestamp = channel.lastMessageAt,
                unreadCount = channel.unreadCount ?: 0,
                avatarText = "#",
                avatarSeed = title.length + 7,
                isMuted = channel.muted ?: false
            )
        }

        val all = (conversationItems + channelItems).map { it.copy(isFavourite = favouriteIds.contains(it.id)) }

        val filtered = all
            .filter { item ->
                when (activeFilter) {
                    ChatFilter.ALL -> true
                    ChatFilter.UNREAD -> item.unreadCount > 0
                    ChatFilter.GROUPS -> item.kind == ChatListItem.Kind.CHANNEL
                    ChatFilter.DM -> item.kind == ChatListItem.Kind.DIRECT
                    ChatFilter.FAVOURITES -> item.isFavourite
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

    private fun renderEmptyForActiveFilter() {
        if (chatSearchQuery.isNotBlank()) {
            showEmptyState(
                title = "No matches",
                subtitle = "Try a different keyword or clear your search."
            )
            return
        }

        when (activeFilter) {
            ChatFilter.ALL ->
                showEmptyState(
                    title = "No Chats Yet",
                    subtitle = "Stay organized by creating or joining teams.\nGroups help you manage tasks, track progress, and collaborate with your team in one place."
                )

            ChatFilter.UNREAD ->
                showEmptyState(
                    title = "No Unread Message Yet",
                    subtitle = "Stay organized by creating or joining teams.\nGroups help you manage tasks, track progress, and collaborate with your team in one place."
                )

            ChatFilter.GROUPS ->
                showEmptyState(
                    title = "No Groups Yet",
                    subtitle = "Stay organized by creating or joining teams.\nGroups help you manage tasks, track progress, and collaborate with your team in one place."
                )

            ChatFilter.DM ->
                showEmptyState(
                    title = "No Direct Message Yet",
                    subtitle = "Stay organized by creating or joining teams.\nGroups help you manage tasks, track progress, and collaborate with your team in one place."
                )

            ChatFilter.FAVOURITES ->
                showEmptyState(
                    title = "No Favourites Yet",
                    subtitle = "Long-press on a chat and select \"Add to Favourites\" to pin it here."
                )
        }
    }

    private fun showEmptyState(title: String, subtitle: String) {
        binding.emptyState.visibility = View.VISIBLE
        binding.tvEmptyTitle.text = title
        binding.tvEmptySubtitle.text = subtitle
    }

    private fun showListState() {
        binding.emptyState.visibility = View.GONE
    }

    private fun handleEmptyCta() {
        showNewChatOptions()
    }

    private fun showNewChatOptions() {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_new_chat, null)
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let {
                val params = it.layoutParams
                params.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
                it.layoutParams = params
                it.setBackgroundResource(android.R.color.transparent)
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = true
                }
            }
        }

        val searchField = content.findViewById<EditText>(R.id.etSearchPeople)
        val peopleCard = content.findViewById<LinearLayout>(R.id.peopleCard)
        val emptyState = content.findViewById<TextView>(R.id.tvEmptyPeople)
        val createGroupCta = content.findViewById<View>(R.id.cardCreateGroup)
        val closeBtn = content.findViewById<View>(R.id.btnSheetClose)
        val startBtn = content.findViewById<FrameLayout>(R.id.btnStartChat)
        val startLabel = content.findViewById<TextView>(R.id.tvStartChatLabel)

        var people: List<StaffData> = emptyList()
        var selectedStaff: StaffData? = null

        fun bindStartButton() {
            val enabled = selectedStaff != null
            startBtn.isClickable = enabled
            startBtn.isFocusable = enabled
            startBtn.setBackgroundResource(
                if (enabled) R.drawable.bg_sheet_start_button
                else R.drawable.bg_sheet_start_button_disabled
            )
            startLabel.text = "Start Chat"
        }

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

            peopleCard.removeAllViews()
            if (filtered.isEmpty()) {
                peopleCard.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                return
            }

            emptyState.visibility = View.GONE
            peopleCard.visibility = View.VISIBLE

            filtered.forEachIndexed { index, member ->
                val row = layoutInflater.inflate(
                    R.layout.item_chat_sheet_person,
                    peopleCard,
                    false
                )
                val initials = initialsFor(member.name ?: "User")
                bindAvatar(
                    row.findViewById(R.id.avatarContainer),
                    row.findViewById(R.id.tvAvatar),
                    initials,
                    index + (member.name?.length ?: 0)
                )
                row.findViewById<TextView>(R.id.tvName).text = member.name ?: "User"
                row.findViewById<TextView>(R.id.tvSubtitle).text =
                    listOfNotNull(member.designation, member.department)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" • ")
                        ?: "Tap to start a direct message"

                val radio = row.findViewById<View>(R.id.radioButton)
                val avatarCheck = row.findViewById<View>(R.id.avatarCheck)
                val isSelected = selectedStaff?.id == member.id && member.id != null
                radio.setBackgroundResource(
                    if (isSelected) R.drawable.bg_sheet_radio_on
                    else R.drawable.bg_sheet_radio_off
                )
                avatarCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                row.setOnClickListener {
                    selectedStaff = if (selectedStaff?.id == member.id) null else member
                    renderPeople()
                    bindStartButton()
                }

                peopleCard.addView(row)

                if (index < filtered.lastIndex) {
                    val divider = View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
                        ).apply {
                            marginStart = dpToPx(74)
                        }
                        setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.chat_separator)
                        )
                        alpha = 0.5f
                    }
                    peopleCard.addView(divider)
                }
            }
        }

        closeBtn.setOnClickListener { dialog.dismiss() }
        createGroupCta.setOnClickListener {
            dialog.dismiss()
            promptGroupConversationName()
        }
        startBtn.setOnClickListener {
            val staff = selectedStaff ?: return@setOnClickListener
            dialog.dismiss()
            startDirectMessage(staff)
        }
        searchField.doAfterTextChanged { renderPeople() }

        bindStartButton()
        emptyState.text = "Loading people..."
        emptyState.visibility = View.VISIBLE
        peopleCard.visibility = View.GONE
        dialog.show()

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
        view.text = formatChatTimestamp(millis)
    }

    private fun formatChatTimestamp(millis: Long): String {
        val now = Calendar.getInstance()
        val msg = Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
        val sameWeek = now.get(Calendar.WEEK_OF_YEAR) == msg.get(Calendar.WEEK_OF_YEAR) &&
            now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)

        return when {
            sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
            sameWeek -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis))
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(millis))
        }
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
