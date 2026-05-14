package com.manjugroups.m_connect.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.manjugroups.m_connect.network.MessageData
import java.io.File

/**
 * Simple per-chat JSON cache stored under filesDir/chat_cache/.
 * Holds the last MAX_CACHED_MESSAGES messages so a re-entry paints instantly
 * while the network refresh runs in background.
 */
object ChatMessageCache {

    private const val DIR = "chat_cache"
    private const val MAX_CACHED_MESSAGES = 100
    private val gson = Gson()
    private val type = object : TypeToken<List<MessageData>>() {}.type

    private fun cacheDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, chatKey: String): File =
        File(cacheDir(context), "${sanitize(chatKey)}.json")

    private fun sanitize(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    fun load(context: Context, chatKey: String?): List<MessageData> {
        val key = chatKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        val file = fileFor(context, key)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readText().takeIf { it.isNotBlank() }?.let {
                gson.fromJson<List<MessageData>>(it, type)
            }.orEmpty()
        }.getOrElse { emptyList() }
    }

    fun save(context: Context, chatKey: String?, messages: List<MessageData>) {
        val key = chatKey?.takeIf { it.isNotBlank() } ?: return
        val toStore = if (messages.size > MAX_CACHED_MESSAGES) {
            messages.takeLast(MAX_CACHED_MESSAGES)
        } else {
            messages
        }
        runCatching {
            val file = fileFor(context, key)
            file.writeText(gson.toJson(toStore))
        }
    }

    fun clear(context: Context, chatKey: String?) {
        val key = chatKey?.takeIf { it.isNotBlank() } ?: return
        runCatching { fileFor(context, key).delete() }
    }
}
