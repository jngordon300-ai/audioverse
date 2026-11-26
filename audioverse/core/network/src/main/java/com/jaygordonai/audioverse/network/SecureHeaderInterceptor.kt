package com.jaygordonai.audioverse.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * When the home-server token may travel over plain http to the saved private IP.
 * Council ruling U26: the server-ID check is only an accident guard (the ID is public and
 * can be replayed), so plain http additionally requires that Android reports the active
 * network as a VPN (Tailscale, Meshnet…), or that the listener switched on "Trusted home
 * Wi-Fi" (default off) and the phone is on Wi-Fi. Otherwise the request goes out with no
 * credentials (fail closed). The app keeps these flags current from ConnectivityManager.
 */
object PrivateTransportPolicy {
    @Volatile var vpnActive: Boolean = false
    @Volatile var onWifi: Boolean = false
    @Volatile var trustHomeWifi: Boolean = false

    fun plainHttpAllowed(): Boolean = vpnActive || (trustHomeWifi && onWifi)
}

/**
 * Adds Jellyfin / Emby and Cloudflare Access credentials as HEADERS, never in the URL.
 *
 * Register with addNetworkInterceptor (not addInterceptor): network interceptors
 * run on every hop, including redirects, so the host check applies to the host
 * actually being contacted. OkHttp strips Authorization on cross-host redirects
 * but NOT custom headers like CF-Access-Client-Secret.
 *
 * Credentials go over HTTPS, or over plain http only to the saved private-network IP
 * while PrivateTransportPolicy allows it.
 */
class SecureHeaderInterceptor(
    private val configProvider: () -> AuthConfig?,
    private val plainHttpAllowed: () -> Boolean = PrivateTransportPolicy::plainHttpAllowed
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cfg = configProvider() ?: return chain.proceed(request)
        val headers = headersFor(cfg, request.url.isHttps, request.url.host, plainHttpAllowed())
        if (headers.isEmpty()) return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return chain.proceed(builder.build())
    }

    companion object {
        /** Pure decision (unit tested): which credential headers this hop may carry. */
        fun headersFor(cfg: AuthConfig, https: Boolean, host: String, plainHttpAllowed: Boolean): Map<String, String> {
            val privateHop = cfg.isPrivateJellyfinHost(host) && plainHttpAllowed
            if (!https && !privateHop) return emptyMap()
            val out = LinkedHashMap<String, String>()
            if (cfg.hasCloudflare && https && host.equals(cfg.tunnelHost, ignoreCase = true)) {
                out["CF-Access-Client-Id"] = cfg.cfClientId!!
                out["CF-Access-Client-Secret"] = cfg.cfClientSecret!!
            }
            if (cfg.hasJellyfin && ((https && host.equals(cfg.jellyfinHost, ignoreCase = true)) || privateHop)) {
                // Jellyfin reads Authorization; Emby reads X-Emby-Token. Both are headers, never URL parameters.
                out["Authorization"] = "MediaBrowser Client=\"AudioVerse\", Device=\"Android\", " +
                    "DeviceId=\"${cfg.deviceId}\", Version=\"0.12\", Token=\"${cfg.jellyfinToken}\""
                out["X-Emby-Token"] = cfg.jellyfinToken!!
            }
            return out
        }
    }
}
