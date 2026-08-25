package com.shivam.sketchseed.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often the app is allowed to go and ask.
 *
 * The check runs when Settings is opened, which someone can do repeatedly in a
 * minute. Without a floor, that is a request per visit against an endpoint that
 * allows sixty an hour and then starts refusing — so the app would rate-limit
 * itself out of the very answer it wanted.
 */
class UpdateScheduleTest {

    private val now = 1_800_000_000L

    @Test
    fun `an install that has never checked is due`() {
        assertTrue(UpdateSchedule.isDue(lastCheckEpochSecond = null, nowEpochSecond = now))
    }

    @Test
    fun `a check a moment ago is not repeated`() {
        assertFalse(UpdateSchedule.isDue(lastCheckEpochSecond = now - 60, nowEpochSecond = now))
    }

    @Test
    fun `a check just inside the interval is not repeated`() {
        val justInside = now - UpdateSchedule.INTERVAL_SECONDS + 1

        assertFalse(UpdateSchedule.isDue(lastCheckEpochSecond = justInside, nowEpochSecond = now))
    }

    @Test
    fun `a check older than the interval is due again`() {
        val older = now - UpdateSchedule.INTERVAL_SECONDS - 1

        assertTrue(UpdateSchedule.isDue(lastCheckEpochSecond = older, nowEpochSecond = now))
    }

    @Test
    fun `a timestamp in the future does not wedge the check shut`() {
        // Someone setting the clock forward, checking, then setting it back
        // would otherwise leave the app convinced its next check is years away.
        val fromTheFuture = now + 365 * 24 * 60 * 60L

        assertTrue(UpdateSchedule.isDue(lastCheckEpochSecond = fromTheFuture, nowEpochSecond = now))
    }
}
