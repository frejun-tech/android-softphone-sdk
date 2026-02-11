package com.frejun.androidsoftphonesdk.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.auth0.android.jwt.JWT
import com.frejun.androidsoftphonesdk.api.ApiClient
import com.frejun.androidsoftphonesdk.api.Permission
import com.frejun.androidsoftphonesdk.api.RefreshRequest
import com.frejun.androidsoftphonesdk.api.Role
import com.frejun.androidsoftphonesdk.api.UserRolesResponse
import com.frejun.androidsoftphonesdk.data.SipCredentials
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.frejun.androidsoftphonesdk.data.UserProfile
import com.frejun.androidsoftphonesdk.exceptions.UnauthorizedException
import com.frejun.androidsoftphonesdk.storage.TokenStorage
import java.nio.charset.StandardCharsets

/**
 * Manages all authentication-related tasks:
 * - Orchestrates the OAuth 2.0 flow.
 * - Handles token exchange and secure storage.
 * - Manages automatic token refreshing.
 * - Fetches user-specific data like SIP credentials and profiles.
 */
internal class AuthManager(
    context: Context,
    private val clientId: String,
    private val clientSecret: String,
    private val onTokensRefreshed: (TokenPayload) -> Unit
) {
    private val TAG = "AuthManager"
    private val tokenStorage = TokenStorage(context)
    private val apiService = ApiClient.create(tokenStorage, this::refreshAndRetry)

    init {
        Log.d(TAG+"Phase 1", "AuthManager initialized.")
    }

    fun getAuthorizationUrl(): String {
        val url = "https://product.frejun.com/oauth/authorize/?client_id=$clientId"
        Log.d(TAG+"Phase 2", "Generated Authorization URL: $url")
        return url
    }

    /**
     * Exchanges the authorization code from the OAuth redirect for access and refresh tokens.
     */
    suspend fun exchangeCodeForToken(code: String, email: String) {
        Log.i(TAG, "Exchanging authorization code for token for user: $email")
        // --- FIX: Correctly create the Basic Auth header ---
        val credentials = "$clientId:$clientSecret"
        val encodedCredentials = "Basic " + Base64.encodeToString(
            credentials.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        // ---------------------------------------------------

        try {
            val response = apiService.exchangeCodeForToken(code, encodedCredentials)
            if (response.success) {
                val payload = TokenPayload(response.accessToken, response.refreshToken, email)
                tokenStorage.saveTokens(payload)
                Log.i(TAG, "Token exchange successful. Tokens saved.")
            } else {
                Log.e(TAG, "Token exchange failed from API.")
                throw UnauthorizedException("Token exchange failed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token exchange", e)
            throw e
        }
    }

    suspend fun validateUserPermissions(email: String) {
        Log.i(TAG, "Validating user permissions for $email")

        Log.i(TAG, "Auth: Retrieving user roles for validation...")

        val response: UserRolesResponse = try {
            apiService.retrieveUserRoles(email)
        } catch (e: Exception) {
            Log.e(TAG, "Network error while retrieving roles", e)
            throw e
        }

        val rolesList: List<Role> = response.data ?: emptyList()

        val allPermissions: List<Permission> = rolesList.flatMap { role: Role ->
            role.permissions ?: emptyList()
        }

        val hasSDKPermission = allPermissions.any { p: Permission ->
            p.action == "integrations (iframe and sdk)"
        }

        if (!hasSDKPermission) {
            Log.e(TAG, "Permission Denied: User does not have 'integrations (iframe and sdk)' permission.")

            // Log the user out locally so they can't try again without re-authenticating
            logout()

            throw IllegalStateException("User does not have permission to use the SDK.")
        }

        Log.i(TAG, "Permission check passed. User is authorized for SDK usage.")
    }

    fun isLoggedIn(): Boolean {
        val token = tokenStorage.getTokens()?.accessToken ?: return false
        val isLoggedIn = !JWT(token).isExpired(10) // 10-second buffer
        Log.d(TAG, "isLoggedIn check: $isLoggedIn")
        return isLoggedIn
    }

    suspend fun getSipCredentials(): SipCredentials {
        val email = tokenStorage.getTokens()?.email ?: throw UnauthorizedException("Not logged in")
        Log.i(TAG+"Phase 3", "Fetching SIP credentials for $email")
        return apiService.registerSoftphone(email)
    }

    internal suspend fun refreshAccessToken(): Boolean {
        return refreshAndRetry() // This is your existing logic in AuthManager
    }

    internal fun getStoredTokens() = tokenStorage.getTokens()

    suspend fun getUserProfile(): UserProfile {
        val email = tokenStorage.getTokens()?.email ?: throw UnauthorizedException("Not logged in")
        Log.i(TAG+"Phase 3", "Fetching user profile for $email")
        val response = apiService.getUserProfile(email)
        Log.d(TAG+"Phase 3", "UserProfile Response: $response")
        Log.i(TAG+"Phase 3","response data: ${response.data}");
        if (response.success) {
            return response.data
        } else {
            throw RuntimeException("Failed to fetch user profile.")
        }
    }

    suspend fun logout() {
        Log.i(TAG, "Logging out user.")
        tokenStorage.clearTokens()
    }

    /**
     * The core token refresh logic. This is called by the ApiClient's Authenticator when a 401 is received.
     * @return True if the refresh was successful, false otherwise.
     */
    private suspend fun refreshAndRetry(): Boolean {
        Log.i(TAG, "Attempting to refresh tokens.")
        val currentTokens = tokenStorage.getTokens() ?: return false
        return try {
            val credentials = "$clientId:$clientSecret"
            val encodedCredentials = "Basic " + Base64.encodeToString(
                credentials.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )

            val response = apiService.refreshAccessToken(
                RefreshRequest(currentTokens.refreshToken),
                encodedCredentials
            )

            if (response.success) {
                val newPayload = TokenPayload(response.access, response.refresh, currentTokens.email)
                tokenStorage.saveTokens(newPayload)
                Log.i(TAG, "Token refresh successful.")
                // Notify the SDK (and in turn, the app) that tokens have changed.
                onTokensRefreshed(newPayload)
                true
            } else {
                Log.w(TAG, "Token refresh failed according to API response. Logging out.")
                logout()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token refresh. Logging out.", e)
            // If refresh fails (e.g., refresh token is invalid), log the user out.
            logout()
            false
        }
    }
}