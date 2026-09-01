package com.example.touchevidence

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    @Test
    fun retentionMinutesToMs_clampsBelowMinimum() {
        assertEquals(5 * 60_000L, retentionMinutesToMs(1))
    }

    @Test
    fun retentionMinutesToMs_keepsAllowedValues() {
        assertEquals(5 * 60_000L, retentionMinutesToMs(5))
        assertEquals(10 * 60_000L, retentionMinutesToMs(10))
        assertEquals(15 * 60_000L, retentionMinutesToMs(15))
    }

    @Test
    fun retentionMinutesToMs_clampsAboveMaximum() {
        assertEquals(15 * 60_000L, retentionMinutesToMs(30))
    }
}
