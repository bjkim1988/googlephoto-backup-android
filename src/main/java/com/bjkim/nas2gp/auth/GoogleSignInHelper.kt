package com.bjkim.nas2gp.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

/**
 * Handles Google Sign-In with permissions required for Google Photos upload.
 */
object GoogleSignInHelper {

    // Scopes needed to upload to Google Photos (app created data)
    private val scopeReadOnly = Scope("https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata")
    private val scopeEdit = Scope("https://www.googleapis.com/auth/photoslibrary.edit.appcreateddata")
    private val scopeAppend = Scope("https://www.googleapis.com/auth/photoslibrary.appendonly")

    /**
     * Gets the prepared GoogleSignInClient for requesting scopes.
     * Note: You must provide a Web Client ID string if you need offline access / refresh tokens.
     * For now, we request basic sign in and the 3 photos scopes.
     */
    fun getClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scopeReadOnly, scopeEdit, scopeAppend)
            .build()
            
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Returns the intent that starts the sign-in flow.
     */
    fun getSignInIntent(context: Context): Intent {
        return getClient(context).signInIntent
    }

    /**
     * Helper to extract the account from the Intent result in onActivityResult
     */
    fun getSignedInAccountFromIntent(data: Intent?): Task<GoogleSignInAccount> {
        return GoogleSignIn.getSignedInAccountFromIntent(data)
    }

    /**
     * Returns the currently signed-in account, or null if not signed in or scopes are missing.
     */
    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && GoogleSignIn.hasPermissions(account, scopeReadOnly, scopeEdit, scopeAppend)) {
            account
        } else {
            null
        }
    }
    
    /**
     * Signs out the current user.
     */
    fun signOut(context: Context): Task<Void> {
        return getClient(context).signOut()
    }
}

