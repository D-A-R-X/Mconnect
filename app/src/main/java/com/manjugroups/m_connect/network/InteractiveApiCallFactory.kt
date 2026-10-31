package com.manjugroups.m_connect.network

import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

internal class InteractiveApiCallFactory(private val reads: OkHttpClient) : Call.Factory {
    // Keep submissions and form shift lookup out of the background-read queue.
    // Preserve bounded limits, connection pool, interceptors and session policy.
    private val writes = reads.newBuilder().dispatcher(Dispatcher().apply {
        maxRequests = reads.dispatcher.maxRequests
        maxRequestsPerHost = reads.dispatcher.maxRequestsPerHost
    }).build()

    override fun newCall(request: Request): Call {
        val backgroundRead = when (request.method) {
            "GET", "HEAD", "OPTIONS" -> request.url.encodedPath != "/api/hr/shifts/today"
            else -> false
        }
        return (if (backgroundRead) reads else writes).newCall(request)
    }
}
