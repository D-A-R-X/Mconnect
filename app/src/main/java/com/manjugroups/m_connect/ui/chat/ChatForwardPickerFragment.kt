package com.manjugroups.m_connect.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentChatForwardPickerBinding
import com.manjugroups.m_connect.databinding.ItemChatForwardTargetBinding
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch

class ChatForwardPickerFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onForwardTo(targetConversationId: String?, targetChannelId: String?, targetName: String)
    }

    private var _binding: FragmentChatForwardPickerBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()
    private var listener: Listener? = null
    private val targets = mutableListOf<Target>()

    data class Target(
        val name: String,
        val subtitle: String,
        val conversationId: String?,
        val channelId: String?
    )

    fun setListener(l: Listener) { listener = l }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatForwardPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = TargetAdapter { target ->
            listener?.onForwardTo(target.conversationId, target.channelId, target.name)
            dismiss()
        }
        binding.rvForwardTargets.layoutManager = LinearLayoutManager(requireContext())
        binding.rvForwardTargets.adapter = adapter
        loadTargets(adapter)
    }

    private fun loadTargets(adapter: TargetAdapter) {
        val session = SessionManager(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val combined = mutableListOf<Target>()
            runCatching { api.getConversations(session.bearerToken) }
                .onSuccess { resp ->
                    resp.conversations.forEach { c ->
                        val id = c.id ?: return@forEach
                        val name = c.displayName?.takeIf { it.isNotBlank() }
                            ?: c.participants?.firstOrNull()?.name
                            ?: "Direct chat"
                        val sub = c.lastMessagePreview?.takeIf { it.isNotBlank() } ?: "Direct chat"
                        combined += Target(name, sub, conversationId = id, channelId = null)
                    }
                }
            runCatching { api.getChannels(session.bearerToken) }
                .onSuccess { resp ->
                    resp.channels.forEach { c ->
                        val id = c.id ?: return@forEach
                        val name = c.name?.takeIf { it.isNotBlank() } ?: "Channel"
                        val sub = c.type?.replaceFirstChar { it.uppercase() } ?: "Channel"
                        combined += Target(name, sub, conversationId = null, channelId = id)
                    }
                }
            if (_binding == null) return@launch
            targets.clear()
            targets.addAll(combined)
            adapter.submit(combined)
            binding.tvEmptyPicker.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(messageIds: List<String>) = ChatForwardPickerFragment().apply {
            arguments = Bundle().apply {
                putStringArrayList("messageIds", ArrayList(messageIds))
            }
        }
    }

    private class TargetAdapter(
        val onPick: (Target) -> Unit
    ) : RecyclerView.Adapter<TargetAdapter.VH>() {

        private val items = mutableListOf<Target>()

        fun submit(list: List<Target>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemChatForwardTargetBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.b.tvForwardName.text = t.name
            holder.b.tvForwardSub.text = t.subtitle
            holder.b.tvForwardAvatar.text = t.name.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "?" }
            holder.itemView.setOnClickListener { onPick(t) }
        }

        override fun getItemCount(): Int = items.size

        class VH(val b: ItemChatForwardTargetBinding) : RecyclerView.ViewHolder(b.root)
    }
}
