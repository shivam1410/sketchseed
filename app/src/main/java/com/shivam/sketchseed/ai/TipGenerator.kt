package com.shivam.sketchseed.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.shivam.sketchseed.domain.model.Difficulty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

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
    data class Failed(val cause: Throwable?) : TipResult
}

/**
 * Generates a one-line drawing tip with Gemini Nano, entirely on the device.
 *
 * This is a garnish, never a dependency. Gemini Nano is unavailable on plenty of
 * hardware and the API is still in beta, so every failure path resolves to "no
 * tip" and the rest of the app carries on untouched. Nothing here is on the
 * critical path of marking a day done.
 */
class TipGenerator {

    suspend fun availability(): TipAvailability = withModel { model ->
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> TipAvailability.READY
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> TipAvailability.NEEDS_DOWNLOAD
            else -> TipAvailability.UNSUPPORTED
        }
    } ?: TipAvailability.UNSUPPORTED

    /**
     * Produces a short tip for [promptText], downloading the model first if the
     * system has not fetched it yet.
     */
    suspend fun generate(promptText: String, difficulty: Difficulty): TipResult {
        val result = withModel { model ->
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit

                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    if (!model.awaitDownload()) {
                        return@withModel TipResult.Failed(null)
                    }
                }

                else -> return@withModel TipResult.Unsupported
            }

            val response = model.generateContent(instructionFor(promptText, difficulty))
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

    /** @return true once the model is on the device. */
    private suspend fun GenerativeModel.awaitDownload(): Boolean {
        var failure: Throwable? = null
        download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadFailed -> {
                    failure = status.e
                    Log.e(TAG, "Gemini Nano download failed", status.e)
                }

                is DownloadStatus.DownloadCompleted -> Log.i(TAG, "Gemini Nano ready")
                else -> Unit
            }
        }
        return failure == null && checkStatus() == FeatureStatus.AVAILABLE
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

    private fun instructionFor(promptText: String, difficulty: Difficulty): String {
        val level = when (difficulty) {
            Difficulty.EASY -> "an absolute beginner"
            Difficulty.MEDIUM -> "a beginner with a few weeks of practice"
            Difficulty.HARD -> "an improving beginner tackling a full scene"
        }
        return """
            Give one practical sketching tip for drawing "$promptText".
            The artist is $level working in pencil.
            Focus on how to block in the basic shapes, proportions, or where beginners usually go wrong.
            Reply with two short sentences at most. No greeting, no preamble, no bullet points, no markdown.
        """.trimIndent()
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
        val MARKDOWN_NOISE = Regex("""[*_`#]""")
    }
}
