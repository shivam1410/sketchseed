package com.shivam.sketchseed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the releases feed.
 *
 * The reply decides what the app is about to download and install, so anything
 * it cannot make complete sense of resolves to null. There is no partially
 * usable release here: a missing asset or an unfamiliar host is a reason to stop,
 * not to guess.
 */
class GitHubReleaseTest {

    @Test
    fun `a published release with an apk is read whole`() {
        val release = GitHubRelease.parseLatest(payload())

        assertEquals("v1.3.0", release?.tag)
        assertEquals("Sketch Seed 1.3.0", release?.name)
        assertEquals(
            "https://github.com/shivam1410/sketchseed/releases/download/v1.3.0/SketchSeed-1.3.0.apk",
            release?.apkUrl,
        )
        assertEquals(2_960_147L, release?.apkSizeBytes)
        assertEquals(SHA, release?.apkSha256)
        assertEquals("What changed.", release?.notes)
    }

    @Test
    fun `a draft is not a release anyone is meant to have yet`() {
        assertNull(GitHubRelease.parseLatest(payload(draft = true)))
    }

    @Test
    fun `a pre-release is not offered`() {
        assertNull(GitHubRelease.parseLatest(payload(prerelease = true)))
    }

    @Test
    fun `a release with no apk attached is nothing this app can install`() {
        assertNull(GitHubRelease.parseLatest(payload(assetName = "source-code.zip")))
    }

    @Test
    fun `an asset hosted anywhere but GitHub is refused`() {
        // The reply arrives over TLS from api.github.com, so reaching this case
        // means something has already gone wrong. It still costs one comparison
        // to make sure a rewritten URL cannot become an APK this app installs.
        assertNull(GitHubRelease.parseLatest(payload(host = "https://example.com")))
        assertNull(GitHubRelease.parseLatest(payload(host = "http://github.com")))
        assertNull(GitHubRelease.parseLatest(payload(host = "https://github.com.evil.test")))
    }

    @Test
    fun `an asset without a digest still parses, with nothing to check against`() {
        // GitHub only started reporting digests recently, so older releases have
        // none. That is the caller's problem to weigh, not a parse failure.
        val release = GitHubRelease.parseLatest(payload(digest = null))

        assertEquals("v1.3.0", release?.tag)
        assertNull(release?.apkSha256)
    }

    @Test
    fun `a digest in an algorithm we cannot verify is dropped rather than trusted`() {
        val release = GitHubRelease.parseLatest(payload(digest = "md5:abc123"))

        assertEquals("v1.3.0", release?.tag)
        assertNull(release?.apkSha256)
    }

    @Test
    fun `a reply that is not the expected shape is not a release`() {
        assertNull(GitHubRelease.parseLatest("not json at all"))
        assertNull(GitHubRelease.parseLatest("{}"))
        assertNull(GitHubRelease.parseLatest(""))
    }

    private fun payload(
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "SketchSeed-1.3.0.apk",
        host: String = "https://github.com",
        digest: String? = SHA_DIGEST,
    ): String = """
        {
          "tag_name": "v1.3.0",
          "name": "Sketch Seed 1.3.0",
          "body": "What changed.",
          "draft": $draft,
          "prerelease": $prerelease,
          "assets": [
            {
              "name": "$assetName",
              "browser_download_url": "$host/shivam1410/sketchseed/releases/download/v1.3.0/$assetName",
              "size": 2960147,
              "digest": ${digest?.let { "\"$it\"" } ?: "null"}
            }
          ]
        }
    """.trimIndent()

    private companion object {
        const val SHA = "39063c62deb21cfbf3ba6e6415cb4c8c1fb3cdd921ddce83843efcca0b81c6a3"
        const val SHA_DIGEST = "sha256:$SHA"
    }
}
