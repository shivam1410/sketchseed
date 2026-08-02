package com.shivam.sketchseed.data

import android.content.Context
import com.shivam.sketchseed.domain.model.PromptPack
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Reads the bundled prompt pack out of assets, once, and keeps it in memory. */
class PromptRepository(
    private val context: Context,
    private val json: Json = appJson,
) {
    private val mutex = Mutex()
    private var cached: PromptPack? = null

    /**
     * @throws IOException if the bundled pack is missing or unreadable. That is
     * a packaging failure rather than a runtime condition, so it is surfaced
     * rather than papered over with an empty pack.
     */
    suspend fun pack(): PromptPack = cached ?: mutex.withLock {
        cached ?: load().also { cached = it }
    }

    private suspend fun load(): PromptPack = withContext(Dispatchers.IO) {
        val raw = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw IOException("Bundled prompt pack '$ASSET_NAME' could not be read", e)
        }
        json.decodeFromString(PromptPack.serializer(), raw)
    }

    private companion object {
        const val ASSET_NAME = "prompts.json"
    }
}
