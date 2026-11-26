package com.jaygordonai.audioverse.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Council ruling U26: plain-http token sends need a VPN (or the trusted-Wi-Fi opt-in). */
class HeaderPolicyTest {
    private val cfg = AuthConfig(
        jellyfinHost = "media.example.com", jellyfinToken = "tok", tunnelHost = "media.example.com",
        cfClientId = "id", cfClientSecret = "sec", privateHost = "100.101.102.103"
    )

    @Test fun privateHttpWithoutVpnGetsNothingEvenIfTheServerIdIsReplayed() {
        // A device at the same private IP that echoes the real server ID is irrelevant here:
        // without the transport policy the request carries no credentials at all.
        assertTrue(SecureHeaderInterceptor.headersFor(cfg, https = false, host = "100.101.102.103", plainHttpAllowed = false).isEmpty())
    }

    @Test fun privateHttpWithVpnGetsTokenButNeverAccessKeys() {
        val h = SecureHeaderInterceptor.headersFor(cfg, https = false, host = "100.101.102.103", plainHttpAllowed = true)
        assertEquals(setOf("Authorization", "X-Emby-Token"), h.keys)
    }

    @Test fun otherHostsAndPublicHttpNeverGetCredentials() {
        assertTrue(SecureHeaderInterceptor.headersFor(cfg, false, "media.example.com", true).isEmpty())
        assertTrue(SecureHeaderInterceptor.headersFor(cfg, true, "cdn.other.com", true).isEmpty())
        assertTrue(SecureHeaderInterceptor.headersFor(cfg, false, "100.101.102.104", true).isEmpty())
    }

    @Test fun httpsTunnelGetsAccessKeysAndToken() {
        val h = SecureHeaderInterceptor.headersFor(cfg, true, "media.example.com", false)
        assertEquals(setOf("CF-Access-Client-Id", "CF-Access-Client-Secret", "Authorization", "X-Emby-Token"), h.keys)
    }

    @Test fun policyNeedsVpnOrTrustedWifi() {
        PrivateTransportPolicy.vpnActive = false; PrivateTransportPolicy.onWifi = true; PrivateTransportPolicy.trustHomeWifi = false
        assertEquals(false, PrivateTransportPolicy.plainHttpAllowed())
        PrivateTransportPolicy.trustHomeWifi = true
        assertEquals(true, PrivateTransportPolicy.plainHttpAllowed())
        PrivateTransportPolicy.onWifi = false
        assertEquals(false, PrivateTransportPolicy.plainHttpAllowed())
        PrivateTransportPolicy.vpnActive = true
        assertEquals(true, PrivateTransportPolicy.plainHttpAllowed())
        PrivateTransportPolicy.vpnActive = false; PrivateTransportPolicy.trustHomeWifi = false
    }
}
