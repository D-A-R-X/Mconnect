package com.manjugroups.m_connect.ui.common

import com.manjugroups.m_connect.network.MobileStorageFiles

/**
 * Resolves profile-photo storage IDs through the MFPL storage compatibility
 * route. The route redirects both external and legacy files to short-lived
 * download URLs, which Coil follows without persisting the redirect target.
 */
object ProfilePhotos {
    fun resolve(value: String?): String? = MobileStorageFiles.resolve(value)
}
