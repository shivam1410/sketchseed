package com.shivam.sketchseed.update

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 over a downloaded file.
 *
 * Kept apart from the installer, and free of Android APIs, because this is the
 * one piece of the update path whose correctness can be proven on the JVM.
 */
object ApkDigest {

    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_BYTES = 64 * 1024

    /** @return the digest of [file] as lowercase hex. */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether [file] is exactly what [expected] describes.
     *
     * A null or blank [expected] is false, not true. Having nothing to compare
     * against is the absence of a check, and an absent check must never read as
     * a passed one — that inversion is how a verification step quietly becomes
     * decoration.
     */
    fun matches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return false
        return of(file).equals(expected.trim(), ignoreCase = true)
    }
}
