package com.shivam.sketchseed.update

/**
 * When the automatic check is allowed to run again.
 *
 * A sideloaded app gets a new version every few weeks at most, so asking twice a
 * day is already generous. The floor exists because the check is triggered by
 * opening Settings: without it, someone going in and out of the screen would
 * spend the unauthenticated rate limit — sixty calls an hour, shared across
 * everyone behind the same address — on repeats of an answer that has not
 * changed. Tapping the row asks anyway; this only governs the automatic one.
 */
object UpdateSchedule {

    const val INTERVAL_SECONDS = 12 * 60 * 60L

    /**
     * @param lastCheckEpochSecond null on an install that has never checked.
     */
    fun isDue(lastCheckEpochSecond: Long?, nowEpochSecond: Long): Boolean {
        val last = lastCheckEpochSecond ?: return true

        // A stored time later than now means the clock moved, not that the
        // check happened in the future. Treating it as due costs one request;
        // trusting it would hold the check shut until the clock caught up.
        if (last > nowEpochSecond) return true

        return nowEpochSecond - last >= INTERVAL_SECONDS
    }
}
