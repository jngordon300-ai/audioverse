package com.jaygordonai.audioverse.network

/**
 * Recognises private-network addresses: home Wi-Fi (10/8, 172.16/12, 192.168/16) and
 * VPN meshes such as Tailscale or Meshnet (100.64/10, IPv6 fc00::/7). Only literal IP
 * addresses count: a hostname could be pointed anywhere, so it never qualifies.
 */
object PrivateNet {
    fun isPrivateAddress(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return h.lowercase().let { it.startsWith("fc") || it.startsWith("fd") }
        val p = h.split('.')
        if (p.size != 4) return false
        val o = p.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
        if (p.any { it.length > 1 && it.startsWith("0") }) return false // no octal-looking tricks
        return when {
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            else -> false
        }
    }
}
