// File: sip/AndroidSoftphoneSDK/src/main/java/com/frejun/androidsoftphonesdk/SoftphoneSDK.kt

package com.frejun.androidsoftphonesdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import com.frejun.androidsoftphonesdk.auth.AuthManager
import com.frejun.androidsoftphonesdk.core.SipManager
import com.frejun.androidsoftphonesdk.data.CallMetaData
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.frejun.androidsoftphonesdk.exceptions.InvalidTokenException
import com.frejun.androidsoftphonesdk.exceptions.SdkNotInitializedException
import com.frejun.androidsoftphonesdk.exceptions.UnauthorizedException
import com.frejun.androidsoftphonesdk.service.SoftphoneService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main public entry point for the Softphone SDK.
 * This is a singleton object.
 */
object SoftphoneSDK {

    private const val TAG = "SoftphoneSDK"

    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var authManager: AuthManager
    private lateinit var sipManager: SipManager

    private val sdkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The listener provided by the consuming app
    private var appListener: SoftphoneListener? = null

    /**
     * Initializes the SDK. Must be called once, typically in the Application's onCreate.
     */
    fun initialize(context: Context, clientId: String, clientSecret: String) {
        if (isInitialized) {
            Log.w(TAG+"Phase 1", "SDK already initialized.")
            return
        }
        Log.i(TAG+"Phase 1", "Initializing SDK...")
        this.appContext = context.applicationContext
        this.authManager = AuthManager(this.appContext, clientId, clientSecret, ::onTokensRefreshed)
        this.sipManager = SipManager(this.appContext)
        isInitialized = true

        // Restore session if possible
        sdkScope.launch {
            Log.i(TAG+"Phase 1", "Checking for active user session...")
            Log.i(TAG+"Phase 1","authManager.isLoggedIn(): "+authManager.isLoggedIn());
            if (authManager.isLoggedIn()) {
                Log.i(TAG+"Phase 1", "User is already logged in from a previous session.")
                // You might want to auto-start the service here if a valid session exists
            } else {
                Log.i(TAG+"Phase 1", "No active user session found.")
            }
        }
        Log.i(TAG+"Phase 1", "SDK Initialization complete.")
    }

