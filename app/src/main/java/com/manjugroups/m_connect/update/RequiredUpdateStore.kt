package com.manjugroups.m_connect.update

import android.content.Context

/**
 * A forced update this phone must install before the app can be used again.
 *
 * Saved on the device the moment the server (minimum build) or Google Play
 * (a newer build offered to this account) says the installed build is too old,
 * so closing and reopening the app, starting offline, or Play's update check
 * briefly answering "no update" can never make the Update page disappear.
 * It clears only when the installed build reaches [build], or the server
 * explicitly drops its minimum (see [RequiredUpdatePolicy]).
 */
data class RequiredUpdate(
    /** Required versionCode. */
    val build: Int,
    /** Shown to staff, e.g. "1.0 (109)". */
    val version: String,
    /** Store page to open from the Update button. */
    val url: String,
    /** Set by the server's minimum build (/api/mobile/app-version). */
    val fromServer: Boolean,
    /** Set by Google Play offering a newer build to this account. */
    val fromPlay: Boolean,
    val savedAtMs: Long,
)

/** Pure decisions for the forced-update gate (unit-tested). */
object RequiredUpdatePolicy {
    /**
     * A Play-only requirement is dropped only after Play has said "no update"
     * and the server has no minimum for this long (a halted rollout), never on
     * a single flaky answer.
     */
    const val PLAY_ONLY_STALE_MS = 72L * 60 * 60 * 1000

    /** The requirement to enforce now, or null when the installed build is new enough. */
    fun enforce(stored: RequiredUpdate?, installedBuild: Int): RequiredUpdate? =
        stored?.takeIf { it.build > installedBuild }

    /** Fresh, successful server answer: its minimum replaces the server part; "none" drops it. */
    fun withServer(stored: RequiredUpdate?, server: RequiredUpdate?, installedBuild: Int): RequiredUpdate? {
        val playPart = stored?.takeIf { it.fromPlay }?.copy(fromServer = false)
        return enforce(merge(playPart, server?.copy(fromServer = true, fromPlay = false)), installedBuild)
    }

    /** Play offers [availableBuild]: it becomes required (the higher of Play and server wins). */
    fun withPlayOffer(
        stored: RequiredUpdate?,
        availableBuild: Int,
        url: String,
        installedBuild: Int,
        nowMs: Long,
    ): RequiredUpdate? {
        if (availableBuild <= installedBuild) return enforce(stored, installedBuild)
        val existingPlay = stored?.takeIf { it.fromPlay && it.build == availableBuild }
        val play = RequiredUpdate(
            build = availableBuild,
            version = "build $availableBuild",
            url = url,
            fromServer = false,
            fromPlay = true,
            // Keep the first time Play offered this build (for the stale rule).
            savedAtMs = existingPlay?.savedAtMs ?: nowMs,
        )
        return enforce(merge(stored, play), installedBuild)
    }

    /**
     * Play says there is no update. A server minimum always stays; a Play-only
     * requirement stays unless the server also has none and it is older than
     * [PLAY_ONLY_STALE_MS].
     */
    fun withPlayNoUpdate(
        stored: RequiredUpdate?,
        serverHasNoMinimum: Boolean,
        installedBuild: Int,
        nowMs: Long,
    ): RequiredUpdate? {
        val current = enforce(stored, installedBuild) ?: return null
        if (current.fromServer) return current
        val stale = serverHasNoMinimum && nowMs - current.savedAtMs >= PLAY_ONLY_STALE_MS
        return if (stale) null else current
    }

    private fun merge(a: RequiredUpdate?, b: RequiredUpdate?): RequiredUpdate? = when {
        a == null -> b
        b == null -> a
        a.build == b.build -> a.copy(
            fromServer = a.fromServer || b.fromServer,
            fromPlay = a.fromPlay || b.fromPlay,
            // The server's store link and version text are the configured ones.
            url = if (b.fromServer) b.url else a.url,
            version = if (b.fromServer) b.version else a.version,
            savedAtMs = minOf(a.savedAtMs, b.savedAtMs),
        )
        a.build > b.build -> a
        else -> b
    }
}

/** SharedPreferences persistence for [RequiredUpdate] (same file the old remote policy used). */
object RequiredUpdateStore {
    private const val PREFS = "mandatory_app_update"
    private const val KEY_VERSION = "version"
    private const val KEY_BUILD = "build"
    private const val KEY_URL = "url"
    private const val KEY_FROM_SERVER = "from_server"
    private const val KEY_FROM_PLAY = "from_play"
    private const val KEY_SAVED_AT = "saved_at"

    /** The stored requirement still in force for [installedBuild]; clears a satisfied one. */
    fun load(context: Context, installedBuild: Int): RequiredUpdate? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val build = prefs.getInt(KEY_BUILD, 0)
        val url = prefs.getString(KEY_URL, null)
        if (build <= 0 || url.isNullOrBlank()) return null
        val stored = RequiredUpdate(
            build = build,
            version = prefs.getString(KEY_VERSION, null) ?: "build $build",
            url = url,
            // Rows saved before these flags existed came from the server policy.
            fromServer = prefs.getBoolean(KEY_FROM_SERVER, !prefs.contains(KEY_FROM_PLAY)),
            fromPlay = prefs.getBoolean(KEY_FROM_PLAY, false),
            savedAtMs = prefs.getLong(KEY_SAVED_AT, System.currentTimeMillis()),
        )
        val active = RequiredUpdatePolicy.enforce(stored, installedBuild)
        if (active == null) prefs.edit().clear().apply()
        return active
    }

    fun save(context: Context, update: RequiredUpdate?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            clear()
            if (update != null) {
                putInt(KEY_BUILD, update.build)
                putString(KEY_VERSION, update.version)
                putString(KEY_URL, update.url)
                putBoolean(KEY_FROM_SERVER, update.fromServer)
                putBoolean(KEY_FROM_PLAY, update.fromPlay)
                putLong(KEY_SAVED_AT, update.savedAtMs)
            }
        }.commit()
    }
}
