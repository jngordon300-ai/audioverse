package com.jaygordonai.audioverse.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    // Fake hosts and logins only.
    private fun parse(text: String, limit: Int = M3uParser.DEFAULT_LIMIT) = M3uParser.parse(text.lineSequence(), limit)

    @Test fun readsM3uPlusAttributesAndNames() {
        val r = parse(
            "﻿#EXTM3U x-tvg-url=\"https://epg.example/x.xml\"\r\n" +
                "#EXTINF:-1 tvg-id=\"news1.us\" tvg-name=\"News One\" tvg-logo=\"https://img.example/1.png\" group-title=\"US | News\",News One HD\r\n" +
                "http://iptv.example:8080/user/pass/101.ts\r\n" +
                "#EXTINF:-1 group-title=\"Sports\",ESPN, Live\n" +
                "https://iptv.example/live/user/pass/202.m3u8\n"
        )
        assertEquals(2, r.channels.size)
        val a = r.channels[0]
        assertEquals("News One HD", a.name)
        assertEquals("US | News", a.group)
        assertEquals("news1.us", a.tvgId)
        assertEquals("http://iptv.example:8080/user/pass/101.ts", a.url)
        assertEquals("ESPN, Live", r.channels[1].name)
        assertEquals("Sports", r.channels[1].group)
    }

    @Test fun commaInsideQuotedAttributeDoesNotSplitTheName() {
        val r = parse("#EXTM3U\n#EXTINF:-1 group-title=\"Movies, Kids\" tvg-name=\"Toons\",Toon TV\nhttps://a.example/1\n")
        assertEquals("Movies, Kids", r.channels[0].group)
        assertEquals("Toon TV", r.channels[0].name)
    }

    @Test fun fallsBackToTvgNameThenNumber() {
        val r = parse("#EXTINF:-1 tvg-name=\"From Attr\",\nhttps://a.example/1\nhttps://a.example/2\n")
        assertEquals("From Attr", r.channels[0].name)
        assertEquals("Channel 2", r.channels[1].name)
        assertEquals(M3uParser.NO_GROUP, r.channels[1].group)
    }

    @Test fun extGrpAndVlcOptionsApplyToNextChannelOnly() {
        val r = parse(
            "#EXTINF:-1,One\n#EXTGRP:Music\n#EXTVLCOPT:http-user-agent=Player/1.0\n#EXTVLCOPT:http-referrer=https://ref.example/\n" +
                "https://a.example/1\n#EXTINF:-1,Two\nhttps://a.example/2\n"
        )
        assertEquals("Music", r.channels[0].group)
        assertEquals("Player/1.0", r.channels[0].userAgent)
        assertEquals("https://ref.example/", r.channels[0].referrer)
        assertEquals(M3uParser.NO_GROUP, r.channels[1].group)
        assertNull(r.channels[1].userAgent)
    }

    @Test fun skipsNonWebLinksAndHonoursLimit() {
        val r = parse("#EXTINF:-1,Bad\nrtmp://a.example/x\n#EXTINF:-1,Local\nfile:///sdcard/x.ts\nhttps://a.example/1\nhttps://a.example/2\nhttps://a.example/3\n", limit = 2)
        assertEquals(2, r.skipped)
        assertEquals(2, r.channels.size)
        assertTrue(r.truncated)
    }

    @Test fun groupsKeepPlaylistOrderWithCounts() {
        val r = parse("#EXTINF:-1 group-title=\"B\",1\nhttps://a.example/1\n#EXTINF:-1 group-title=\"A\",2\nhttps://a.example/2\n#EXTINF:-1 group-title=\"B\",3\nhttps://a.example/3\n")
        assertEquals(listOf("B" to 2, "A" to 1), M3uParser.groups(r.channels))
    }

    @Test fun toStringNeverContainsTheLink() {
        val r = parse("#EXTINF:-1,Secret\nhttp://iptv.example/myuser/mypassword/1.ts\n")
        assertFalse(r.channels[0].toString().contains("mypassword"))
        assertTrue(M3uParser.isPlainHttp(" http://x.example"))
        assertFalse(M3uParser.isPlainHttp("https://x.example"))
    }
}
