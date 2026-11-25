package com.jaygordonai.audioverse.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {
    @Test fun everyPresetHasTenBandsAndAPreampThatCancelsTheBiggestBoost() {
        EqPresets.builtIn.forEach { p ->
            assertEquals(p.name, 10, p.bands.size)
            assertTrue(p.name, p.preampDb <= 0f)
            assertEquals(p.name, 0f, (p.bands.maxOrNull() ?: 0f).coerceAtLeast(0f) + p.preampDb, 0.26f)
        }
        assertEquals(-7f, EqPresets.builtIn.first { it.name == "Bass Boost" }.preampDb, 0f)
        assertEquals(0f, EqPresets.builtIn.first { it.name == "Flat" }.preampDb, 0f)
    }

    @Test fun matchingFindsTheChipToHighlight() {
        val boom = EqPresets.builtIn.first { it.name == "Boom Bap" }
        assertEquals("Boom Bap", EqPresets.matching(boom.bands, EqPresets.builtIn)?.name)
        assertNull(EqPresets.matching(boom.bands.map { it + 0.5f }, EqPresets.builtIn))
        assertEquals(EqPresets.builtIn.size, EqPresets.builtIn.map { it.name }.toSet().size)
    }
}
