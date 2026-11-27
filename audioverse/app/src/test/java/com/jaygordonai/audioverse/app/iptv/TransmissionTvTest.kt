package com.jaygordonai.audioverse.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransmissionTvTest {
    private var n = 0
    private fun ch(name: String, group: String = "Other") = IptvChannel(n, name, "https://s.example/${n++}", group)

    private val pluto = TransmissionTv.fastLists[0]
    private val samsung = TransmissionTv.fastLists[1]
    private val usIptvOrg = TransmissionTv.usLists.first { it.endsWith("/us.m3u") }
    private val jamaica = TransmissionTv.caribbean.first()
    private val nigeria = TransmissionTv.africa.first()

    @Test fun groupsAreAssignedInOrderAndDeduplicatedByName() {
        val lineup = TransmissionTv.curate(mapOf(
            pluto to listOf(ch("CBS News Philadelphia"), ch("CNN HEADLINES"), ch("BET Cinema"), ch("Vevo R&B"), ch("Cooking 24/7")),
            samsung to listOf(ch("TYT Network"), ch("Vevo Hip-Hop"), ch("XITE Gospel"), ch("Bounce XL")),
            usIptvOrg to listOf(ch("CBS News Philadelphia (720p)"), ch("PhillyCAM (720p)")),
            jamaica to listOf(ch("TVJ (720p) [Not 24/7]")),
            nigeria to listOf(ch("Channels 24"))
        ))
        val byGroup = lineup.groupBy { it.group }.mapValues { (_, v) -> v.map { it.name } }
        assertEquals(listOf("CBS News Philadelphia", "PhillyCAM"), byGroup["Philadelphia"])
        assertEquals(listOf("CNN HEADLINES", "TYT Network"), byGroup["News"])
        assertEquals(listOf("Vevo Hip-Hop"), byGroup["Hip-Hop Videos"])
        assertEquals(listOf("Vevo R&B"), byGroup["R&B / Soul Videos"])
        assertEquals(listOf("XITE Gospel"), byGroup["Gospel"])
        assertEquals(listOf("BET Cinema", "Bounce XL"), byGroup["African American"])
        assertEquals(1, byGroup["Caribbean"]?.size); assertEquals(1, byGroup["Africa"]?.size)
        assertTrue("unrelated channels are left out", lineup.none { it.name == "Cooking 24/7" })
        assertEquals(TransmissionTv.groupOrder.filter { it in byGroup }, lineup.map { it.group }.distinct())
        assertEquals(lineup.indices.toList(), lineup.map { it.index })
    }

    @Test fun exactNamesDontMatchLookAlikes() {
        val lineup = TransmissionTv.curate(mapOf(pluto to listOf(ch("CNN Originals"), ch("Vevo R&B Throwback Special"))))
        assertTrue(lineup.none { it.group == "News" || it.group == "R&B / Soul Videos" })
    }

    @Test fun missingSourcesAreFine() {
        assertTrue(TransmissionTv.curate(emptyMap()).isEmpty())
        assertTrue(TransmissionTv.allSources.all { it.startsWith("https://raw.githubusercontent.com/") })
    }

    @Test fun cleanNameStripsResolutionOnly() {
        assertEquals("CBS News Philadelphia", TransmissionTv.cleanName("CBS News Philadelphia (720p)"))
        assertEquals("TVJ (720p) [Not 24/7]", TransmissionTv.cleanName("TVJ (720p) [Not 24/7]"))
    }
}
