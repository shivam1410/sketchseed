package com.shivam.sketchseed.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** How far the download got. */
sealed interface DownloadOutcome {
    data class Ready(val apk: File) : DownloadOutcome

    /** Bytes arrived, but they are not the ones GitHub published. */
    data object Corrupt : DownloadOutcome

    /** The transfer never completed. Retrying is reasonable. */
    data object Failed : DownloadOutcome
}

/**
 * Fetches a released APK and hands it to Android's installer.
 *
 * Three separate things stand between a released APK and a replaced app, and it
 * is worth being explicit about which one does what:
 *
 * 1. **Where it came from.** [GitHubRelease] refuses any asset URL that is not
 *    on a GitHub host, so the address being fetched here is never taken from an
 *    arbitrary field in a reply.
 * 2. **What arrived.** The bytes are checked against the SHA-256 GitHub
 *    published before anything is handed on, so a truncated or altered download
 *    is discarded rather than shown to the user as something to approve.
 * 3. **What it is allowed to replace.** Android refuses to install an APK signed
 *    with a different certificate over an installed app. That is the real
 *    guarantee, and it is the platform's rather than this class's — nothing this
 *    code could get wrong can weaken it.
 *
 * The install itself is always the user's: this opens the system installer, and
 * the system asks. Nothing here installs anything silently, and it cannot — that
 * needs a privilege a sideloaded app does not have.
 */
class UpdateInstaller(
    context: Context,
    private val http: OkHttpClient = updateHttpClient(),
) {
    private val appContext = context.applicationContext

    /**
     * Whether the user has allowed this app to ask to install packages.
     *
     * Android 8 replaced the global "unknown sources" switch with a per-app
     * permission that only the user can grant, in system settings. It cannot be
     * requested from a dialog, so the app has to send them there and explain why.
     */
    val canInstallPackages: Boolean
        get() = appContext.packageManager.canRequestPackageInstalls()

    /** Sends the user to the screen where that permission lives. */
    fun permissionSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${appContext.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Downloads [release], verifying it before reporting success.
     *
     * @param onProgress bytes received so far, against the size GitHub reported.
     */
    suspend fun download(
        release: GitHubRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, UPDATE_DIR)

        // Start from nothing every time. A part-finished attempt from a previous
        // run has no resume story here, and a cache directory quietly filling
        // with old APKs is the failure nobody notices.
        directory.deleteRecursively()
        if (!directory.mkdirs()) {
            Log.w(TAG, "Could not create the update cache directory")
            return@withContext DownloadOutcome.Failed
        }

        // Downloaded under a different name, then renamed once verified, so an
        // interrupted transfer can never be mistaken for a complete file.
        val partial = File(directory, "$FILE_STEM-${release.version}.apk.part")

        try {
            val received = fetchTo(release.apkUrl, partial, release.apkSizeBytes, onProgress)
            if (!received) return@withContext DownloadOutcome.Failed
        } catch (e: IOException) {
            Log.i(TAG, "Update download did not complete", e)
            return@withContext DownloadOutcome.Failed
        }

        if (!verified(partial, release)) {
            partial.delete()
            return@withContext DownloadOutcome.Corrupt
        }

        val apk = File(directory, "$FILE_STEM-${release.version}.apk")
        if (!partial.renameTo(apk)) {
            Log.w(TAG, "Could not finalise the downloaded APK")
            partial.delete()
            return@withContext DownloadOutcome.Failed
        }

        DownloadOutcome.Ready(apk)
    }

    /**
     * Streams [apk] into an installer session and asks Android to confirm it.
     *
     * The older route — an `ACTION_VIEW` intent carrying a `content://` URI —
     * makes the system installer copy the file out of this app's cache into its
     * own staging area first. That copy is a step that can fail on its own, and
     * when it does the only thing the user is told is that the package "appears
     * to be invalid", which is both alarming and wrong: the bytes were verified
     * against GitHub's digest before this was ever called. Writing into a session
     * removes the staging copy, and with it a whole failure mode that pointed at
     * the wrong culprit.
     *
     * The confirmation still belongs to Android. Committing returns
     * [PackageInstaller.STATUS_PENDING_USER_ACTION], and
     * [InstallResultReceiver] is what puts the resulting dialog on screen.
     */
    fun install(apk: File): Boolean = try {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(appContext.packageName)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            session.commit(statusIntentFor(sessionId).intentSender)
        }
        true
    } catch (e: IOException) {
        Log.e(TAG, "Could not open an installer session", e)
        false
    }

    /**
     * Where the session sends its progress.
     *
     * Mutable because the system fills in the status extras on the way back; an
     * immutable one would arrive empty and every reply would look the same.
     */
    private fun statusIntentFor(sessionId: Int): PendingIntent {
        val intent = Intent(appContext, InstallResultReceiver::class.java)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        return PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
    }

    /** @return false when GitHub answered with an error rather than the file. */
    private fun fetchTo(
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "SketchSeed")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "Asset download answered ${response.code}")
                return false
            }
            val body = response.body ?: return false
            val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        return true
    }

    /**
     * Whether [file] is what [release] describes.
     *
     * With a published digest this is decisive. Without one — every release from
     * before GitHub reported digests — there is nothing to compare against, and
     * the size is checked instead. That is a check against a truncated transfer,
     * not against a substituted file, and it is worth naming as the weaker thing
     * it is: what actually stops a foreign APK replacing this app is the
     * platform's signature check at install time.
     */
    private fun verified(file: File, release: GitHubRelease): Boolean {
        val expected = release.apkSha256
        if (expected != null) {
            val matched = ApkDigest.matches(file, expected)
            if (!matched) Log.e(TAG, "Downloaded APK does not match the published digest")
            return matched
        }

        Log.i(TAG, "Release ${release.tag} publishes no digest; falling back to the size")
        return release.apkSizeBytes <= 0 || file.length() == release.apkSizeBytes
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    private companion object {
        const val TAG = "UpdateInstaller"
        const val UPDATE_DIR = "updates"
        const val FILE_STEM = "SketchSeed"

        /** The session's name for the base APK. Any name works; this is the convention. */
        const val SESSION_ENTRY = "base.apk"
        const val BUFFER_BYTES = 64 * 1024
    }
}
