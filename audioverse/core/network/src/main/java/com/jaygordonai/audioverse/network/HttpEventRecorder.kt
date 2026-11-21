package com.jaygordonai.audioverse.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records a privacy-safe summary of each HTTP exchange for the gate tests.
 * Stores no URLs, hostnames, headers or bodies: only whether the request went to
 * the configured server, the status code, and security flags.
 *
 * Register as a network interceptor AFTER SecureHeaderInterceptor so it sees the
 * final request.
 */
class HttpEventRecorder(
    private val configProvider: () -> AuthConfig?
) : Interceptor {

    data class Event(
        val toConfiguredServer: Boolean,
        val status: Int,
        val rangeRequested: Boolean,
        val credentialsAttached: Boolean,
        val tokenInUrl: Boolean,
        val https: Boolean
    )

    private val events = CopyOnWriteArrayList<Event>()

    fun snapshot(): List<Event> = events.toList()
    fun clear() = events.clear()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val response = chain.proceed(req)
        val cfg = configProvider()
        val tokenInUrl = req.url.queryParameterNames.any {
            it.equals("api_key", true) || it.equals("apikey", true) || it.equals("token", true)
        }
        events += Event(
            toConfiguredServer = cfg?.isConfiguredHost(req.url.host) == true,
            status = response.code,
            rangeRequested = req.header("Range") != null,
            credentialsAttached = req.header("Authorization") != null || req.header("CF-Access-Client-Id") != null,
            tokenInUrl = tokenInUrl,
            https = req.url.isHttps
        )
        if (events.size > 500) events.removeAt(0)
        return response
    }
}
