package com.shivam.sketchseed.update

import com.shivam.sketchseed.data.appJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The newest release, reduced to the few things needed to offer it.
 *
 * [apkSha256] is null when GitHub reports no digest for the asset — true of
 * every release published before the API began returning one.
 */
data class GitHubRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String?,
) {
    /** The tag without its `v`, which is what the app shows and compares. */
    val version: String get() = tag.removePrefix("v").removePrefix("V")

    companion object {
        /** Only an asset served from GitHub itself is a candidate for install. */
        private val ALLOWED_ASSET_HOSTS = setOf("github.com", "objects.githubusercontent.com")

        private const val SHA256_PREFIX = "sha256:"

        /**
         * Reads the `releases/latest` reply, or null if there is nothing here
         * this app can safely install.
         *
         * Null covers every unusable case on purpose — a draft, a pre-release, a
         * release with no APK attached, an asset pointing somewhere other than
         * GitHub, or a body that does not parse. The caller has exactly one
         * decision to make, and none of these are a partial success worth
         * reporting differently.
         *
         * Deliberately free of Android APIs, including logging, so the rules
         * above are covered by fast JVM tests. [UpdateChecker] reports the null.
         */
        fun parseLatest(body: String, json: Json = appJson): GitHubRelease? {
            val dto = try {
                json.decodeFromString(ReleaseDto.serializer(), body)
            } catch (e: SerializationException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }

            if (dto.draft || dto.prerelease) return null
            if (dto.tagName.isBlank()) return null

            val apk = dto.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.url.isGitHubHosted()
            } ?: return null

            return GitHubRelease(
                tag = dto.tagName,
                name = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
                notes = dto.body.orEmpty().trim(),
                apkUrl = apk.url,
                apkSizeBytes = apk.size,
                // Anything other than SHA-256 is dropped rather than carried
                // forward: a digest the app cannot compute is not a check, and
                // holding one would only invite pretending otherwise.
                apkSha256 = apk.digest
                    ?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                    ?.removePrefix(SHA256_PREFIX)
                    ?.lowercase()
                    ?.takeIf { it.length == 64 && it.all(Char::isLetterOrDigit) },
            )
        }

        /**
         * Exact host match over HTTPS.
         *
         * `startsWith("https://github.com")` would also welcome
         * `https://github.com.example.test`, which is the whole trick.
         */
        private fun String.isGitHubHosted(): Boolean {
            val afterScheme = removePrefix("https://")
            if (afterScheme == this) return false
            val host = afterScheme.substringBefore('/').substringBefore(':')
            return host in ALLOWED_ASSET_HOSTS
        }
    }
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    /** `"sha256:<hex>"`, or absent on releases published before GitHub sent it. */
    val digest: String? = null,
)