    /**
     * Initiates the standard browser OAuth login flow.
     */
    fun login() {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG+"Phase 2", "login() called.")
        val authUrl = authManager.getAuthorizationUrl()
        Log.d(TAG+"Phase 2", "Launching Custom Tab with URL: $authUrl")
        val customTabsIntent = CustomTabsIntent.Builder().build()

        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(appContext, Uri.parse(authUrl))
    }

    internal fun getSipManagerInternal(): SipManager {
        if (!isInitialized) throw SdkNotInitializedException()
        return sipManager
    }

    /**
     * Handles the redirect from the OAuth flow to exchange the code for tokens.
     */
    fun handleRedirect(url: Uri) {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG, "handleRedirect() called with URL: $url")
        val code = url.getQueryParameter("code")
        val email = url.getQueryParameter("email")

        if (code != null && email != null) {
            Log.d(TAG, "Authorization code and email found. Exchanging for token.")
            sdkScope.launch {
                try{
                    // Phase 1: Get Tokens
                    authManager.exchangeCodeForToken(code, email)

                    // Phase 2: Validate Permissions (The new logic)
                    authManager.validateUserPermissions(email)

                }catch (e: Exception){
                    Log.e(TAG, "Authentication failed: ${e.message}")
                    // Clear state if any part of the handshake/validation fails
                    authManager.logout()

                    // Notify the UI if needed via the listener
                    appListener?.onConnectionStateChanged(
                        "UserAgentState",
                        "Disconnected",
                        true,
                        "Access Denied: ${e.message}"
                    )
                }
            }
        } else {
            Log.e(TAG, "OAuth redirect missing 'code' or 'email' parameter.")
        }
    }

    fun isLoggedIn(): Boolean {
        if (!isInitialized) return false
        return authManager.isLoggedIn()
    }

    private suspend fun <T> executeWithRetry(actionName: String, action: suspend () -> T): T {
        return try {
            action()
        } catch (e: Exception) {
            // Check if the error is a 401 or InvalidToken (adjust based on your API response)
            if (e is InvalidTokenException || (e is retrofit2.HttpException && e.code() == 401)) {
                Log.w(TAG, "Softphone: ⚠️ Caught 401 in $actionName. Initiating Auto-Refresh...")

                // 1. Attempt Refresh
                val success = authManager.refreshAccessToken()

                if (success) {
                    // 2. Notify the app listener about the new session
                    authManager.getStoredTokens()?.let {
                        Log.d(TAG, "Softphone: Emitting new tokens via onSessionRefreshed")
                        appListener?.onSessionRefreshed(it)
                    }

                    // 3. Retry the original action
                    return action()
                } else {
                    // 4. Refresh failed, clean up and throw
                    Log.e(TAG, "Softphone: Session expired during $actionName retry.")
                    logout()
                    throw UnauthorizedException("Session expired.")
                }
            }
            // If it's any other error, just throw it
            throw e
        }
    }

    /**
     * Starts the SIP service and connects to the server.
     * @param listener The listener to receive SDK events.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun start(listener: SoftphoneListener) {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG+"Phase 3", "start() called.")
        Log.d(TAG+"Phase 3", "authManager.isLoggedIn(): "+authManager.isLoggedIn());
        if (!authManager.isLoggedIn()) {
            Log.w(TAG+"Phase 3", "Start failed: User is not logged in.")
            listener.onConnectionStateChanged(
                "UserAgentState",
                "Disconnected",
                true,
                "User is not logged in."
            )
            return
        }
        Log.i(TAG+"Phase 3", "start() called.")
        this.appListener = listener

        executeWithRetry("start") {
            try{
                val email = authManager.getStoredTokens()?.email
                    ?: throw UnauthorizedException("No email found")

                // This call to getSipCredentials() might throw 401, handled by retry
                val sipCreds = authManager.getSipCredentials()
                Log.d(TAG+"Phase 3", "SipCreds fetched successfully. :- "+sipCreds)
                val profile = authManager.getUserProfile()
                Log.i(TAG+"Phase 3", "profile fetched successfully. :- "+profile)

                sipManager.setListener(listener)

                val serviceIntent = Intent(appContext, SoftphoneService::class.java).apply {
                    putExtra(SoftphoneService.EXTRA_SIP_CREDS, sipCreds)
                    putExtra(SoftphoneService.EXTRA_EDGE_DOMAIN, profile.edgeDomain)
                }
                appContext.startForegroundService(serviceIntent)
            }catch (e: Exception){
                appListener?.onConnectionStateChanged(
                    "UserAgentState",
                    "Disconnected",
                    false,
                    "User logged out."
                )
                throw e
            }
        }
    }

    /**
     * Initiates an outbound call.
     * @param destination The number to call in E.164 format.
     */
    fun makeCall( destination: String,
                  transactionId: String? = null,
                  jobId: String? = null,
                  candidateId: String? = null) {
        Log.i(TAG, "makeCall() called for destination: $destination")
        if (!isInitialized || !sipManager.isStarted()) throw IllegalStateException("SDK not started. Call start() first.")
//        sipManager.makeCall(destination)
        sdkScope.launch {
            executeWithRetry("makeCall") {
                val tokens = authManager.getStoredTokens() ?: return@executeWithRetry

                // 2. Prepare Metadata
                val meta = CallMetaData(
                    transactionId = transactionId,
                    jobId = jobId,
                    candidateId = candidateId
                )

                // 3. Execute via Manager
                sipManager.makeCall(destination, meta, tokens.accessToken)
            }
        }
    }

    /**
     * Disconnects from the SIP server and logs the user out.
     */
    fun logout() {
        if (!isInitialized) return
        Log.i(TAG, "logout() called.")
        appContext.stopService(Intent(appContext, SoftphoneService::class.java))
        sdkScope.launch {
            authManager.logout()
        }
        appListener?.onConnectionStateChanged(
            "UserAgentState",
            "Disconnected",
            false,
            "User logged out."
        )
        appListener = null
        Log.i(TAG, "Logout complete.")
    }

    /**
     * Internal callback passed to AuthManager to notify the app of token refreshes.
     */
    private fun onTokensRefreshed(payload: TokenPayload) {
        Log.i(TAG, "onTokensRefreshed: Notifying listener of token refresh.")
        appListener?.onSessionRefreshed(payload)
    }
}