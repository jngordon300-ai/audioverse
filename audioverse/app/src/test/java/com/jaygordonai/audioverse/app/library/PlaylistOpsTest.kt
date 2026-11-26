package com.jaygordonai.audioverse.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOpsTest {

    private fun entry(id: Long, live: Boolean = false) = PlaylistEntry(id, "content://media/$id", "T$id", "A", live)
    private val base = PlaylistOps.ensureFavorites(emptyList())

    @Test fun favoritesAlwaysExistAndCannotBeDeleted() {
        assertEquals(1, base.size)
        assertEquals(PlaylistOps.FAVORITES_ID, base[0].id)
        assertEquals(base, PlaylistOps.delete(base, PlaylistOps.FAVORITES_ID))
        assertEquals(base, PlaylistOps.ensureFavorites(base))
    }

    @Test fun createTrimsAndRejectsBlankNames() {
        assertEquals(base, PlaylistOps.create(base, "   ", 9))
        val made = PlaylistOps.create(base, "  Sunday Gospel  ", 9)
        assertEquals("Sunday Gospel", made.last().name)
        assertEquals(PlaylistOps.MAX_NAME, PlaylistOps.create(base, "x".repeat(100), 9).last().name.length)
    }

    @Test fun addSkipsDuplicatesAndMixesSongsWithStations() {
        var l = PlaylistOps.add(base, PlaylistOps.FAVORITES_ID, entry(5))
        l = PlaylistOps.add(l, PlaylistOps.FAVORITES_ID, entry(5))
        l = PlaylistOps.add(l, PlaylistOps.FAVORITES_ID, entry(-42, live = true))
        assertEquals(listOf(5L, -42L), l[0].entries.map { it.id })
    }

    @Test fun toggleRemovesThenAdds() {
        val on = PlaylistOps.toggle(base, PlaylistOps.FAVORITES_ID, entry(3))
        assertTrue(PlaylistOps.contains(on, PlaylistOps.FAVORITES_ID, 3))
        val off = PlaylistOps.toggle(on, PlaylistOps.FAVORITES_ID, entry(3))
        assertFalse(PlaylistOps.contains(off, PlaylistOps.FAVORITES_ID, 3))
    }

    @Test fun moveAndRemoveIgnoreBadIndexes() {
        var l = base
        listOf(1L, 2L, 3L).forEach { l = PlaylistOps.add(l, PlaylistOps.FAVORITES_ID, entry(it)) }
        l = PlaylistOps.move(l, PlaylistOps.FAVORITES_ID, 0, 2)
        assertEquals(listOf(2L, 3L, 1L), l[0].entries.map { it.id })
        assertEquals(l, PlaylistOps.move(l, PlaylistOps.FAVORITES_ID, 2, 3))
        l = PlaylistOps.removeAt(l, PlaylistOps.FAVORITES_ID, 1)
        assertEquals(listOf(2L, 1L), l[0].entries.map { it.id })
        assertEquals(l, PlaylistOps.removeAt(l, PlaylistOps.FAVORITES_ID, 7))
    }
}
