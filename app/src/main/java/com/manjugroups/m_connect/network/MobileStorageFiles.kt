package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds the stable MFPL resolver URL for both legacy and external storage IDs. */
object MobileStorageFiles {
    fun resolve(value: String?): String? {
        val raw = value
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
            ?: return null

        val absolute = raw.toHttpUrlOrNull()
        if (absolute != null) {
            val storageId = storageIdFromKnownRoute(absolute)
            return storageId?.let(::urlForStorageId) ?: raw
        }

        if (raw.startsWith("/")) {
            val base = BuildConfig.STORAGE_BASE_URL.toHttpUrlOrNull() ?: return null
            val relative = base.resolve(raw)
            val storageId = relative?.let(::storageIdFromKnownRoute)
            return storageId?.let(::urlForStorageId) ?: relative?.toString()
        }

        return urlForStorageId(raw)
    }

    fun urlForStorageId(storageId: String): String? {
        val cleanId = storageId.trim().takeIf(String::isNotEmpty) ?: return null
        val base = BuildConfig.STORAGE_BASE_URL.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegments("api/storage/files")
            .addPathSegment(cleanId)
            .build()
            .toString()
    }

    private fun storageIdFromKnownRoute(url: HttpUrl): String? {
        url.queryParameter("storageId")?.takeIf(String::isNotBlank)?.let { return it }

        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.size >= 4 &&
            segments[0] == "api" &&
            segments[1] == "storage" &&
            segments[2] == "files"
        ) {
            return segments.drop(3).joinToString("/").takeIf(String::isNotBlank)
        }

        if (segments.size == 3 &&
            segments[0] == "api" &&
            segments[1] == "storage" &&
            url.host.contains("convex", ignoreCase = true)
        ) {
            return segments[2].takeIf(String::isNotBlank)
        }

        return null
    }
}
