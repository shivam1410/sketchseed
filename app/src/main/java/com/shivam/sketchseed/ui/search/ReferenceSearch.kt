package com.shivam.sketchseed.ui.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * Opens image searches for a prompt.
 *
 * Searching for "<prompt> sketch" rather than the bare noun matters: it returns
 * line drawings a beginner can actually follow instead of photographs.
 */
object ReferenceSearch {

    fun pinterest(query: String): Uri =
        "https://www.pinterest.com/search/pins/?q=${Uri.encode(query)}".toUri()

    fun googleImages(query: String): Uri =
        "https://www.google.com/search?tbm=isch&q=${Uri.encode(query)}".toUri()

    /** @return false when the device has nothing that can open a web link. */
    fun open(context: Context, uri: Uri): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app available to open $uri", e)
        false
    }

    private const val TAG = "ReferenceSearch"
}
