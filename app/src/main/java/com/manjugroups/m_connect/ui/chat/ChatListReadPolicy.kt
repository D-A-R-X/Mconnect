package com.manjugroups.m_connect.ui.chat

/** Fragment-local limits for optional list/avatar reads, never message or tracking writes. */
internal class ChatListReadPolicy {
    private var failures = 0
    private var nextReadAt = 0L
    private val photoAttempts = linkedMapOf<String, Long>()

    fun canRead(visible: Boolean, resumed: Boolean, now: Long): Boolean =
        visible && resumed && now >= nextReadAt

    fun onSuccess() {
        failures = 0
        nextReadAt = 0
    }

    fun onFailure(status: Int?, retryAfterSeconds: Long?, now: Long) {
        if (status != 429 && status != 503) return
        failures = (failures + 1).coerceAtMost(4)
        val backoff = (10_000L shl (failures - 1)).coerceAtMost(60_000L)
        val retryAfter = (retryAfterSeconds ?: 0).coerceIn(0, 600) * 1_000L
        nextReadAt = maxOf(nextReadAt, now + maxOf(backoff, retryAfter))
    }

    fun beginPhotoRead(staffId: String, now: Long): Boolean {
        if (now < nextReadAt) return false
        val previous = photoAttempts[staffId]
        if (previous != null && now - previous < 300_000L) return false
        photoAttempts.remove(staffId)
        if (photoAttempts.size >= 256) {
            photoAttempts.entries.removeAll { now - it.value >= 300_000L }
            if (photoAttempts.size >= 256) return false
        }
        photoAttempts[staffId] = now
        return true
    }
}
