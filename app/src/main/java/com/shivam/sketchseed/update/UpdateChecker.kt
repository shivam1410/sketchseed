package com.shivam.sketchseed.update

import android.util.Log
import com.shivam.sketchseed.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the last look at the releases feed established. */
sealed interface UpdateCheck {
    /** Nothing newer is published. */
    data object UpToDate : UpdateCheck

    data class Available(val release: GitHubRelease) : UpdateCheck

    /**
     * The check did not complete — offline, rate limited, or a reply that made
     * no sense. Distinct from [UpToDate] because "we could not ask" and "there
     * is nothing new" are different things to tell someone.
     */
    data object Failed : UpdateCheck
}

/**
 * Short timeouts throughout. This is a background courtesy on a screen the user
 * opened to do something else, so it either answers quickly or gives up quietly.
 */
internal fun updateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(1, TimeUnit.MINUTES)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Asks GitHub whether a newer APK has been published.
 *
 * The app is sideloaded rather than installed from Play, so nothing tells the
 * user a new build exists — every earlier release relied on them going and
 * looking. This is the whole of the "detection" half: one unauthenticated GET
 * against the public releases feed, no account, nothing sent about the user or
 * the device beyond what any HTTP request carries.
 */
class UpdateChecker(
    private val installedVersion: String = BuildConfig.VERSION_NAME,
    private val http: OkHttpClient = updateHttpClient(),
    private val releasesUrl: String = LATEST_RELEASE_URL,
) {

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val body = try {
            fetchLatest()
        } catch (e: IOException) {
            Log.i(TAG, "Could not reach the releases feed", e)
            return@withContext UpdateCheck.Failed
        }

        // Null here means the reply parsed to nothing installable — a draft, a
        // pre-release, no APK attached, or an unexpected shape. Reported as a
        // failed check rather than as "up to date", since the app genuinely does
        // not know what the newest release is.
        val release = body?.let(GitHubRelease::parseLatest)
            ?: return@withContext UpdateCheck.Failed

        if (ReleaseVersion.isUpgrade(installed = installedVersion, candidate = release.version)) {
            UpdateCheck.Available(release)
        } else {
            UpdateCheck.UpToDate
        }
    }

    /** @return the reply body, or null when GitHub answered with an error. */
    private fun fetchLatest(): String? {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            // GitHub rejects unauthenticated calls that do not identify
            // themselves. It carries the version so a rate-limit complaint can
            // be traced to a build.
            .header("User-Agent", "SketchSeed/$installedVersion")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 403 here is almost always the 60-per-hour unauthenticated
                // rate limit, which is why the caller only asks twice a day.
                Log.i(TAG, "Releases feed answered ${response.code}")
                return null
            }
            return response.body?.string()
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"

        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/shivam1410/sketchseed/releases/latest"

        /** Where someone is sent to read the notes or download by hand. */
        const val RELEASES_PAGE_URL = "https://github.com/shivam1410/sketchseed/releases/latest"
    }
}
