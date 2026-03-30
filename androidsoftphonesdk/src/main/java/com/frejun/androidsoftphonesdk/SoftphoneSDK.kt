package com.frejun.androidsoftphonesdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import com.frejun.androidsoftphonesdk.auth.AuthManager
import com.frejun.androidsoftphonesdk.core.CallSession
import com.frejun.androidsoftphonesdk.core.SipManager
import com.frejun.androidsoftphonesdk.data.CallMetaData
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.frejun.androidsoftphonesdk.data.VirtualNumber
import com.frejun.androidsoftphonesdk.exceptions.InvalidTokenException
import com.frejun.androidsoftphonesdk.exceptions.SdkNotInitializedException
import com.frejun.androidsoftphonesdk.exceptions.UnauthorizedException
import com.frejun.androidsoftphonesdk.service.SoftphoneService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SoftphoneSDK {

    private const val TAG = "SoftphoneSDK"

    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var authManager: AuthManager
    private lateinit var sipManager: SipManager
    private var currentEdgeDomain: String? = null

    private val sdkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appListener: SoftphoneListener? = null

    fun initialize(context: Context, clientId: String, clientSecret: String) {
        if (isInitialized) {
            Log.w(TAG, "SDK already initialized.")
            return
        }
        Log.i(TAG, "Initializing SDK...")
        this.appContext = context.applicationContext
        this.authManager = AuthManager(this.appContext, clientId, clientSecret, ::onTokensRefreshed)
        this.sipManager = SipManager(this.appContext)
        isInitialized = true
        Log.i(TAG, "SDK Initialization complete.")
    }

    fun login() {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG, "login() called for browser flow.")
        val authUrl = authManager.getAuthorizationUrl()
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(appContext, Uri.parse(authUrl))
    }

    suspend fun login(accessToken: String, refreshToken: String, email: String) {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG, "login() called for direct token login.")
        val payload = TokenPayload(accessToken, refreshToken, email)
        authManager.validateAndSaveSession(payload)
    }

    internal fun getSipManagerInternal(): SipManager {
        if (!isInitialized) throw SdkNotInitializedException()
        return sipManager
    }

    fun handleRedirect(url: Uri) {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG, "handleRedirect() called with URL: $url")
        val code = url.getQueryParameter("code")
        val email = url.getQueryParameter("email")

        if (code != null && email != null) {
            sdkScope.launch {
                try {
                    authManager.exchangeCodeForToken(code, email)
                    authManager.validateUserPermissions(email)
                } catch (e: Exception) {
                    handleAuthFailure("Authentication failed", e)
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

    fun setListener(listener: SoftphoneListener?) {
        if (!isInitialized) return
        this.appListener = listener
        sipManager.setListener(listener)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun start(listener: SoftphoneListener) {
        if (!isInitialized) throw SdkNotInitializedException()
        Log.i(TAG, "start() called.")
        setListener(listener)
        if (!authManager.isLoggedIn()) {
            handleAuthFailure("Start failed: User is not logged in.")
            return
        }

        executeWithRetry("start") {
            try {
                val profile = authManager.getUserProfile()
                val sipCreds = authManager.getSipCredentials()
                currentEdgeDomain = profile.edgeDomain

                val serviceIntent = Intent(appContext, SoftphoneService::class.java).apply {
                    putExtra(SoftphoneService.EXTRA_SIP_CREDS, sipCreds)
                    putExtra(SoftphoneService.EXTRA_EDGE_DOMAIN, profile.edgeDomain)
                }
                appContext.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                handleAuthFailure("SDK start failed", e)
                throw e
            }
        }
    }

    fun makeCall(
        destination: String,
        fromVirtualNumber: String? = null,
        transactionId: String? = null,
        jobId: String? = null,
        candidateId: String? = null
    ) {
        if (!isInitialized || !sipManager.isStarted()) throw IllegalStateException("SDK not started. Call start() first.")

        Log.i(TAG, "makeCall() initiated. Destination: '$destination', VN: '$fromVirtualNumber'")

        sdkScope.launch {
            Log.d(TAG, "Executing makeCall logic within sdkScope (background thread).")

            executeWithRetry("makeCall") {
                val currentUserProfile = authManager.getUserProfile()
                val primaryVn = currentUserProfile.virtualNumbers?.find { it.isDefaultCallingNumber }?.let { "${it.countryCode}${it.number}" }

                if (fromVirtualNumber != null && fromVirtualNumber != primaryVn) {
                    val newProfile = authManager.updatePrimaryVirtualNumber(fromVirtualNumber)
                    if (newProfile.edgeDomain != currentEdgeDomain) {
                        Log.w(TAG, "Edge domain has changed from $currentEdgeDomain to ${newProfile.edgeDomain}. Restarting SIP connection.")
                        currentEdgeDomain = newProfile.edgeDomain
                        val newSipCreds = authManager.getSipCredentials()
                        sipManager.restart(newSipCreds, newProfile.edgeDomain)
                    }
                }

                val tokens = authManager.getStoredTokens() ?: return@executeWithRetry
                val meta = CallMetaData(transactionId, jobId, candidateId)

                Log.d(TAG, "Delegating call to SipManager with metadata: $meta")
                sipManager.makeCall(destination, meta, tokens.accessToken)
            }
        }
    }

    fun answerCall(session: CallSession?) {
        val callSession = session ?: return
        Log.i(TAG, "answerCall() initiated for call ID: ${callSession.callId}")
        sipManager.answerCall(callSession)
    }

    fun hangupCall(session: CallSession?) {
        val callSession = session ?: return
        Log.i(TAG, "hangupCall() initiated for call ID: ${callSession.callId}")
        sipManager.hangupCall(callSession)
    }

    suspend fun getVirtualNumbers(): List<VirtualNumber>? {
        if (!isInitialized) throw SdkNotInitializedException()
        return executeWithRetry("getVirtualNumbers") {
            authManager.getUserProfile().virtualNumbers
        }
    }

    fun getTokens(): TokenPayload? {
        if (!isInitialized) return null
        return authManager.getStoredTokens()
    }

    fun logout() {
        if (!isInitialized) return
        Log.i(TAG, "logout() called.")
        appContext.stopService(Intent(appContext, SoftphoneService::class.java))
        sdkScope.launch {
            authManager.logout()
        }
        appListener?.onConnectionStateChanged("UserAgentState", "Disconnected", false, "User logged out.")
        appListener = null
        currentEdgeDomain = null
        Log.i(TAG, "Logout complete.")
    }

    private fun handleAuthFailure(message: String, e: Exception? = null) {
        Log.e(TAG, "$message: ${e?.message}")
        sdkScope.launch { authManager.logout() }
        appListener?.onConnectionStateChanged(
            "UserAgentState", "Disconnected", true, "Access Denied: ${e?.message ?: message}"
        )
    }

    private suspend fun <T> executeWithRetry(actionName: String, action: suspend () -> T): T {
        return try {
            action()
        } catch (e: Exception) {
            if (e is InvalidTokenException || (e is retrofit2.HttpException && e.code() == 401)) {
                Log.w(TAG, "Caught 401 in $actionName. Initiating Auto-Refresh...")
                val success = authManager.refreshAccessToken()
                if (success) {
                    authManager.getStoredTokens()?.let { appListener?.onSessionRefreshed(it) }
                    return action()
                } else {
                    handleAuthFailure("Session expired during $actionName retry.")
                    throw UnauthorizedException("Session expired.")
                }
            }
            throw e
        }
    }

    private fun onTokensRefreshed(payload: TokenPayload) {
        Log.i(TAG, "onTokensRefreshed: Notifying listener of token refresh.")
        appListener?.onSessionRefreshed(payload)
    }
}