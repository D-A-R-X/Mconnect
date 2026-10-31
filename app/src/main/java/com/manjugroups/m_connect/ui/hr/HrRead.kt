package com.manjugroups.m_connect.ui.hr

import kotlinx.coroutines.CancellationException

internal suspend fun <T> hrRead(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
