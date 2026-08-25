package com.shivam.sketchseed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the update check turns on.
 *
 * Only two answers matter: offer the download, or say nothing. Anything the app
 * cannot read confidently has to fall on the "say nothing" side — an update
 * prompt that installs the same build, or an older one, is worse than no prompt.
 */
class ReleaseVersionTest {

    @Test
    fun `a higher patch is an upgrade`() {
        assertTrue(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "1.2.3"))
    }

    @Test
    fun `the installed version is not an upgrade over itself`() {
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "1.2.2"))
    }

    @Test
    fun `an older release is never offered`() {
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "1.2.1"))
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "0.9.9"))
    }

    @Test
    fun `the v prefix on a git tag is not part of the version`() {
        assertTrue(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "v1.3.0"))
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "v1.2.2"))
    }

    @Test
    fun `components are compared as numbers, not as text`() {
        // The classic sideloaded-updater bug: "1.10" sorts before "1.9" as text,
        // so the tenth release of a line stops being offered entirely.
        assertTrue(ReleaseVersion.isUpgrade(installed = "1.9.0", candidate = "1.10.0"))
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.10.0", candidate = "1.9.0"))
    }

    @Test
    fun `a missing component counts as zero`() {
        // This project has shipped both `v1.1` and `v1.1.1`, so the two lengths
        // genuinely meet.
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.0", candidate = "1.2"))
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2", candidate = "1.2.0"))
        assertTrue(ReleaseVersion.isUpgrade(installed = "1.1", candidate = "1.1.1"))
    }

    @Test
    fun `a suffix is ignored rather than ranked`() {
        // Ranking pre-releases properly is a whole specification, and this
        // project has never published one. Comparing the numbers alone means the
        // worst case is staying put, not installing something unexpected.
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "1.2.2-beta1"))
        assertTrue(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "1.3.0-rc1"))
    }

    @Test
    fun `a version neither side can parse is not an upgrade`() {
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = "latest"))
        assertFalse(ReleaseVersion.isUpgrade(installed = "1.2.2", candidate = ""))
        assertFalse(ReleaseVersion.isUpgrade(installed = "nightly", candidate = "1.3.0"))
    }

    @Test
    fun `parsing rejects anything that is not a dotted number`() {
        assertNull(ReleaseVersion.parse("1.2."))
        assertNull(ReleaseVersion.parse("1..2"))
        assertNull(ReleaseVersion.parse("1.2.x"))
        assertNull(ReleaseVersion.parse("-1.2"))
        assertNull(ReleaseVersion.parse(""))
        assertNull(ReleaseVersion.parse("v"))
    }

    @Test
    fun `parsing keeps the numbers it was given`() {
        assertEquals(listOf(1, 2, 2), ReleaseVersion.parse("v1.2.2")?.parts)
        assertEquals(listOf(2), ReleaseVersion.parse("2")?.parts)
        assertEquals(listOf(1, 3, 0), ReleaseVersion.parse("1.3.0-rc1")?.parts)
    }
}
