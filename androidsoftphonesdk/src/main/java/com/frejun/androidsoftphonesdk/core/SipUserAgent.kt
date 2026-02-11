// File: sip/AndroidSoftphoneSDK/src/main/java/com/frejun/androidsoftphonesdk/core/SipUserAgent.kt

package com.frejun.androidsoftphonesdk.core

import android.content.Context
import android.util.Log
import com.frejun.androidsoftphonesdk.*
import com.frejun.androidsoftphonesdk.data.CallMetaData
import com.frejun.androidsoftphonesdk.data.SipCredentials
import com.frejun.androidsoftphonesdk.pjsip.PjsipAccount
import com.frejun.androidsoftphonesdk.pjsip.PjsipCall
import com.frejun.androidsoftphonesdk.pjsip.PjsipLogWriter
import kotlinx.coroutines.*
import org.pjsip.pjsua2.*
import org.pjsip.pjsua2.pjsua2Constants.INVALID_ID

/**
 * This class is a direct parallel to the UserAgent class in the sip.js example.
 * It manages the entire lifecycle of the PJSIP Endpoint, transport, and account registration.
 * All operations are managed within a dedicated single-threaded coroutine scope to ensure thread safety with PJSIP.
 */
internal class SipUserAgent(private val context: Context) {
    private val TAG = "Softphone-SipUserAgent"

    private var endpoint: Endpoint? = null
    private var account: PjsipAccount? = null
    private var transportId: Int = INVALID_ID

    private var reRegisterAttempts = 0
    private val maxRetryAttempts = 3
    private var currentSession: CallSession? = null

    // Prevent the LogWriter from being garbage collected prematurely.
    private val logWriter = PjsipLogWriter()

    // A dedicated single-threaded coroutine context for all PJSIP operations.
    private val sipDispatcher = newSingleThreadContext("SipWorkerThread")
    private val sipScope = CoroutineScope(sipDispatcher + SupervisorJob())

    private var sipCreds: SipCredentials? = null
    private var edgeDomain: String? = null
    private var listener: SoftphoneListener? = null

