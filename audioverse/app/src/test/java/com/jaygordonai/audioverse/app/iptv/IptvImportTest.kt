package com.jaygordonai.audioverse.app.iptv

import com.jaygordonai.audioverse.app.library.FolderLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvImportTest {

    // Fake hosts and logins only.
    @Test fun m3uTextStaysOnePlaylist() {
        val p = IptvImport.plan("#EXTM3U\n#EXTINF:-1,One\nhttps://a.example/1\n")
        assertTrue(p.isM3u)
        assertTrue(p.playlistLinks.isEmpty() && p.streams.isEmpty())
    }

    @Test fun splitsPlaylistLinksFromStreamsAndReadsLabels() {
        val p = IptvImport.plan(
            """
            Gamma, http://iptv.example:8080/get.php?username=u&password=p&type=m3u_plus
            Backup | https://other.example/list.m3u
            https://cdn.example/live/news.m3u8
            Sports HD, http://iptv.example/u/p/123.ts
            not a link
            # comment http://ignored.example
            """.trimIndent()
        )
        assertFalse(p.isM3u)
        assertEquals(listOf("Gamma", "Backup"), p.playlistLinks.map { it.first })
        assertEquals("https://other.example/list.m3u", p.playlistLinks[1].second)
        assertEquals(listOf("", "Sports HD"), p.streams.map { it.first })
        assertEquals("http://iptv.example/u/p/123.ts", p.streams[1].second)
    }

    @Test fun playlistLinkHeuristics() {
        assertTrue(IptvImport.isPlaylistLink("http://x.example/get.php?username=a&password=b"))
        assertTrue(IptvImport.isPlaylistLink("https://x.example/my.M3U"))
        assertTrue(IptvImport.isPlaylistLink("https://x.example/p?output=m3u8"))
        assertFalse(IptvImport.isPlaylistLink("https://x.example/live/ch1.m3u8"))
        assertFalse(IptvImport.isPlaylistLink("http://x.example/u/p/1.ts"))
    }

    @Test fun folderTrackIdsNeverCollideWithPhoneOrStationIds() {
        val id = FolderLibrary.trackId("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic/document/x.flac")
        assertTrue(id > (1L shl 40) - 1)
        assertTrue(id > 0) // stations are negative, MediaStore ids are small
    }
}
