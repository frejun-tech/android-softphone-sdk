package com.frejun.androidsoftphonesdk.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.google.gson.Gson

/**
 * Manages the secure storage of authentication tokens.
 *
 * This class uses EncryptedSharedPreferences to ensure that tokens (access, refresh)
 * are stored securely on the device's local storage.
 */
internal class TokenStorage(context: Context) {

    private val gson = Gson()

    // Create the master key for encryption
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // Create the encrypted preferences instance
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val PREFS_FILE_NAME = "frejun_secure_sdk_prefs"
        private const val KEY_TOKEN_PAYLOAD = "auth_token_payload"
    }

    /**
     * Saves the token payload securely.
     * @param payload The TokenPayload object to save.
     */
    fun saveTokens(payload: TokenPayload) {
        val jsonPayload = gson.toJson(payload)
        sharedPreferences.edit().putString(KEY_TOKEN_PAYLOAD, jsonPayload).apply()
    }

    /**
     * Retrieves the stored token payload.
     * @return The stored TokenPayload, or null if none exists.
     */
    fun getTokens(): TokenPayload? {
        val jsonPayload = sharedPreferences.getString(KEY_TOKEN_PAYLOAD, null)
        return if (jsonPayload != null) {
            try {
                gson.fromJson(jsonPayload, TokenPayload::class.java)
            } catch (e: Exception) {
                // In case of parsing error (e.g., data model changed), clear the invalid data.
                clearTokens()
                null
            }
        } else {
            null
        }
    }

    /**
     * Clears all stored tokens from secure storage.
     */
    fun clearTokens() {
        sharedPreferences.edit().remove(KEY_TOKEN_PAYLOAD).apply()
    }
}