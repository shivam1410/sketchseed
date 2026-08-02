package com.shivam.sketchseed.backup

import android.util.Log
import com.shivam.sketchseed.data.appJson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A file as Drive reports it. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
    /** Drive returns size as a string because it can exceed Int range. */
    val size: String? = null,
) {
    val sizeBytes: Long get() = size?.toLongOrNull() ?: 0L
}

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

/**
 * @param code the HTTP status, when the failure came from Drive rather than the
 *   network. 401 means the token needs refreshing.
 */
class DriveException(
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isAuthExpired: Boolean get() = code == 401
}

/**
 * A minimal Drive v3 client, speaking REST directly.
 *
 * The official `google-api-services-drive` client would drag in a large stack of
 * transitive dependencies for the four calls this app makes, so these are hand
 * written against the documented endpoints instead.
 *
 * Everything lives in the **appDataFolder** space: a per-app hidden folder that
 * does not appear anywhere in the Drive interface and that no other app can
 * read. Listing is confined to that space, so this client cannot see anything
 * else in the user's Drive even accidentally.
 */
class DriveClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = appJson,
) {

    /** Backups this app has stored, newest first. */
    suspend fun list(token: String, fileName: String? = null): List<DriveFile> {
        val query = buildList {
            add("trashed=false")
            fileName?.let { add("name='${it.escapeForQuery()}'") }
        }.joinToString(" and ")

        return queryFiles(token, query)
    }

    /**
     * Uploads [source] as [fileName], replacing the previous copy in place.
     *
     * Replacing rather than accumulating keeps one canonical backup, which is
     * how a phone backup is normally expected to behave, and stops the hidden
     * folder growing without bound where the user cannot prune it.
     */
    suspend fun upload(
        token: String,
        fileName: String,
        source: File,
    ): DriveFile {
        val existing = list(token, fileName).firstOrNull()

        val request = if (existing != null) {
            Request.Builder()
                .url("$UPLOAD/files/${existing.id}?uploadType=media&fields=$FILE_FIELDS")
                .authorized(token)
                .patch(source.asRequestBody(ZIP_MIME))
                .build()
        } else {
            val metadata = buildJsonObject("name" to fileName, "parents" to listOf(APP_DATA_SPACE))
            val multipart = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody(JSON_MIME))
                .addPart(source.asRequestBody(ZIP_MIME))
                .build()

            Request.Builder()
                .url("$UPLOAD/files?uploadType=multipart&fields=$FILE_FIELDS")
                .authorized(token)
                .post(multipart)
                .build()
        }

        return http.run(request) { response ->
            json.decodeFromString(DriveFile.serializer(), response.bodyText())
        }
    }

    /** Streams [fileId] into [destination]. */
    suspend fun download(token: String, fileId: String, destination: File) {
        val request = Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .authorized(token)
            .get()
            .build()

        http.run(request) { response ->
            val stream = response.body?.byteStream()
                ?: throw DriveException("Drive returned an empty response.")
            destination.outputStream().use { out -> stream.use { it.copyTo(out) } }
        }
    }

    suspend fun delete(token: String, fileId: String) {
        val request = Request.Builder()
            .url("$API/files/$fileId")
            .authorized(token)
            .delete()
            .build()

        http.run(request) { }
    }

    private suspend fun queryFiles(token: String, query: String): List<DriveFile> {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", APP_DATA_SPACE)
            .addQueryParameter("fields", "files($FILE_FIELDS)")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .build()

        val request = Request.Builder().url(url).authorized(token).get().build()

        return http.run(request) { response ->
            json.decodeFromString(DriveFileList.serializer(), response.bodyText()).files
        }
    }

    private fun Request.Builder.authorized(token: String) =
        header("Authorization", "Bearer $token")

    private suspend fun <T> OkHttpClient.run(
        request: Request,
        onSuccess: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(MAX_ERROR_CHARS)
                    Log.e(TAG, "Drive ${request.method} ${request.url.encodedPath} -> ${response.code}: $detail")
                    throw DriveException(
                        message = describe(response.code),
                        code = response.code,
                    )
                }
                onSuccess(response)
            }
        } catch (e: IOException) {
            throw DriveException("Could not reach Google Drive. Check your connection.", cause = e)
        }
    }

    private fun Response.bodyText(): String =
        body?.string() ?: throw DriveException("Drive returned an empty response.")

    private fun describe(code: Int): String = when (code) {
        401 -> "Google Drive access expired. Sign in again."
        403 -> "Google Drive refused the request. Your Drive storage may be full."
        404 -> "That backup is no longer in Drive."
        in 500..599 -> "Google Drive is having problems. Try again shortly."
        else -> "Google Drive returned an unexpected error ($code)."
    }

    private fun buildJsonObject(vararg pairs: Pair<String, Any>): String {
        val body = pairs.joinToString(",") { (key, value) ->
            val encoded = when (value) {
                is List<*> -> value.joinToString(",", "[", "]") {
                    json.encodeToString(String.serializer(), it.toString())
                }

                else -> json.encodeToString(String.serializer(), value.toString())
            }
            "${json.encodeToString(String.serializer(), key)}:$encoded"
        }
        return "{$body}"
    }

    /** Drive query strings are single quoted, so embedded quotes must escape. */
    private fun String.escapeForQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val TAG = "DriveClient"
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

        /** Drive's reserved alias for the app's own hidden folder. */
        const val APP_DATA_SPACE = "appDataFolder"
        const val FILE_FIELDS = "id,name,modifiedTime,size"
        const val MAX_ERROR_CHARS = 500

        val JSON_MIME = "application/json; charset=UTF-8".toMediaType()
        val ZIP_MIME = "application/zip".toMediaType()
    }
}
