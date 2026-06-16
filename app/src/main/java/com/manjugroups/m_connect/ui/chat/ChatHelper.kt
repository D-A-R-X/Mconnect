package com.manjugroups.m_connect.ui.chat

import com.manjugroups.m_connect.network.MessageData

data class MessagePreviewResult(
    val text: String,
    val iconResId: Int? = null
)

object DeletedMessagesTracker {
    private const val PREFS_NAME = "deleted_messages_prefs"
    private const val KEY_DELETED_IDS = "deleted_message_ids"
    private const val PREFIX_CONV_DEL_TIME = "deleted_conv_time_"

    fun markAsDeleted(context: android.content.Context, messageId: String?) {
        if (messageId.isNullOrBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DELETED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(messageId)
        prefs.edit().putStringSet(KEY_DELETED_IDS, current).apply()
    }

    fun isDeleted(context: android.content.Context, messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DELETED_IDS, emptySet()) ?: emptySet()
        return current.contains(messageId)
    }

    fun markPreviewDeleted(context: android.content.Context, chatId: String?, timestamp: Long) {
        if (chatId.isNullOrBlank() || timestamp <= 0L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val current = prefs.getLong(PREFIX_CONV_DEL_TIME + chatId, 0L)
        if (timestamp > current) {
            prefs.edit().putLong(PREFIX_CONV_DEL_TIME + chatId, timestamp).apply()
        }
    }

    fun getDeletedTimestamp(context: android.content.Context, chatId: String?): Long {
        if (chatId.isNullOrBlank()) return 0L
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getLong(PREFIX_CONV_DEL_TIME + chatId, 0L)
    }
}

private fun parseContactPreview(body: String): MessagePreviewResult? {
    val clean = body.trim()
    val isContact = clean.startsWith("📇") || 
                    clean.contains("📇") || 
                    clean.contains("Contact", ignoreCase = true)
                    
    if (!isContact) return null
    
    val lines = clean.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val name = if (lines.size > 1) {
        if (lines[0].contains("Contact", ignoreCase = true) || lines[0].contains("📇")) {
            lines[1]
        } else {
            lines[0]
        }
    } else {
        ""
    }
    
    // Clean name of phone label/digits/plus/colons
    val cleanedName = if (name.contains(":") || name.contains("+") || name.any { it.isDigit() }) "" else name
    
    val text = if (cleanedName.isNotEmpty()) {
        "Contact: $cleanedName"
    } else {
        val stripped = clean.replace("📇", "").replace("\n", " ").trim()
        val displayStr = if (stripped.startsWith("Contact", ignoreCase = true)) {
            stripped
        } else {
            "Contact: $stripped"
        }
        
        if (displayStr.startsWith("Contact...", ignoreCase = true)) {
            "Contact"
        } else if (displayStr.contains("Mobile:") || displayStr.contains("Home:") || displayStr.contains("Work:") || displayStr.contains("+") || displayStr.any { it.isDigit() }) {
            "Contact"
        } else {
            displayStr
        }
    }
    
    return MessagePreviewResult(text, com.manjugroups.m_connect.R.drawable.ic_attach_contact)
}

fun getMessagePreviewText(message: MessageData): String {
    if (message.isDeleted == true) {
        return "This message was deleted"
    }
    val body = message.body.orEmpty().trim()
    val contactPreview = parseContactPreview(body)
    if (contactPreview != null) {
        return contactPreview.text
    }
    if (!message.body.isNullOrBlank()) {
        return message.body
    }
    val attachments = message.attachments
    if (!attachments.isNullOrEmpty()) {
        val first = attachments.first()
        val mime = first.fileType.orEmpty().lowercase()
        val name = first.fileName.orEmpty().lowercase()
        
        val isAudio = mime.startsWith("audio/") || 
                      name.endsWith(".m4a") || 
                      name.endsWith(".mp3") || 
                      name.endsWith(".wav") || 
                      name.endsWith(".aac") || 
                      name.endsWith(".caf") || 
                      name.endsWith(".ogg") || 
                      name.endsWith(".opus") || 
                      name.startsWith("voice-") || 
                      name.startsWith("voice_message_")
                      
        if (isAudio) {
            return "🎙️ Voice message"
        }
        
        val isVideo = mime.startsWith("video/") || 
                      name.endsWith(".mp4") || 
                      name.endsWith(".mkv") || 
                      name.endsWith(".mov") || 
                      name.endsWith(".3gp")
        if (isVideo) {
            return "🎥 Video"
        }
        
        val isImage = mime.startsWith("image/") || 
                      name.endsWith(".jpg") || 
                      name.endsWith(".jpeg") || 
                      name.endsWith(".png") || 
                      name.endsWith(".gif") || 
                      name.endsWith(".webp")
        if (isImage) {
            return "📷 Photo"
        }
        
        return "📁 " + (first.fileName ?: "Attachment")
    }
    return ""
}

fun resolveMessagePreview(message: MessageData, selfStaffId: String? = null): MessagePreviewResult {
    if (message.isDeleted == true) {
        val isMine = selfStaffId != null && message.senderId == selfStaffId
        val text = if (isMine) "You deleted this message" else "This message was deleted"
        return MessagePreviewResult(text, com.manjugroups.m_connect.R.drawable.ic_chat_msg_deleted)
    }
    
    val body = message.body.orEmpty().trim()
    
    val contactPreview = parseContactPreview(body)
    if (contactPreview != null) {
        return contactPreview
    }
    
    // Check for explicit call or location keywords in the body
    if (body == "Voice call" || body.contains("Incoming voice call") || body.contains("Outgoing voice call")) {
        return MessagePreviewResult("Voice call", com.manjugroups.m_connect.R.drawable.ic_phone_outline)
    }
    if (body == "Video Call" || body.contains("Incoming video call") || body.contains("Outgoing video call")) {
        return MessagePreviewResult("Video Call", com.manjugroups.m_connect.R.drawable.ic_chat_video)
    }
    if (body == "Location" || body.startsWith("Location:") || body.contains("maps.google.com")) {
        return MessagePreviewResult("Location", com.manjugroups.m_connect.R.drawable.ic_location_pin)
    }
    
    val attachments = message.attachments
    if (!attachments.isNullOrEmpty()) {
        val first = attachments.first()
        val mime = first.fileType.orEmpty().lowercase()
        val name = first.fileName.orEmpty().lowercase()
        
        val isAudio = mime.startsWith("audio/") || 
                      name.endsWith(".m4a") || 
                      name.endsWith(".mp3") || 
                      name.endsWith(".wav") || 
                      name.endsWith(".aac") || 
                      name.endsWith(".caf") || 
                      name.endsWith(".ogg") || 
                      name.endsWith(".opus") || 
                      name.startsWith("voice-") || 
                      name.startsWith("voice_message_")
                      
        if (isAudio) {
            val durationText = if (name.contains("0_") || name.contains("0-")) {
                name.substringAfter("voice_message_").substringBefore(".wav").replace("_", ":").replace("-", ":")
            } else {
                "Voice message"
            }
            return MessagePreviewResult(if (durationText.contains(":")) durationText else "Voice message", com.manjugroups.m_connect.R.drawable.ic_chat_mic)
        }
        
        val isVideo = mime.startsWith("video/") || 
                      name.endsWith(".mp4") || 
                      name.endsWith(".mkv") || 
                      name.endsWith(".mov") || 
                      name.endsWith(".3gp")
        if (isVideo) {
            return MessagePreviewResult("Video Call", com.manjugroups.m_connect.R.drawable.ic_chat_video)
        }
        
        val isImage = mime.startsWith("image/") || 
                      name.endsWith(".jpg") || 
                      name.endsWith(".jpeg") || 
                      name.endsWith(".png") || 
                      name.endsWith(".gif") || 
                      name.endsWith(".webp")
        if (isImage) {
            return MessagePreviewResult("Photo", com.manjugroups.m_connect.R.drawable.ic_chat_camera)
        }
        
        val displayName = first.fileName ?: "Attachment"
        return MessagePreviewResult(displayName, com.manjugroups.m_connect.R.drawable.ic_chat_file)
    }
    
    return MessagePreviewResult(body, null)
}

fun resolveRawPreviewText(preview: String): MessagePreviewResult {
    val clean = preview.trim()
    if (clean.isBlank()) return MessagePreviewResult("", null)
    
    val contactPreview = parseContactPreview(clean)
    if (contactPreview != null) {
        return contactPreview
    }
    
    return when {
        clean.contains("deleted this message", ignoreCase = true) || clean.contains("message was deleted", ignoreCase = true) ->
            MessagePreviewResult(clean, com.manjugroups.m_connect.R.drawable.ic_chat_msg_deleted)

        clean.contains("Location", ignoreCase = true) || clean.contains("📍") -> 
            MessagePreviewResult("Location", com.manjugroups.m_connect.R.drawable.ic_location_pin)
            
        clean.contains(".apk", ignoreCase = true) -> 
            MessagePreviewResult(clean, com.manjugroups.m_connect.R.drawable.ic_chat_file)
            
        clean.contains("Voice call", ignoreCase = true) -> 
            MessagePreviewResult("Voice call", com.manjugroups.m_connect.R.drawable.ic_phone_outline)
            
        clean.contains("Video Call", ignoreCase = true) -> 
            MessagePreviewResult("Video Call", com.manjugroups.m_connect.R.drawable.ic_chat_video)
            
        clean.contains("Voice message", ignoreCase = true) -> 
            MessagePreviewResult("Voice message", com.manjugroups.m_connect.R.drawable.ic_chat_mic)
            
        clean.contains("Photo", ignoreCase = true) || clean.contains("📷") || clean.contains("Image", ignoreCase = true) -> 
            MessagePreviewResult("Photo", com.manjugroups.m_connect.R.drawable.ic_chat_camera)
            
        clean.contains("Video", ignoreCase = true) || clean.contains("🎥") -> 
            MessagePreviewResult("Video", com.manjugroups.m_connect.R.drawable.ic_chat_video)
            
        clean.contains("Attachment", ignoreCase = true) || clean.contains("📁") || clean.contains(".pdf", ignoreCase = true) || clean.contains(".docx", ignoreCase = true) -> 
            MessagePreviewResult(clean, com.manjugroups.m_connect.R.drawable.ic_chat_file)
            
        else -> MessagePreviewResult(clean, null)
    }
}
