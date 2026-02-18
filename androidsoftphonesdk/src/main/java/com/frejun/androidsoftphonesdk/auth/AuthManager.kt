package com.frejun.androidsoftphonesdk.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.auth0.android.jwt.JWT
import com.frejun.androidsoftphonesdk.api.ApiClient
import com.frejun.androidsoftphonesdk.api.Permission
import com.frejun.androidsoftphonesdk.api.RefreshRequest
import com.frejun.androidsoftphonesdk.api.Role
import com.frejun.androidsoftphonesdk.api.UpdateUserProfileRequest
import com.frejun.androidsoftphonesdk.api.UserRolesResponse
import com.frejun.androidsoftphonesdk.data.SipCredentials
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.frejun.androidsoftphonesdk.data.UserProfile
import com.frejun.androidsoftphonesdk.exceptions.InvalidTokenException
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
    // Corrected: Pass the renamed and now-internal 'refreshAccessToken' method reference
    private val apiService = ApiClient.create(tokenStorage, this::refreshAccessToken)
    private var userProfile: UserProfile? = null

    init {
        Log.d(TAG+"Phase 1", "AuthManager initialized.")
    }

    fun getAuthorizationUrl(): String {
        val url = "https://product.frejun.com/oauth/authorize/?client_id=$clientId"
        Log.d(TAG+"Phase 2", "Generated Authorization URL: $url")
        return url
    }

    suspend fun exchangeCodeForToken(code: String, email: String) {
        Log.i(TAG, "Exchanging authorization code for token for user: $email")
        val credentials = "$clientId:$clientSecret"
        val encodedCredentials = "Basic " + Base64.encodeToString(
            credentials.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )

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

    suspend fun validateAndSaveSession(payload: TokenPayload) {
        val tokenStatus = isTokenValid(payload.accessToken)
        var finalPayload = payload

        if (tokenStatus == "EXPIRED") {
            Log.w(TAG, "Provided access token is expired, attempting to refresh.")
            val newTokens = refreshWithToken(payload.refreshToken)
            if (newTokens != null) {
                finalPayload = newTokens
            } else {
                logout() // Clear invalid session
                throw UnauthorizedException("Token is expired and refresh failed.")
            }
        } else if (tokenStatus == "INVALID") {
            logout() // Clear invalid session
            throw InvalidTokenException("validateAndSaveSession", "The provided access token is invalid.")
        }

        tokenStorage.saveTokens(finalPayload)
        validateUserPermissions(finalPayload.email)
        Log.i(TAG, "Session validated and saved successfully.")
    }

    suspend fun validateUserPermissions(email: String) {
        Log.i(TAG, "Validating user permissions for $email")

        val response: UserRolesResponse = try {
            apiService.retrieveUserRoles(email)
        } catch (e: Exception) {
            Log.e(TAG, "Network error while retrieving roles", e)
            throw e
        }

        val rolesList: List<Role> = response.data ?: emptyList()
        val allPermissions: List<Permission> = rolesList.flatMap { it.permissions }

        val hasSDKPermission = allPermissions.any { it.action == "integrations (iframe and sdk)" }

        if (!hasSDKPermission) {
            Log.e(TAG, "Permission Denied: User does not have 'integrations (iframe and sdk)' permission.")
            logout()
            throw IllegalStateException("User does not have permission to use the SDK.")
        }

        Log.i(TAG, "Permission check passed. User is authorized for SDK usage.")
    }

    fun isLoggedIn(): Boolean {
        val token = tokenStorage.getTokens()?.accessToken ?: return false
        return isTokenValid(token) == "VALID"
    }

    private fun isTokenValid(token: String): String {
        return try {
            if (JWT(token).isExpired(10)) "EXPIRED" else "VALID"
        } catch (e: Exception) {
            "INVALID"
        }
    }

    suspend fun getSipCredentials(): SipCredentials {
        val email = tokenStorage.getTokens()?.email ?: throw UnauthorizedException("Not logged in")
        Log.i(TAG+"Phase 3", "Fetching SIP credentials for $email")
        return apiService.registerSoftphone(email)
    }

    internal fun getStoredTokens() = tokenStorage.getTokens()

    suspend fun getUserProfile(): UserProfile {
        if (userProfile != null) return userProfile!!

        val email = tokenStorage.getTokens()?.email ?: throw UnauthorizedException("Not logged in")
        Log.i(TAG+"Phase 3", "Fetching user profile for $email")
        val response = apiService.getUserProfile(email)
        if (response.success) {
            userProfile = response.data
            return response.data
        } else {
            throw RuntimeException("Failed to fetch user profile.")
        }
    }

    suspend fun updatePrimaryVirtualNumber(virtualNumber: String): UserProfile {
        val email = tokenStorage.getTokens()?.email ?: throw UnauthorizedException("Not logged in")
        Log.i(TAG, "Updating primary virtual number to $virtualNumber for $email")

        val response = apiService.updateUserProfile(email, UpdateUserProfileRequest(virtualNumber))
        if (response.success) {
            Log.i(TAG, "Successfully updated virtual number.")
            userProfile = response.data
            return response.data
        } else {
            throw RuntimeException("Failed to update virtual number.")
        }
    }

    suspend fun logout() {
        Log.i(TAG, "Logging out user.")
        tokenStorage.clearTokens()
        userProfile = null
    }

    private suspend fun refreshWithToken(refreshToken: String): TokenPayload? {
        return try {
            val credentials = "$clientId:$clientSecret"
            val encodedCredentials = "Basic " + Base64.encodeToString(
                credentials.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )

            val response = apiService.refreshAccessToken(
                RefreshRequest(refreshToken),
                encodedCredentials
            )

            if (response.success) {
                val currentEmail = tokenStorage.getTokens()?.email ?: ""
                TokenPayload(response.access, response.refresh, currentEmail)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The core token refresh logic. This is called by the ApiClient's Authenticator when a 401 is received.
     * @return True if the refresh was successful, false otherwise.
     */
    internal suspend fun refreshAccessToken(): Boolean {
        Log.i(TAG, "Attempting to refresh tokens.")
        val currentTokens = tokenStorage.getTokens() ?: return false

        val newPayload = refreshWithToken(currentTokens.refreshToken)

        return if (newPayload != null) {
            tokenStorage.saveTokens(newPayload)
            Log.i(TAG, "Token refresh successful.")
            onTokensRefreshed(newPayload)
            true
        } else {
            Log.w(TAG, "Token refresh failed. Logging out.")
            logout()
            false
        }
    }
}