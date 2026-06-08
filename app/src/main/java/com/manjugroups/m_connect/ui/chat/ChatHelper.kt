package com.manjugroups.m_connect.ui.chat

import com.manjugroups.m_connect.network.MessageData

data class MessagePreviewResult(
    val text: String,
    val iconResId: Int? = null
)

fun getMessagePreviewText(message: MessageData): String {
    if (message.isDeleted == true) {
        return "This message was deleted"
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

fun resolveMessagePreview(message: MessageData): MessagePreviewResult {
    if (message.isDeleted == true) {
        return MessagePreviewResult("This message was deleted", com.manjugroups.m_connect.R.drawable.ic_chat_delete)
    }
    
    val body = message.body.orEmpty().trim()
    
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
    
    return when {
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
