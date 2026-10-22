package app.nwmovie.data

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.nwmovie.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** The Google account chooser (no password). Gives back a Google ID token for the chosen account. */
object GoogleAuth {
    class Failed(val userMessage: String, val cancelled: Boolean = false) : Exception(userMessage)

    data class Account(val idToken: String, val email: String)

    suspend fun pick(activity: Activity): Account {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val cred = CredentialManager.create(activity).getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val g = GoogleIdTokenCredential.createFrom(cred.data)
                return Account(g.idToken, g.id)
            }
            throw Failed("That kind of account is not supported.")
        } catch (e: GetCredentialCancellationException) {
            throw Failed("Cancelled", cancelled = true)
        } catch (e: NoCredentialException) {
            throw Failed("No Google account was found. Add one in your phone's Settings → Accounts, then try again.")
        } catch (e: GetCredentialException) {
            throw Failed("Google sign-in is not available right now. Please try again later.")
        } catch (e: GoogleIdTokenParsingException) {
            throw Failed("Could not read the Google account.")
        }
    }
}
