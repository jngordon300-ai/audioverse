package com.jaygordonai.audioverse.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutListTest {

    @Test
    fun templatesNameSegments() {
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "OT", "OT2"), (0..5).map { CutTemplate.BASKETBALL.nameFor(it) })
        assertEquals("Match 3", CutTemplate.WRESTLING.nameFor(2))
        assertEquals("Part 1", CutTemplate.CUSTOM.nameFor(0))
    }

    @Test
    fun ffmpegTimeFormat() {
        assertEquals("0:00:00.000", ffTime(0))
        assertEquals("1:02:03.456", ffTime(3_723_456))
    }

    @Test
    fun scriptUsesSeekBeforeInputAndStreamCopy() {
        val script = ffmpegScript(
            "Raw \"Night\" 2026.mkv", "Raw Night 2026",
            listOf(Segment(1, "Match 1", 60_000, 600_000), Segment(2, "Match 2", 600_000, 1_500_000))
        )
        val lines = script.lines().filter { it.startsWith("ffmpeg") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("-ss 0:01:00.000 -i \"\$IN\" -t 0:09:00.000"))
        assertTrue(lines[0].contains("-c copy"))
        assertTrue(lines[0].endsWith("\"Raw Night 2026 - Match 1.mkv\""))
        assertTrue(script.contains("IN='Raw \"Night\" 2026.mkv'"))
        val evil = ffmpegScript("x'\$(rm -rf ~)'.mp4", "x", listOf(Segment(1, "A", 0, 5_000)))
        assertTrue("single quotes must be escaped", evil.contains("IN='x'\\''\$(rm -rf ~)'\\''.mp4'"))
    }

    @Test
    fun unsafeCharactersAreRemovedFromFileNames() {
        assertEquals("a_b_c", safeName("a/b;c"))
        assertEquals("video", safeName("   "))
    }
}
