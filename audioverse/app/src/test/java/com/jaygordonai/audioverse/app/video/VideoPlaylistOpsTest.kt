package com.jaygordonai.audioverse.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaylistOpsTest {
    private fun e(n: Int) = VideoEntry("content://x/$n", "Video $n")

    @Test fun createAddSkipsDuplicatesAndBlankNames() {
        assertTrue(VideoPlaylistOps.create(emptyList(), "  ", 1).isEmpty())
        var l = VideoPlaylistOps.create(emptyList(), " Wrestling ", 1, e(1))
        assertEquals("Wrestling", l[0].name)
        l = VideoPlaylistOps.add(l, 1, e(1)); l = VideoPlaylistOps.add(l, 1, e(2))
        assertEquals(listOf("content://x/1", "content://x/2"), l[0].items.map { it.uri })
    }

    @Test fun moveRemoveDelete() {
        var l = VideoPlaylistOps.create(emptyList(), "A", 7, e(1))
        listOf(2, 3).forEach { l = VideoPlaylistOps.add(l, 7, e(it)) }
        l = VideoPlaylistOps.move(l, 7, 2, 0)
        assertEquals(listOf("Video 3", "Video 1", "Video 2"), l[0].items.map { it.name })
        assertEquals(l, VideoPlaylistOps.move(l, 7, 0, -1))
        l = VideoPlaylistOps.removeAt(l, 7, 1)
        assertEquals(listOf("Video 3", "Video 2"), l[0].items.map { it.name })
        assertTrue(VideoPlaylistOps.delete(l, 7).isEmpty())
    }
}
