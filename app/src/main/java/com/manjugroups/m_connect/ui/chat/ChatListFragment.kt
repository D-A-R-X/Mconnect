package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val selectedChatIds: MutableSet<String> = mutableSetOf()
    private var selectionPopup: android.widget.PopupWindow? = null
    private var backCallback: androidx.activity.OnBackPressedCallback? = null

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
        binding.btnChatSelectionMenu.setOnClickListener { showSelectionActionsPopup(it) }
        binding.btnEmptyAction.setOnClickListener { handleEmptyCta() }

        backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                clearChatSelection()
            }
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
        }

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
                if (selectedChatIds.isNotEmpty()) {
                    toggleChatSelection(item.id)
                    return@ChatListAdapter
                }
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
            onItemLongClick = { _, item ->
                toggleChatSelection(item.id)
            },
            avatarBinder = { container, label, text, seed ->
                if (_binding != null) {
                    bindAvatar(container, label, text, seed)
                }
            },
            timestampBinder = { view, millis ->
                bindTimestamp(view, millis)
            },
            isSelectedProvider = { item -> selectedChatIds.contains(item.id) }
        )
        binding.rvChatList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatListAdapter
            itemAnimator = null
            addOnScrollListener(chipsCollapseScrollListener)
        }
    }

    private var chipsExpanded = true
    private var chipsExpandedHeight: Int = -1
    private var chipsExpandedTopMargin: Int = -1
    private var chipsHeightAnimator: android.animation.ValueAnimator? = null
    private val chipsCollapseScrollListener = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
        override fun onScrolled(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            dx: Int,
            dy: Int
        ) {
            if (_binding == null) return
            val chips = binding.chipScrollView
            if (chipsExpandedHeight <= 0 && chips.height > 0) {
                chipsExpandedHeight = chips.height
            }
            val lp = chips.layoutParams as? ViewGroup.MarginLayoutParams
            if (chipsExpandedTopMargin < 0 && lp != null && lp.topMargin > 0) {
                chipsExpandedTopMargin = lp.topMargin
            }
            val lm = recyclerView.layoutManager as? LinearLayoutManager
            val atTop = (lm?.findFirstCompletelyVisibleItemPosition() ?: -1) == 0
            when {
                dy > 6 && chipsExpanded -> animateChips(false)
                (dy < -6 || atTop) && !chipsExpanded -> animateChips(true)
            }
        }
    }

    private fun animateChips(expand: Boolean) {
        if (_binding == null) return
        val chips = binding.chipScrollView
        val lp = chips.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val fullHeight = chipsExpandedHeight.takeIf { it > 0 }
            ?: chips.height.takeIf { it > 0 }
            ?: return
        val fullMargin = chipsExpandedTopMargin.takeIf { it >= 0 } ?: lp.topMargin
        chipsExpanded = expand
        chipsHeightAnimator?.cancel()
        val startHeight = lp.height.let { if (it <= 0) chips.height else it }
        val startMargin = lp.topMargin
        val targetHeight = if (expand) fullHeight else 0
        val targetMargin = if (expand) fullMargin else 0
        chipsHeightAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                if (_binding == null) return@addUpdateListener
                val t = va.animatedValue as Float
                val mp = chips.layoutParams as ViewGroup.MarginLayoutParams
                mp.height = (startHeight + (targetHeight - startHeight) * t).toInt()
                mp.topMargin = (startMargin + (targetMargin - startMargin) * t).toInt()
                chips.layoutParams = mp
                chips.alpha = (mp.height.toFloat() / fullHeight).coerceIn(0f, 1f)
            }
            start()
        }
    }

    private fun toggleChatSelection(id: String) {
        if (selectedChatIds.contains(id)) {
            selectedChatIds.remove(id)
        } else {
            selectedChatIds.add(id)
        }
        applySelectionState()
        chatListAdapter.notifyDataSetChanged()
    }

    private fun clearChatSelection() {
        if (selectedChatIds.isEmpty()) return
        selectedChatIds.clear()
        applySelectionState()
        chatListAdapter.notifyDataSetChanged()
    }

    private fun applySelectionState() {
        if (_binding == null) return
        val count = selectedChatIds.size
        if (count == 0) {
            binding.tvChatHeaderTitle.text = "Chats"
            binding.btnChatMenu.visibility = View.VISIBLE
            binding.btnChatSelectionMenu.visibility = View.GONE
            backCallback?.isEnabled = false
            selectionPopup?.dismiss()
            selectionPopup = null
        } else {
            binding.tvChatHeaderTitle.text = "$count selected"
            binding.btnChatMenu.visibility = View.GONE
            binding.btnChatSelectionMenu.visibility = View.VISIBLE
            backCallback?.isEnabled = true
        }
    }

    private fun showSelectionActionsPopup(anchor: View) {
        if (selectedChatIds.isEmpty()) return
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.popup_chat_selection_actions, null)

        val popup = android.widget.PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        selectionPopup = popup

        val allAlreadyFav = selectedChatIds.all { favouriteIds.contains(it) }
        view.findViewById<TextView>(R.id.tvActionFavouriteLabel).text =
            if (allAlreadyFav) "Remove Favourites" else "Add Favourites"

        val selectedItems = buildItems().filter { it.id in selectedChatIds }
        val allMuted = selectedItems.isNotEmpty() && selectedItems.all { it.isMuted }
        view.findViewById<TextView>(R.id.tvActionMuteLabel).text =
            if (allMuted) "Unmute" else "Set as silent"
        view.findViewById<View>(R.id.actionMute).setOnClickListener {
            popup.dismiss()
            toggleChatMute(selectedItems, mute = !allMuted)
            selectedChatIds.clear()
            applySelectionState()
        }

        view.findViewById<View>(R.id.actionDelete).setOnClickListener {
            val count = selectedChatIds.size
            toast(if (count == 1) "Deleting 1 chat" else "Deleting $count chats")
            selectedChatIds.clear()
            popup.dismiss()
            applySelectionState()
            chatListAdapter.notifyDataSetChanged()
        }
        view.findViewById<View>(R.id.actionFavourite).setOnClickListener {
            if (allAlreadyFav) {
                favouriteIds.removeAll(selectedChatIds)
                toast("Removed from Favourites")
            } else {
                favouriteIds.addAll(selectedChatIds)
                toast("Added to Favourites")
            }
            selectedChatIds.clear()
            popup.dismiss()
            applySelectionState()
            renderCurrentList()
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val xOffset = anchorLocation[0] + anchor.width - view.measuredWidth
        val yOffset = anchorLocation[1] + anchor.height + dpToPx(6)
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, xOffset, yOffset)
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
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(true)
            main.setTopBarAppearance(Color.parseColor("#FEFEFE"), true, fullBleed = false)
        }
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

    private var isLoadingChats = false

    private fun loadData() {
        if (isLoadingChats) return
        isLoadingChats = true
        viewLifecycleOwner.lifecycleScope.launch {
            if (!hasLoadedOnce) {
                SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
                binding.rvChatList.visibility = View.GONE
            }
            runCatching {
                val conversations = api.getConversations(session.bearerToken).conversations
                val channels = api.getChannels(session.bearerToken).channels
                conversations to channels
            }.onSuccess { (conversations, channels) ->
                if (_binding == null) { isLoadingChats = false; return@onSuccess }
                allConversations = conversations
                allChannels = channels
                hasLoadedOnce = true
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                renderCurrentList()
            }.onFailure {
                if (_binding == null) { isLoadingChats = false; return@onFailure }
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                if (!hasLoadedOnce) {
                    showEmptyState(
                        title = "Unable to load chats",
                        subtitle = "Pull to refresh later or try opening chat again."
                    )
                }
            }
            isLoadingChats = false
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
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.popup_chat_item_actions, null)
        val popup = android.widget.PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 14f
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        val isFav = favouriteIds.contains(item.id)
        view.findViewById<TextView>(R.id.tvItemActionFavourite).text =
            if (isFav) "Remove from Favourites" else "Add to Favourites"
        view.findViewById<TextView>(R.id.tvItemActionMute).text =
            if (item.isMuted) "Unmute" else "Set as silent"

        view.findViewById<View>(R.id.itemActionFavourite).setOnClickListener {
            popup.dismiss()
            if (isFav) {
                favouriteIds.remove(item.id)
                toast("Removed from Favourites")
            } else {
                favouriteIds.add(item.id)
                toast("Added to Favourites")
            }
            renderCurrentList()
        }
        view.findViewById<View>(R.id.itemActionMute).setOnClickListener {
            popup.dismiss()
            toggleChatMute(listOf(item), mute = !item.isMuted)
        }
        view.findViewById<View>(R.id.itemActionDelete).setOnClickListener {
            popup.dismiss()
            toast("Deleting ${item.title}")
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width - view.measuredWidth).coerceAtLeast(16)
        val y = loc[1] + anchor.height + 8
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun toggleChatMute(items: List<ChatListItem>, mute: Boolean) {
        if (items.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var failures = 0
            items.forEach { item ->
                runCatching {
                    if (item.kind == ChatListItem.Kind.CHANNEL) {
                        api.setChannelMute(
                            session.bearerToken,
                            com.manjugroups.m_connect.network.SetMuteRequest(
                                channelId = item.id,
                                muted = mute
                            )
                        )
                    } else {
                        api.setConversationMute(
                            session.bearerToken,
                            com.manjugroups.m_connect.network.SetMuteRequest(
                                conversationId = item.id,
                                muted = mute
                            )
                        )
                    }
                }.onFailure { failures++ }
            }
            if (_binding == null) return@launch
            if (failures == 0) {
                toast(if (mute) "Muted" else "Unmuted")
            } else {
                toast("Updated with $failures error(s)")
            }
            loadData()
        }
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
            // Newest-message-first ordering, matching WhatsApp / iOS. Unread
            // status is conveyed by the badge, not by hoisting old unread
            // chats to the top.
            .sortedWith(
                compareByDescending<ChatListItem> { it.timestamp ?: Long.MIN_VALUE }
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
        val skeletonContainer = content.findViewById<View>(R.id.skeletonContainer)
        val createGroupCta = content.findViewById<View>(R.id.cardCreateGroup)
        val closeBtn = content.findViewById<View>(R.id.btnSheetClose)
        val startBtn = content.findViewById<FrameLayout>(R.id.btnStartChat)
        val startLabel = content.findViewById<TextView>(R.id.tvStartChatLabel)

        dialog.setOnDismissListener {
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
        }

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
            showCreateGroupSheet()
        }
        startBtn.setOnClickListener {
            val staff = selectedStaff ?: return@setOnClickListener
            dialog.dismiss()
            startDirectMessage(staff)
        }
        searchField.doAfterTextChanged { renderPeople() }

        bindStartButton()
        emptyState.text = ""
        emptyState.visibility = View.GONE
        peopleCard.visibility = View.GONE
        skeletonContainer.visibility = View.VISIBLE
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        dialog.show()

        withActiveStaff(
            onError = {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                skeletonContainer.visibility = View.GONE
                emptyState.text = "Unable to load people."
                emptyState.visibility = View.VISIBLE
            }
        ) { staff ->
            people = staff
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
            skeletonContainer.visibility = View.GONE
            emptyState.text = "No people match your search."
            renderPeople()
        }
    }

    private fun showCreateGroupSheet() {
        val content = layoutInflater.inflate(R.layout.bottom_sheet_create_group, null)
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

        val closeBtn = content.findViewById<View>(R.id.btnCreateGroupClose)
        val etGroupName = content.findViewById<EditText>(R.id.etGroupName)
        val searchField = content.findViewById<EditText>(R.id.etSearchGroupPeople)
        val peopleCard = content.findViewById<LinearLayout>(R.id.groupPeopleCard)
        val emptyView = content.findViewById<TextView>(R.id.tvGroupEmpty)
        val skeletonContainer = content.findViewById<View>(R.id.groupSkeletonContainer)
        val membersHeader = content.findViewById<TextView>(R.id.tvSelectedMembersHeader)
        val membersScroll = content.findViewById<View>(R.id.selectedMembersScroll)
        val membersContainer = content.findViewById<LinearLayout>(R.id.selectedMembersContainer)
        val createBtn = content.findViewById<FrameLayout>(R.id.btnCreateGroup)
        val createLabel = content.findViewById<TextView>(R.id.tvCreateGroupLabel)

        dialog.setOnDismissListener {
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
        }

        var people: List<StaffData> = emptyList()
        val selectedIds = linkedSetOf<String>()
        val selectedById = mutableMapOf<String, StaffData>()

        var renderSelectedChips: () -> Unit = {}
        var renderPeopleList: () -> Unit = {}
        var bindCreateButton: () -> Unit = {}

        renderSelectedChips = render@{
            membersContainer.removeAllViews()
            if (selectedIds.isEmpty()) {
                membersHeader.visibility = View.GONE
                membersScroll.visibility = View.GONE
                return@render
            }

            membersHeader.text = "Selected Members (${selectedIds.size})"
            membersHeader.visibility = View.VISIBLE
            membersScroll.visibility = View.VISIBLE

            selectedIds.forEach { id ->
                val staff = selectedById[id] ?: return@forEach
                val chip = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_create_group_chip)
                    setPadding(dpToPx(4), dpToPx(4), dpToPx(10), dpToPx(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dpToPx(6) }

                    val initials = initialsFor(staff.name ?: "User")
                    val avatarFrame = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
                        setBackgroundResource(R.drawable.bg_chat_avatar_circle)
                    }
                    val avatarLabel = TextView(context).apply {
                        textSize = 9f
                        setTextColor(resolveColor(R.attr.colorAccentPrimary))
                        text = initials
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER
                        )
                    }
                    avatarFrame.addView(avatarLabel)
                    bindAvatar(avatarFrame, avatarLabel, initials, (staff.name?.length ?: 0))
                    addView(avatarFrame)

                    addView(TextView(context).apply {
                        text = staff.name ?: "Member"
                        textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#101828"))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginStart = dpToPx(6)
                            marginEnd = dpToPx(4)
                        }
                    })

                    addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
                        setImageResource(R.drawable.ic_sheet_close)
                        setColorFilter(android.graphics.Color.parseColor("#667085"))
                    })

                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        selectedIds.remove(id)
                        selectedById.remove(id)
                        renderSelectedChips()
                        renderPeopleList()
                        bindCreateButton()
                    }
                }
                membersContainer.addView(chip)
            }
        }

        bindCreateButton = {
            val enabled = selectedIds.size >= 2
            createBtn.isClickable = enabled
            createBtn.isFocusable = enabled
            createBtn.setBackgroundResource(
                if (enabled) R.drawable.bg_sheet_start_button
                else R.drawable.bg_sheet_start_button_disabled
            )
            createLabel.text = if (selectedIds.size >= 2) "Create" else "Select at least 2"
        }

        renderPeopleList = render@{
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
                emptyView.visibility = View.VISIBLE
                return@render
            }

            emptyView.visibility = View.GONE
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
                        ?: "Tap to add to group"

                val radio = row.findViewById<View>(R.id.radioButton)
                val avatarCheck = row.findViewById<View>(R.id.avatarCheck)
                val memberId = member.id
                val isSelected = memberId != null && selectedIds.contains(memberId)
                radio.setBackgroundResource(
                    if (isSelected) R.drawable.bg_sheet_radio_on
                    else R.drawable.bg_sheet_radio_off
                )
                avatarCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                row.setOnClickListener {
                    if (memberId == null) return@setOnClickListener
                    if (selectedIds.contains(memberId)) {
                        selectedIds.remove(memberId)
                        selectedById.remove(memberId)
                    } else {
                        selectedIds.add(memberId)
                        selectedById[memberId] = member
                    }
                    renderPeopleList()
                    renderSelectedChips()
                    bindCreateButton()
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
        createBtn.setOnClickListener {
            if (selectedIds.size < 2) {
                toast("Select at least 2 members")
                return@setOnClickListener
            }
            val name = etGroupName.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            createGroupConversation(
                memberIds = selectedIds.toList(),
                displayName = name.takeIf { it.isNotBlank() }
            )
        }
        searchField.doAfterTextChanged { renderPeopleList() }

        bindCreateButton()
        emptyView.text = ""
        emptyView.visibility = View.GONE
        peopleCard.visibility = View.GONE
        skeletonContainer.visibility = View.VISIBLE
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        dialog.show()

        withActiveStaff(
            onError = {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                skeletonContainer.visibility = View.GONE
                emptyView.text = "Unable to load people."
                emptyView.visibility = View.VISIBLE
            }
        ) { staff ->
            people = staff
            SkeletonUtils.stopSkeletonPulse(skeletonContainer)
            skeletonContainer.visibility = View.GONE
            emptyView.text = "No people match your search."
            renderPeopleList()
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

    private fun withActiveStaff(
        onError: (() -> Unit)? = null,
        onLoaded: (List<StaffData>) -> Unit
    ) {
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
                    onError?.invoke()
                    return@onSuccess
                }
                onLoaded(activeStaffCache)
            }.onFailure {
                toast("Unable to load staff")
                onError?.invoke()
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
            sameDay -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
            sameWeek -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis))
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(millis))
        }
    }

    private fun bindAvatar(container: View, label: TextView, text: String, seed: Int) {
        val palette = avatarPalette(seed)
        // Try to tint the background drawable; use setBackgroundColor as fallback
        val bg = container.background
        if (bg != null) {
            bg.mutate().setTint(palette.first)
        } else {
            container.setBackgroundColor(palette.first)
        }
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
        SkeletonUtils.stopAll()
        refreshJob?.cancel()
        refreshJob = null
        super.onDestroyView()
        _binding = null
    }
}
