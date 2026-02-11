package com.frejun.androidsoftphonesdk.data

import com.google.gson.annotations.SerializedName

/**
 * Represents the core authentication payload containing access and refresh tokens.
 * This object is securely stored on the device to maintain the user's session.
 *
 * @property accessToken The short-lived JWT used to authenticate API requests.
 * @property refreshToken The long-lived token used to obtain a new accessToken when it expires.
 * @property email The email address of the authenticated user.
 */
data class TokenPayload(
    @SerializedName("access_token")
    val accessToken: String,

    @SerializedName("refresh_token")
    val refreshToken: String,

    @SerializedName("email")
    val email: String
)