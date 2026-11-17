package com.parker.tunes.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeRulesTest {
    private val current = Song("Album A", 1, 240_000L)
    private val next = Song("Album B", 1, 210_000L)

    @Test fun disabledMissingNextAndRepeatOneNeverApply() {
        assertFalse(CrossfadeRules.applies(0, current, next, false))
        assertFalse(CrossfadeRules.applies(4, current, null, false))
        assertFalse(CrossfadeRules.applies(4, current, next, true))
    }

    @Test fun unknownOrTooShortTracksNeverApply() {
        assertFalse(CrossfadeRules.applies(4, current.copy(durationMs = null), next, false))
        assertFalse(CrossfadeRules.applies(4, current, next.copy(durationMs = null), false))
        assertFalse(CrossfadeRules.applies(4, current.copy(durationMs = 7_999), next, false))
        assertFalse(CrossfadeRules.applies(4, current, next.copy(durationMs = 7_999), false))
    }

    @Test fun consecutiveTracksOnSameAlbumStayGapless() {
        assertFalse(CrossfadeRules.applies(4, current, Song("Album A", 2, 210_000L), false))
        assertTrue(CrossfadeRules.applies(4, current, next, false))
    }

    @Test fun overlapLeavesEnoughTimeOutsideTheFade() {
        assertFalse(CrossfadeRules.applies(4, current.copy(durationMs = 11_999L), next, false))
        assertFalse(CrossfadeRules.applies(4, current, next.copy(durationMs = 11_999L), false))
        assertTrue(CrossfadeRules.applies(4, current.copy(durationMs = 12_000L), next, false))
        assertTrue(CrossfadeRules.applies(4, current, next.copy(durationMs = 12_000L), false))
    }

    @Test fun equalPowerCurvesMaintainConstantPower() {
        for (step in 0..100) {
            val p = step / 100f
            val out = CrossfadeRules.fadeOut(p)
            val into = CrossfadeRules.fadeIn(p)
            assertEquals(1f, out * out + into * into, 0.00001f)
        }
        assertEquals(1f, CrossfadeRules.fadeOut(0f), 0.00001f)
        assertEquals(0f, CrossfadeRules.fadeOut(1f), 0.00001f)
        assertEquals(0f, CrossfadeRules.fadeIn(0f), 0.00001f)
        assertEquals(1f, CrossfadeRules.fadeIn(1f), 0.00001f)
    }
}
