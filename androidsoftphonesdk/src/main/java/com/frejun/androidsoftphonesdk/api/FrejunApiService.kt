package com.frejun.androidsoftphonesdk.api

import com.frejun.androidsoftphonesdk.data.SipCredentials
import com.frejun.androidsoftphonesdk.data.UserProfile
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface defining the FreJun REST API endpoints.
 */
internal interface FrejunApiService {

    companion object {
        const val BASE_URL = "https://api.frejun.com/api/v1/"
    }

    @GET("oauth/token/")
    suspend fun exchangeCodeForToken(
        @Query("code") code: String,
        @Header("Authorization") encodedCredentials: String // "Basic base64(clientId:clientSecret)"
    ): TokenResponse

    @POST("oauth/token/refresh/")
    suspend fun refreshAccessToken(
        @Body refreshRequest: RefreshRequest,
        @Header("Authorization") encodedCredentials: String // "Basic base64(clientId:clientSecret)"
    ): RefreshResponse

    @GET("calls/register-softphone/")
    suspend fun registerSoftphone(
        @Query("email") email: String
    ): SipCredentials // Assuming SipCredentials is the direct response model

    @GET("integrations/profile/")
    suspend fun getUserProfile(
        @Query("email") email: String
    ): UserProfileResponse

    @GET("auth/retrieve-user-roles/")
    suspend fun retrieveUserRoles(
        @Query("email") email: String
    ): UserRolesResponse
}

// --- Data classes for API Models ---

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("success") val success: Boolean
)

data class RefreshRequest(
    @SerializedName("refresh") val refreshToken: String
)

data class RefreshResponse(
    @SerializedName("access") val access: String,
    @SerializedName("refresh") val refresh: String,
    @SerializedName("success") val success: Boolean
)

data class UserProfileResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: UserProfile
)

data class UserRolesResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: List<Role>? = null
)

data class Permission(
    @SerializedName("action") val action: String
)

data class Role(
    @SerializedName("permissions") val permissions: List<Permission>
)