package com.jaygordonai.audioverse.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateNetTest {
    @Test fun privateRangesAndVpnMeshes() {
        listOf("10.0.0.5", "172.16.1.2", "172.31.255.1", "192.168.1.50", "100.64.0.1", "100.101.102.103", "100.127.255.254", "fd7a:115c:a1e0::1", "[fd00::2]")
            .forEach { assertTrue(it, PrivateNet.isPrivateAddress(it)) }
    }

    @Test fun publicAddressesAndHostnamesNeverQualify() {
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.1.1", "media.jaygordonai.com", "mac-mini.local", "localhost", "010.0.0.1", "10.0.0", "10.0.0.256", "2001:4860::8888")
            .forEach { assertFalse(it, PrivateNet.isPrivateAddress(it)) }
    }

    @Test fun tokenOnlyGoesToTheSavedPrivateIp() {
        val cfg = AuthConfig(jellyfinHost = "media.example.com", jellyfinToken = "t", privateHost = "100.101.102.103")
        assertTrue(cfg.isPrivateJellyfinHost("100.101.102.103"))
        assertFalse(cfg.isPrivateJellyfinHost("100.101.102.104"))
        assertFalse(AuthConfig(privateHost = "mac-mini.local").isPrivateJellyfinHost("mac-mini.local"))
        assertFalse(cfg.toString().contains("t\""))
    }
}
