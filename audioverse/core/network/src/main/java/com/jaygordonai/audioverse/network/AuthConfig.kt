package com.jaygordonai.audioverse.network

/**
 * Credentials for one home-server connection. Hosts are bare hostnames (no scheme).
 * toString() is overridden so a stray log line or crash report never prints secrets.
 */
data class AuthConfig(
    val jellyfinHost: String? = null,
    val jellyfinToken: String? = null,
    val tunnelHost: String? = null,
    val cfClientId: String? = null,
    val cfClientSecret: String? = null,
    val deviceId: String = "audioverse-unknown",
    /**
     * The same Jellyfin server's address on a private network (home Wi-Fi or a VPN mesh),
     * as a literal IP. The token may go there over plain http because the VPN or home
     * network carries it; see SecureHeaderInterceptor.
     */
    val privateHost: String? = null
) {
    val hasJellyfin: Boolean get() = !jellyfinHost.isNullOrBlank() && !jellyfinToken.isNullOrBlank()
    val hasCloudflare: Boolean get() =
        !tunnelHost.isNullOrBlank() && !cfClientId.isNullOrBlank() && !cfClientSecret.isNullOrBlank()

    fun isConfiguredHost(host: String): Boolean =
        host.equals(jellyfinHost, ignoreCase = true) || host.equals(tunnelHost, ignoreCase = true) ||
            (privateHost != null && host.equals(privateHost, ignoreCase = true))

    /** True only for the saved private address, and only when it really is a private IP. */
    fun isPrivateJellyfinHost(host: String): Boolean =
        !privateHost.isNullOrBlank() && host.equals(privateHost, ignoreCase = true) && PrivateNet.isPrivateAddress(host)

    override fun toString(): String =
        "AuthConfig(jellyfin=${if (hasJellyfin) "set" else "unset"}, cloudflare=${if (hasCloudflare) "set" else "unset"})"
}
