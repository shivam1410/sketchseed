package com.shivam.sketchseed.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.shivam.sketchseed.domain.model.Difficulty
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Whether on-device generation can run right now. */
enum class TipAvailability {
    /** Ready to generate. */
    READY,

    /** Supported, but Gemini Nano still has to be fetched by the system. */
    NEEDS_DOWNLOAD,

    /** This device cannot run the model at all. */
    UNSUPPORTED,
}

/** Outcome of asking for a tip. */
sealed interface TipResult {
    data class Success(val tip: String) : TipResult
    data object Unsupported : TipResult

    /** The model or the inference took too long. Retrying later is reasonable. */
    data object TimedOut : TipResult
    data class Failed(val cause: Throwable?) : TipResult
}

/**
 * Generates a one-line drawing tip with Gemini Nano, entirely on the device.
 *
 * This is a garnish, never a dependency. Gemini Nano is unavailable on plenty of
 * hardware, the API is still in beta, and AICore can sit indefinitely on a model
 * download — so every path is bounded by a timeout and resolves to "no tip"
 * rather than an endless spinner. Nothing here is on the critical path of
 * marking a day done.
 */
class TipGenerator {

    suspend fun availability(): TipAvailability = withModel { model ->
        withTimeoutOrNull(STATUS_TIMEOUT_MS) { model.statusOrNull() }
            ?.toAvailability()
            ?: TipAvailability.UNSUPPORTED
    } ?: TipAvailability.UNSUPPORTED

    /**
     * Produces a short tip for [promptText], fetching the model first if the
     * system has not already done so.
     *
     * @param onPreparing reports bytes fetched while the model downloads, so the
     *   UI can say what it is actually waiting on instead of "Thinking…".
     */
    suspend fun generate(
        promptText: String,
        difficulty: Difficulty,
        onPreparing: (bytesDownloaded: Long) -> Unit = {},
    ): TipResult {
        val result = withModel { model ->
            when (withTimeoutOrNull(STATUS_TIMEOUT_MS) { model.statusOrNull() }?.toAvailability()) {
                TipAvailability.READY -> Unit

                TipAvailability.NEEDS_DOWNLOAD -> {
                    val downloaded = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                        model.awaitDownload(onPreparing)
                    }
                    when (downloaded) {
                        null -> {
                            Log.w(TAG, "Gemini Nano download exceeded ${DOWNLOAD_TIMEOUT_MS}ms")
                            return@withModel TipResult.TimedOut
                        }

                        false -> return@withModel TipResult.Failed(null)
                        true -> Unit
                    }
                }

                // Null status means the call itself timed out or threw.
                TipAvailability.UNSUPPORTED, null -> return@withModel TipResult.Unsupported
            }

            val response = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                model.generateContent(requestFor(promptText, difficulty))
            } ?: run {
                Log.w(TAG, "Inference exceeded ${INFERENCE_TIMEOUT_MS}ms")
                return@withModel TipResult.TimedOut
            }

            val text = response.candidates.firstOrNull()?.text?.let(::tidy)
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Model returned no usable text for '$promptText'")
                TipResult.Failed(null)
            } else {
                TipResult.Success(text)
            }
        }

        return result ?: TipResult.Failed(null)
    }

    /** @return the raw [FeatureStatus], or null if the call threw. */
    private suspend fun GenerativeModel.statusOrNull(): Int? = try {
        checkStatus()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "checkStatus failed; treating as unsupported", e)
        null
    }

    private fun Int.toAvailability(): TipAvailability = when (this) {
        FeatureStatus.AVAILABLE -> TipAvailability.READY
        FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> TipAvailability.NEEDS_DOWNLOAD
        else -> TipAvailability.UNSUPPORTED
    }

    /** @return true once the model is on the device. */
    private suspend fun GenerativeModel.awaitDownload(
        onPreparing: (Long) -> Unit,
    ): Boolean {
        var failure: Throwable? = null
        download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> onPreparing(status.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> {
                    failure = status.e
                    Log.e(TAG, "Gemini Nano download failed", status.e)
                }

                is DownloadStatus.DownloadCompleted -> Log.i(TAG, "Gemini Nano ready")
                else -> Unit
            }
        }
        return failure == null && statusOrNull() == FeatureStatus.AVAILABLE
    }

    /**
     * Runs [block] against a client, always closing it.
     *
     * Returns null when the platform refuses outright — typically a device with
     * no AICore, which throws rather than reporting UNAVAILABLE.
     */
    private suspend fun <T> withModel(block: suspend (GenerativeModel) -> T): T? {
        val model = try {
            Generation.getClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "On-device generation is not available here", e)
            return null
        }

        return try {
            block(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "On-device generation failed", e)
            null
        } finally {
            runCatching { model.close() }
                .onFailure { Log.w(TAG, "Could not close the model client", it) }
        }
    }

    /**
     * Builds the request.
     *
     * Sampling is configured explicitly. The convenience `generateContent(String)`
     * overload leaves seed and temperature at their defaults, which makes
     * inference deterministic — the same prompt returns a byte-identical tip
     * every time, so tapping again looks broken. A fresh [seed] per call plus a
     * non-zero temperature is what makes a second tap worth making.
     *
     * The angle is varied too. Reseeding alone tends to reword one idea; asking
     * about a different aspect actually changes what is said.
     */
    private fun requestFor(promptText: String, difficulty: Difficulty): GenerateContentRequest {
        val level = when (difficulty) {
            Difficulty.EASY -> "an absolute beginner"
            Difficulty.MEDIUM -> "a beginner with a few weeks of practice"
            Difficulty.HARD -> "an improving beginner tackling a full scene"
        }
        val angle = ANGLES.random()

        val instruction = """
            Give one practical sketching tip for drawing "$promptText".
            The artist is $level working in pencil.
            Make the tip about $angle.
            Reply with two short sentences at most. No greeting, no preamble, no bullet points, no markdown.
        """.trimIndent()

        return generateContentRequest(TextPart(instruction)) {
            seed = Random.nextInt()
            temperature = SAMPLING_TEMPERATURE
            topK = SAMPLING_TOP_K
        }
    }

    /** Strips the markdown and quoting small models like to add. */
    private fun tidy(raw: String): String =
        raw.trim()
            .removeSurrounding("\"")
            .replace(MARKDOWN_NOISE, "")
            .lines()
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.trim().removePrefix("- ").removePrefix("* ") }
            .trim()

    private companion object {
        const val TAG = "TipGenerator"

        /** checkStatus talks to AICore over IPC and can hang if it is wedged. */
        const val STATUS_TIMEOUT_MS = 10_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        const val INFERENCE_TIMEOUT_MS = 45_000L

        /** High enough to vary the wording, low enough to stay sensible advice. */
        const val SAMPLING_TEMPERATURE = 0.9f
        const val SAMPLING_TOP_K = 40

        /** Rotated so repeat taps say something new rather than rephrasing. */
        val ANGLES = listOf(
            "the basic shapes to block in first",
            "getting the proportions right",
            "where beginners usually go wrong with this subject",
            "which part to draw first and why",
            "what to leave out",
            "how to check your work as you go",
        )

        val MARKDOWN_NOISE = Regex("""[*_`#]""")
    }
}
