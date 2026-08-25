package com.shivam.sketchseed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against a reply GitHub actually sent.
 *
 * The hand-written fixtures next door cover the decisions; this covers the
 * thing those cannot — that the field names, and the several dozen fields the
 * app ignores, are what GitHub really returns. A rename upstream would otherwise
 * show up as an update check that quietly never finds anything, which is
 * indistinguishable from being up to date.
 *
 * Captured from `repos/shivam1410/sketchseed/releases/latest`.
 */
class GitHubRealPayloadTest {

    private val payload: String =
        checkNotNull(javaClass.getResourceAsStream("/github-releases-latest.json")) {
            "github-releases-latest.json is missing from test resources"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `a real release parses despite the fields the app ignores`() {
        val release = checkNotNull(GitHubRelease.parseLatest(payload)) { "the real payload no longer parses" }

        assertEquals("v1.2.2", release.tag)
        assertEquals("1.2.2", release.version)
        assertEquals("SketchSeed-1.2.2.apk", release.apkUrl.substringAfterLast('/'))
        assertTrue(release.apkUrl.startsWith("https://github.com/"))
        assertEquals(2_960_147L, release.apkSizeBytes)
        assertEquals(
            "39063c62deb21cfbf3ba6e6415cb4c8c1fb3cdd921ddce83843efcca0b81c6a3",
            release.apkSha256,
        )
        assertTrue(release.notes.isNotBlank())
    }

    @Test
    fun `the shipped version is not offered as an update to itself`() {
        // The released build and the tag are the same version, so an install of
        // 1.2.2 must be told there is nothing to do.
        val release = checkNotNull(GitHubRelease.parseLatest(payload)) { "the real payload no longer parses" }

        assertTrue(ReleaseVersion.isUpgrade(installed = "1.2.1", candidate = release.version))
        assertEquals(
            false,
            ReleaseVersion.isUpgrade(installed = release.version, candidate = release.version),
        )
    }
}
