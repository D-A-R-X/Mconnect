package com.manjugroups.m_connect.network

import android.os.Build
import com.manjugroups.m_connect.BuildConfig
import okhttp3.Interceptor

/**
 * Tells our backend which Mconnect build is calling, on every request, so
 * Settings → Mobile → Version can show what each staff member has installed
 * (sign-in requests already carried X-App-Build / X-App-Version).
 */
object AppBuildHeaders {

    // "OPPO CPH2363", but "Samsung SM-A515F" not "samsung Samsung SM-A515F".
    private val deviceModel: String = ascii(
        Build.MODEL.orEmpty().let { model ->
            val maker = Build.MANUFACTURER.orEmpty()
            if (maker.isBlank() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        },
    )
    private val osVersion: String = ascii(Build.VERSION.RELEASE.orEmpty())

    val interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("X-App-Build", BuildConfig.VERSION_CODE.toString())
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .header("X-App-Platform", "android")
            .apply {
                if (deviceModel.isNotEmpty()) header("X-Device-Model", deviceModel)
                if (osVersion.isNotEmpty()) header("X-OS-Version", osVersion)
            }
            .build()
        chain.proceed(request)
    }

    /** OkHttp rejects non-ASCII header values; some OEM model names have them. */
    private fun ascii(value: String): String =
        value.filter { it in ' '..'~' }.trim().take(80)
}
