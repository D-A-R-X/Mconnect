package com.manjugroups.m_connect.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.manjugroups.m_connect.network.ChannelData
import com.manjugroups.m_connect.network.ConversationData
import com.manjugroups.m_connect.network.StaffFullData
import java.io.File

/**
 * On-disk JSON cache for chat metadata that hydrates the conversation header
 * and contact-info screen instantly. Separate buckets:
 *
 *  - chat snapshot keyed by `channel-<id>` / `conversation-<id>` — title,
 *    subtitle, photo URL, muted state, last update timestamp.
 *  - channel/conversation API responses keyed the same way — used by the
 *    contact info screen.
 *  - staff details keyed by staff id — used by the contact info screen and
 *    the conversation header avatar.
 */
object ChatMetadataCache {

    private const val DIR = "chat_metadata"
    private val gson = Gson()

    data class ChatSnapshot(
        val title: String?,
        val subtitle: String?,
        val photoUrl: String?,
        val initials: String?,
        val muted: Boolean,
        val otherStaffId: String?,
        val updatedAt: Long
    )

    private fun root(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun bucket(context: Context, name: String): File =
        File(root(context), name).apply { if (!exists()) mkdirs() }

    private fun sanitize(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    private inline fun <reified T> read(file: File): T? {
        if (!file.exists()) return null
        return runCatching {
            file.readText().takeIf { it.isNotBlank() }?.let { gson.fromJson(it, T::class.java) }
        }.getOrNull()
    }

    private fun write(file: File, value: Any) {
        runCatching { file.writeText(gson.toJson(value)) }
    }

    // ── Chat snapshot (header) ────────────────────────────────────────────

    fun loadChatSnapshot(context: Context, chatKey: String?): ChatSnapshot? {
        val key = chatKey?.takeIf { it.isNotBlank() } ?: return null
        return read(File(bucket(context, "snapshot"), "${sanitize(key)}.json"))
    }

    fun saveChatSnapshot(context: Context, chatKey: String?, snapshot: ChatSnapshot) {
        val key = chatKey?.takeIf { it.isNotBlank() } ?: return
        write(File(bucket(context, "snapshot"), "${sanitize(key)}.json"), snapshot)
    }

    // ── Channel detail ────────────────────────────────────────────────────

    fun loadChannel(context: Context, channelId: String): ChannelData? =
        read(File(bucket(context, "channel"), "${sanitize(channelId)}.json"))

    fun saveChannel(context: Context, channelId: String, channel: ChannelData) {
        write(File(bucket(context, "channel"), "${sanitize(channelId)}.json"), channel)
    }

    // ── Conversation detail ───────────────────────────────────────────────

    fun loadConversation(context: Context, conversationId: String): ConversationData? =
        read(File(bucket(context, "conversation"), "${sanitize(conversationId)}.json"))

    fun saveConversation(context: Context, conversationId: String, conversation: ConversationData) {
        write(File(bucket(context, "conversation"), "${sanitize(conversationId)}.json"), conversation)
    }

    // ── Staff detail ──────────────────────────────────────────────────────

    fun loadStaff(context: Context, staffId: String): StaffFullData? =
        read(File(bucket(context, "staff"), "${sanitize(staffId)}.json"))

    fun saveStaff(context: Context, staffId: String, staff: StaffFullData) {
        write(File(bucket(context, "staff"), "${sanitize(staffId)}.json"), staff)
    }
}