    /**
     * Starts the entire SIP stack. This is the main entry point.
     * Corresponds to the constructor and startUA() in the TypeScript example.
     */
    fun start(creds: SipCredentials, domain: String, l: SoftphoneListener) {
        Log.i(TAG, "🚀 start() | User: ${creds.username} | Domain: $domain")
        this.sipCreds = creds
        this.edgeDomain = domain
        this.listener = l

        sipScope.launch {
            try {
                // Step 1: Create and initialize Endpoint
                endpoint = Endpoint()
                endpoint!!.libCreate()

                val epConfig = EpConfig()

                // --- STUN CONFIGURATION ---
//                val stunServers = StringVector()
//                stunServers.add("stun:stun.l.google.com:19302")
//                stunServers.add("stun:stun1.l.google.com:19302")
//                epConfig.uaConfig.stunServer = stunServers

                // --- DNS RESOLVER CONFIGURATION (CRITICAL FIX) ---
                // Explicitly set a public DNS server for PJSIP's resolver to use.
                // This is crucial for resolving STUN server hostnames on some Android networks.
//                val nameServers = StringVector()
//                nameServers.add("8.8.8.8")
//                epConfig.uaConfig.nameserver = nameServers
                // ----------------------------------------------------

                epConfig.uaConfig.threadCnt = 0
                epConfig.uaConfig.mainThreadOnly = false

                val logConfig = epConfig.logConfig
                logConfig.level = 4
                logConfig.consoleLevel = 4
                logConfig.msgLogging = 1
                logConfig.writer = logWriter
                logConfig.decor = logConfig.decor and (pj_log_decoration.PJ_LOG_HAS_CR or pj_log_decoration.PJ_LOG_HAS_NEWLINE).inv().toLong()

                endpoint!!.libInit(epConfig)
                Log.d(TAG, "✔ PJSIP libInit() successful with STUN and DNS configured")

                endpoint!!.libRegisterThread(Thread.currentThread().name)
                Log.d(TAG, "✔ Thread registered successfully")

                val tpConfig = TransportConfig()
                tpConfig.port = 9080.toLong()
                transportId = endpoint!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tpConfig)
                Log.i(TAG, "✔ TCP Transport created on port ${endpoint!!.transportGetInfo(transportId).localAddress}. ID: $transportId")


                endpoint!!.libStart()
                Log.i(TAG, "⭐ PJSIP libStart() successful. UA is now active.")

                withContext(Dispatchers.Main) {
                    l.onConnectionStateChanged("UserAgentState", "Connected", false)
                }

                startRegistration()

                Log.i(TAG, "Entering PJSIP event loop...")
                while (isActive) {
                    endpoint?.libHandleEvents(20)
                    yield()
                }
                Log.i(TAG, "PJSIP event loop has exited.")

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.i(TAG, "SIP scope was cancelled.")
                } else {
                    Log.e(TAG, "❌ FATAL: SIP worker coroutine crashed", e)
                    withContext(Dispatchers.Main) {
                        l.onConnectionStateChanged("UserAgentState", "Disconnected", true, e.message)
                    }
                }
            } finally {
                destroySipStack()
                Log.i(TAG, "SIP worker coroutine has finished and cleaned up.")
            }
        }
    }

    private fun startRegistration() {
        val creds = sipCreds ?: run { Log.e(TAG, "startRegistration failed: No credentials available"); return }
        val domain = edgeDomain ?: run { Log.e(TAG, "startRegistration failed: No domain available"); return }

        Log.i(TAG, "📢 Registering account: sip:${creds.username}@$domain")

        val accCfg = AccountConfig()
        accCfg.idUri = "sip:${creds.username}@$domain:9080"
        accCfg.regConfig.registrarUri = "sip:$domain:9080;transport=tls"
        accCfg.regConfig.timeoutSec = 20

        // Enable STUN for this specific account for both SIP signaling and media.
        accCfg.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
        accCfg.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT

        val regHeaders = accCfg.regConfig.headers
        regHeaders.add(SipHeader().apply {
            hName = "token"
            hValue = creds.accessToken
        })
        Log.d(TAG, "Added custom 'token' header to REGISTER request.")

        val authCreds = accCfg.sipConfig.authCreds
        Log.i(TAG, "🔐 Adding Digest authentication credentials.$authCreds")
        authCreds.clear()
        authCreds.add(AuthCredInfo("Digest", "*", creds.username, 0, creds.accessToken))
        Log.i(TAG, "🔐 Adding Digest authentication credentials.$authCreds")

        account?.delete()

        account = PjsipAccount(
            onRegStateCallback = ::handleRegistrationState,
            onIncomingCallCallback = ::handleIncomingCall
        )

        try {
            account!!.create(accCfg)
            Log.d(TAG, "PJSIP account.create() called successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create PJSIP account", e)
        }
    }

    private fun handleRegistrationState(state: ConnectionState, reason: String?) {
        Log.i(TAG, "📡 Registration Update | State: $state | Reason: $reason")
        val stateStr = if (state == ConnectionState.REGISTERED) "Registered" else "Unregistered"
        val isError = state == ConnectionState.FAILED

        MainScope().launch {
            listener?.onConnectionStateChanged("RegistererState", stateStr, isError, reason)
        }

        if (state == ConnectionState.REGISTERED) {
            reRegisterAttempts = 0
        } else if (isError && reRegisterAttempts < maxRetryAttempts) {
            reRegisterAttempts++
            Log.w(TAG, "Registration attempt $reRegisterAttempts failed. Retrying in 5 seconds...")
            sipScope.launch {
                delay(5000)
                reRegister()
            }
        }
    }

    private fun reRegister() {
        Log.i(TAG, "🔄 Attempting to re-register...")
        startRegistration()
    }

    private fun handleIncomingCall(pjsipCall: PjsipCall) {
        val session = CallSession(pjsipCall)
        currentSession = session
        val remoteIdentity = try { pjsipCall.info.remoteUri } catch (e: Exception) { "Unknown" }

        pjsipCall.onMediaStateCallback = { connectMedia(it) }

        MainScope().launch {
            listener?.onCallReceived(session, CallType.INCOMING, remoteIdentity)
        }
    }

    fun makeCall(number: String, meta: CallMetaData, sipToken: String) {
        Log.i(TAG, "☎️ Outbound call request to: $number")
        sipScope.launch {
            val acc = account ?: run {
                Log.e(TAG, "makeCall failed: Account is not initialized."); return@launch
            }
            val domain = edgeDomain ?: run {
                Log.e(TAG, "makeCall failed: Domain is not set."); return@launch
            }

            val pjsipCall = PjsipCall(acc, -1,
                onStateChanged = { call, state ->
                    MainScope().launch { listener?.onCallStateChanged(CallSession(call), state) }
                },
                onMediaStateCallback = { connectMedia(it) }
            )

            currentSession = CallSession(pjsipCall)
            val opParam = CallOpParam(true)

            val headers = SipHeaderVector().apply {
                add(SipHeader().apply { hName = "token"; hValue = sipToken })
                add(SipHeader().apply { hName = "X-Transaction-Id"; hValue = meta.transactionId ?: "" })
                add(SipHeader().apply { hName = "X-Job-Id"; hValue = meta.jobId ?: "" })
                add(SipHeader().apply { hName = "X-Reference-Id"; hValue = meta.candidateId ?: "" })
            }
            opParam.txOption = SipTxOption().apply { this.headers = headers }

            try {
                val destUri = "sip:$number@$domain;transport=udp"
                withContext(Dispatchers.Main) {
                    listener?.onCallReceived(currentSession!!, CallType.OUTGOING, number)
                }
                pjsipCall.makeCall(destUri, opParam)
            } catch (e: Exception) {
                Log.e(TAG, "❌ makeCall Exception", e)
                pjsipCall.delete()
                currentSession = null
            }
        }
    }

    private fun connectMedia(call: PjsipCall) {
        Log.i(TAG, "🎙️ connectMedia() for call ${call.id}")
        try {
            val ep = endpoint ?: return
            val audDevManager = ep.audDevManager()
            val callInfo = call.info
            for (i in 0 until callInfo.media.size) {
                val mediaInfo = callInfo.media[i]
                if (mediaInfo.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    val audioMedia = call.getAudioMedia(i)
                    audioMedia.startTransmit(audDevManager.playbackDevMedia)
                    audDevManager.captureDevMedia.startTransmit(audioMedia)
                    Log.i(TAG, "✅ Audio for call ${call.id} linked successfully.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ connectMedia Error", e)
        }
    }

    fun stop() {
        Log.i(TAG, "🛑 stop() called. Shutting down SIP coroutine scope.")
        sipScope.cancel("User initiated stop")
    }

    private fun destroySipStack() {
        Log.i(TAG, "Cleaning up SIP stack...")
        try {
            reRegisterAttempts = maxRetryAttempts + 1
            if (account?.info?.regIsActive == true) {
                account?.setRegistration(false)
                runBlocking { delay(500) }
            }
            account?.delete()
            account = null
            endpoint?.libDestroy()
            Log.d(TAG, "✔ PJSIP Endpoint destroyed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during PJSIP destruction", e)
        } finally {
            endpoint?.delete()
            endpoint = null
            Log.i(TAG, "SIP stack cleanup finished.")
        }
    }
}