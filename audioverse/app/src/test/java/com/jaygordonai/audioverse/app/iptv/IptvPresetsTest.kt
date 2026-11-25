package com.jaygordonai.audioverse.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvPresetsTest {

    @Test fun presetsAreHttpsWithoutLogins() {
        IptvPresets.all.forEach { p ->
            assertTrue("${p.name} must be https", p.url.startsWith("https://"))
            assertFalse("${p.name} must not carry a login", Regex("(?i)(username|password|token|key)=").containsMatchIn(p.url))
            assertTrue(M3uParser.isWebUrl(p.url))
        }
    }

    @Test fun idsAreUniqueAndSmall() {
        val ids = IptvPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 1L..1000L })
        assertTrue(IptvPresets.isPreset(1L))
        assertFalse(IptvPresets.isPreset(System.currentTimeMillis()))
    }
}
