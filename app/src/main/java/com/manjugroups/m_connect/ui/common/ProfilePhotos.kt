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
        val raw = value?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" } ?: return null
        
        var resolved = raw
        val base = BuildConfig.BASE_URL.removeSuffix("/")
        
        // Fix localhost URLs from dev backend
        if (resolved.startsWith("http://127.0.0.1") || resolved.startsWith("http://localhost")) {
            val path = resolved.substringAfter("api/storage/")
            resolved = "$base/api/storage/$path"
        }
        
        // Fix 404 issue on convex-http domain for storage
        if (resolved.contains("convex-http.aivida.in")) {
            resolved = resolved.replace("convex-http.aivida.in", "convex-mms.aivida.in")
        }
        
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) return resolved
        
        if (resolved.startsWith("/")) {
            return "$base$resolved"
        }
        return "$base/api/storage/serve?storageId=$resolved"
    }
}
