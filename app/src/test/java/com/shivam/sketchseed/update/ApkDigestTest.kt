package com.shivam.sketchseed.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The check standing between "some bytes arrived" and "install this".
 *
 * Android refuses to install an APK signed with a different certificate over an
 * existing app, which is the real guarantee. This is the earlier one: it catches
 * a truncated or altered download before the installer is ever opened, so the
 * user is not asked to approve something the app already knows is wrong.
 */
class ApkDigestTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a file matches the digest of its own contents`() {
        val file = fileOf("sketch seed")

        assertTrue(ApkDigest.matches(file, ApkDigest.of(file)))
    }

    @Test
    fun `the expected digest is compared without regard to case`() {
        val file = fileOf("sketch seed")

        assertTrue(ApkDigest.matches(file, ApkDigest.of(file).uppercase()))
    }

    @Test
    fun `a single changed byte fails the check`() {
        val original = fileOf("sketch seed")
        val expected = ApkDigest.of(original)
        val altered = fileOf("sketch seeD")

        assertFalse(ApkDigest.matches(altered, expected))
    }

    @Test
    fun `a truncated download fails the check`() {
        val whole = fileOf("sketch seed, the whole file")
        val expected = ApkDigest.of(whole)
        val partial = fileOf("sketch seed, the who")

        assertFalse(ApkDigest.matches(partial, expected))
    }

    @Test
    fun `a known vector pins the algorithm itself`() {
        // SHA-256 of "abc". If this ever changes, the digest being compared is
        // not the digest GitHub published.
        assertTrue(
            ApkDigest.matches(
                fileOf("abc"),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
        )
    }

    @Test
    fun `nothing to compare against is not a pass`() {
        // A release with no published digest must not slip through as verified.
        assertFalse(ApkDigest.matches(fileOf("sketch seed"), null))
        assertFalse(ApkDigest.matches(fileOf("sketch seed"), ""))
    }

    private fun fileOf(contents: String): File =
        temp.newFile().apply { writeText(contents) }
}
