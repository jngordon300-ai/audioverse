package com.jaygordonai.audioverse.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Jellyfin and Cloudflare Access credentials as HEADERS, never in the URL.
 *
 * Register with addNetworkInterceptor (not addInterceptor): network interceptors
 * run on every hop, including redirects, so the host check applies to the host
 * actually being contacted. OkHttp strips Authorization on cross-host redirects
 * but NOT custom headers like CF-Access-Client-Secret.
 *
 * Credentials are only ever attached over HTTPS.
 */
class SecureHeaderInterceptor(
    private val configProvider: () -> AuthConfig?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cfg = configProvider() ?: return chain.proceed(request)
        if (!request.url.isHttps) return chain.proceed(request)

        val host = request.url.host
        val builder = request.newBuilder()

        if (cfg.hasCloudflare && host.equals(cfg.tunnelHost, ignoreCase = true)) {
            builder.header("CF-Access-Client-Id", cfg.cfClientId!!)
            builder.header("CF-Access-Client-Secret", cfg.cfClientSecret!!)
        }
        if (cfg.hasJellyfin && host.equals(cfg.jellyfinHost, ignoreCase = true)) {
            builder.header(
                "Authorization",
                "MediaBrowser Client=\"AudioVerse\", Device=\"Android\", " +
                    "DeviceId=\"${cfg.deviceId}\", Version=\"0.1\", Token=\"${cfg.jellyfinToken}\""
            )
        }
        return chain.proceed(builder.build())
    }
}
