package com.shivam.sketchseed.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Carries the installer session's replies back.
 *
 * [PackageInstaller] does not show its confirmation screen by itself. Committing
 * a session answers with [PackageInstaller.STATUS_PENDING_USER_ACTION] and an
 * intent, and launching that intent is what puts Android's own "update this
 * app?" dialog in front of the user. Without this step the commit simply sits
 * there and nothing visible happens.
 *
 * Everything after that is Android's to report — it shows its own success and
 * failure screens — so the terminal statuses are logged rather than surfaced a
 * second time in the app's own words.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getConfirmationIntent()
                if (confirm == null) {
                    Log.e(TAG, "Installer asked for confirmation without an intent to show")
                    return
                }
                // Started from a receiver, so it needs its own task.
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Update installed")

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Log.i(TAG, "The user declined the update")

            else -> Log.w(
                TAG,
                "Install failed with status $status: " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getConfirmationIntent(): Intent? =
        // The typed getParcelableExtra arrived in API 33; this app runs from 26.
        getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
