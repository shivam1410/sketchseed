package com.shivam.sketchseed.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Result of asking Google for a Drive access token. */
sealed interface AuthOutcome {
    data class Token(val accessToken: String) : AuthOutcome

    /** The user has to approve access. Launch [pendingIntent] and come back. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthOutcome

    data class Failed(val cause: Throwable?) : AuthOutcome
}

/**
 * Obtains a Drive access token via Play Services.
 *
 * Uses only [DRIVE_APPDATA_SCOPE], which reaches nothing but this app's own
 * hidden configuration folder — it cannot see, list or touch anything else in
 * the user's Drive, and the backups never clutter My Drive. Google classes it
 * non-sensitive, so no OAuth verification review is needed. Full `drive` would
 * mean an app review plus an annual third-party security assessment.
 *
 * The trade-off is that a hidden folder cannot be inspected or moved by its
 * owner, which is why the app also offers a plain file export.
 *
 * There is no client ID or secret here on purpose: Android OAuth clients are
 * identified by package name plus signing certificate, checked by Play Services
 * against what is registered in the Cloud console. Nothing secret ships in the
 * APK.
 */
class DriveAuthorizer(private val context: Context) {

    private val request: AuthorizationRequest by lazy {
        AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
    }

    /**
     * Asks for a token without showing UI.
     *
     * Returns [AuthOutcome.NeedsConsent] the first time, or whenever the user
     * has revoked access; the caller launches the intent and then feeds the
     * result back through [fromConsentResult].
     */
    suspend fun authorize(): AuthOutcome = try {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        val pending = result.pendingIntent
        val token = result.accessToken

        when {
            result.hasResolution() && pending != null -> AuthOutcome.NeedsConsent(pending)
            token != null -> AuthOutcome.Token(token)
            else -> {
                Log.e(TAG, "Authorization returned neither a token nor a resolution")
                AuthOutcome.Failed(null)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Drive authorization failed", e)
        AuthOutcome.Failed(e)
    }

    /** Reads the token out of the consent screen's result. */
    fun fromConsentResult(data: Intent?): AuthOutcome = try {
        val result = Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(data)
        val token = result.accessToken

        if (token == null) {
            Log.e(TAG, "Consent completed without an access token")
            AuthOutcome.Failed(null)
        } else {
            AuthOutcome.Token(token)
        }
    } catch (e: ApiException) {
        Log.e(TAG, "Consent was refused or failed", e)
        AuthOutcome.Failed(e)
    }

    /** Drops the cached token so the next call re-authorizes. */
    suspend fun signOut() {
        try {
            Identity.getAuthorizationClient(context)
                .revokeAccess(
                    com.google.android.gms.auth.api.identity.RevokeAccessRequest.builder()
                        .setScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                        .build(),
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing the user can do about a failed revoke, but never swallow it.
            Log.w(TAG, "Could not revoke Drive access", e)
        }
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val TAG = "DriveAuthorizer"
    }
}
