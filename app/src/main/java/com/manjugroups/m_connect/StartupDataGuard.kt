package com.manjugroups.m_connect

import android.content.Context
import android.util.Log
import com.manjugroups.m_connect.network.OfflineHttpCache
import com.manjugroups.m_connect.ui.common.LocalCache
import java.io.File

/**
 * Clears volatile, rebuildable local data once per version upgrade.
 *
 * Play-delivered AAB updates preserve app data, while most sideload smoke tests
 * start from a cleaner install. Keeping this guard small makes the update path
 * resilient to stale JSON/cache payloads without touching the authenticated
 * session or local tracking database.
 */
object StartupDataGuard {
    private const val TAG = "StartupDataGuard"
    private const val PREFS = "startup_data_guard"
    private const val KEY_LAST_CLEARED_VERSION = "last_cleared_version"

    private val FILES_CACHE_DIRS = listOf(
        "response_cache",
        "chat_cache",
        "chat_metadata",
    )

    private val CACHE_DIRS = listOf(
        "image_cache",
        "http_cache",
    )

    fun clearVolatileCachesAfterUpgrade(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_LAST_CLEARED_VERSION, -1) == currentVersion) return

        runCatching { LocalCache.clearAll(appContext) }
            .onFailure { Log.w(TAG, "LocalCache clear failed", it) }
        runCatching { OfflineHttpCache.clear() }
            .onFailure { Log.w(TAG, "HTTP cache clear failed", it) }

        FILES_CACHE_DIRS.forEach { name ->
            deleteDir(File(appContext.filesDir, name))
        }
        CACHE_DIRS.forEach { name ->
            deleteDir(File(appContext.cacheDir, name))
        }

        prefs.edit().putInt(KEY_LAST_CLEARED_VERSION, currentVersion).apply()
    }

    private fun deleteDir(dir: File) {
        if (!dir.exists()) return
        runCatching { dir.deleteRecursively() }
            .onFailure { Log.w(TAG, "Cache directory clear failed: ${dir.name}", it) }
    }
}
