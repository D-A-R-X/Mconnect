package com.manjugroups.m_connect.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** This client is never shared with application API clients or their auth interceptors. */
internal object PostHogTransport {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    fun request(payload: String): Request = Request.Builder().url(PostHogConfig.BATCH_URL)
        .post(payload.toRequestBody("application/json".toMediaType())).build()
}
