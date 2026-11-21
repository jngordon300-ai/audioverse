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
    val deviceId: String = "audioverse-unknown"
) {
    val hasJellyfin: Boolean get() = !jellyfinHost.isNullOrBlank() && !jellyfinToken.isNullOrBlank()
    val hasCloudflare: Boolean get() =
        !tunnelHost.isNullOrBlank() && !cfClientId.isNullOrBlank() && !cfClientSecret.isNullOrBlank()

    fun isConfiguredHost(host: String): Boolean =
        host.equals(jellyfinHost, ignoreCase = true) || host.equals(tunnelHost, ignoreCase = true)

    override fun toString(): String =
        "AuthConfig(jellyfin=${if (hasJellyfin) "set" else "unset"}, cloudflare=${if (hasCloudflare) "set" else "unset"})"
}
