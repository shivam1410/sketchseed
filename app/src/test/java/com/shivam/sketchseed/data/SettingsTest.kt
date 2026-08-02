package com.shivam.sketchseed.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which date day 1 falls on.
 *
 * This one value decides the prompt everything else hangs off, so the
 * precedence between a personal start date and the pack's own is worth pinning
 * down rather than leaving to an elvis operator nobody reads twice.
 */
class SettingsTest {

    private val packStart = LocalDate.of(2026, 8, 2)

    @Test
    fun `falls back to the pack when no personal date is set`() {
        val settings = Settings()

        assertNull(settings.startDateOverride)
        assertEquals(packStart, settings.startDate(packStart))
    }

    @Test
    fun `a personal start date wins over the pack`() {
        val mine = LocalDate.of(2026, 11, 20)
        val settings = Settings(startDateOverrideEpochDay = mine.toEpochDay())

        assertEquals(mine, settings.startDate(packStart))
    }

    @Test
    fun `installing long after the pack date still begins on day one`() {
        // The whole point of the override: someone starting in November meets
        // Apple, not whatever the calendar had already reached.
        val installedOn = LocalDate.of(2026, 11, 20)
        val settings = Settings(startDateOverrideEpochDay = installedOn.toEpochDay())

        val dayNumber = java.time.temporal.ChronoUnit.DAYS
            .between(settings.startDate(packStart), installedOn)
            .toInt() + 1

        assertEquals(1, dayNumber)
    }

    @Test
    fun `resetting mid-journey puts day one back on the reset date`() {
        val resetOn = LocalDate.of(2026, 9, 10)
        val settings = Settings(startDateOverrideEpochDay = resetOn.toEpochDay())

        assertEquals(resetOn, settings.startDate(packStart))
    }
}
