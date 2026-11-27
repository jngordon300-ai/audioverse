package com.jaygordonai.audioverse.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvPresetsTest {
    @Test fun presetsAreHttpsWithUniqueNegativeIds() {
        assertEquals(6, IptvPresets.all.size)
        assertTrue(IptvPresets.all.all { it.url.startsWith("https://") && it.id < 0 })
        assertEquals(IptvPresets.all.size, IptvPresets.all.map { it.id }.toSet().size)
    }

    @Test fun adultGroupsAreDetectedWithoutFalseAlarms() {
        listOf("XXX", "Adult", "xxx;Movies", "18+", "Adult;Entertainment").forEach { assertTrue(it, IptvPresets.isAdultGroup(it)) }
        listOf("Kids", "Movies", "Sports & Outdoors", "Music", "Classic TV", "XXXTentacion fans").forEach { assertFalse(it, IptvPresets.isAdultGroup(it)) }
    }
}
