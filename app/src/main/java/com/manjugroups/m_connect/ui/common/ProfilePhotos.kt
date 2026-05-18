package com.manjugroups.m_connect.ui.common

import com.manjugroups.m_connect.BuildConfig

/**
 * The server stores staff.photo as a Convex storage ID (not a URL). The
 * upload endpoint returns a `photo.url` shaped as
 * `${BASE_URL}api/storage/serve?storageId=<id>` — but subsequent
 * `getStaffDetail` calls only return the storage ID itself, which Coil
 * can't load directly. Use this helper anywhere we render a photo so
 * either form (full URL or bare storage ID) renders correctly.
 */
object ProfilePhotos {
    fun resolve(value: String?): String? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = BuildConfig.BASE_URL.removeSuffix("/")
        return "$base/api/storage/serve?storageId=$raw"
    }
}
