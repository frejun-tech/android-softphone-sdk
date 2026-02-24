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

internal class SipUserAgent(private val context: Context) {
    private val TAG = "Softphone-SipUserAgent"

    private var endpoint: Endpoint? = null
    private var account: PjsipAccount? = null
    private var transportId: Int = INVALID_ID

    private var reRegisterAttempts = 0
    private val maxRetryAttempts = 3
    private var currentSession: CallSession? = null

    private val logWriter = PjsipLogWriter()
    private val sipDispatcher = newSingleThreadContext("SipWorkerThread")
    private val sipScope = CoroutineScope(sipDispatcher + SupervisorJob())

    private var sipCreds: SipCredentials? = null
    private var edgeDomain: String? = null
    var listener: SoftphoneListener? = null
        private set

    fun setListener(l: SoftphoneListener?) {
        Log.d(TAG, "Listener updated in SipUserAgent.")
        this.listener = l
    }

    fun start(creds: SipCredentials, domain: String) {
        Log.i(TAG, "🚀 start() | User: ${creds.username} | Domain: $domain")
        this.sipCreds = creds
        this.edgeDomain = "sip.sg.frejun.com"

        sipScope.launch {
            try {
                endpoint = Endpoint()
                endpoint!!.libCreate()

                val epConfig = EpConfig()
                epConfig.uaConfig.threadCnt = 1
//                epConfig.uaConfig.mainThreadOnly = false

                val uaConfig = epConfig.uaConfig
                val stunServers = StringVector()
                stunServers.add("stun.l.google.com:19302")
                uaConfig.stunServer = stunServers

                val logConfig = epConfig.logConfig
                logConfig.level = 6
                logConfig.consoleLevel = 6
                logConfig.msgLogging = 1
                logConfig.writer = logWriter
                logConfig.decor = logConfig.decor and (pj_log_decoration.PJ_LOG_HAS_CR or pj_log_decoration.PJ_LOG_HAS_NEWLINE).inv().toLong()

                endpoint!!.libInit(epConfig)
                Log.d(TAG, "✔ PJSIP libInit() successful")

                endpoint!!.libRegisterThread(Thread.currentThread().name)
                Log.d(TAG, "✔ Thread registered successfully")

                val tpConfig = TransportConfig()
                tpConfig.port = 9080.toLong()
                transportId = endpoint!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tpConfig)
                Log.i(TAG, "✔ TLS Transport created on port ${endpoint!!.transportGetInfo(transportId).localAddress}. ID: $transportId")

                endpoint!!.libStart()
                Log.i(TAG, "⭐ PJSIP libStart() successful. UA is now active.")

                // Disable all video codecs
                val videoCodecs = endpoint!!.videoCodecEnum2()
                for (i in 0 until videoCodecs.size) {
                    Log.i(TAG,"Disabling video codec: ${videoCodecs[i].codecId}");
                    endpoint!!.videoCodecSetPriority(videoCodecs[i].codecId, 0)
                }

                // Disable T.140 and RED text codecs
                val allCodecs = endpoint!!.codecEnum2()
                for (i in 0 until allCodecs.size) {
                    Log.i(TAG,"Disabling codec: ${allCodecs[i].codecId}");
                    val codecId = allCodecs[i].codecId
                    if (codecId.startsWith("t140") || codecId.startsWith("red")) {
                        endpoint!!.codecSetPriority(codecId, 0)
                    }
                }

                withContext(Dispatchers.Main) {
                    listener?.onConnectionStateChanged("UserAgentState", "Connected", false)
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
                        listener?.onConnectionStateChanged("UserAgentState", "Disconnected", true, e.message)
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
        accCfg.regConfig.timeoutSec = 600

        val mediaConfig = accCfg.mediaConfig
        mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
        mediaConfig.srtpSecureSignaling = 0
        mediaConfig.rtcpMuxEnabled = true
        mediaConfig.srtpOpt = SrtpOpt()

        accCfg.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
        accCfg.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT

        val regHeaders = accCfg.regConfig.headers
        regHeaders.add(SipHeader().apply {
            hName = "token"
            hValue = creds.accessToken
        })

        val authCreds = accCfg.sipConfig.authCreds
        authCreds.clear()
        authCreds.add(AuthCredInfo("Digest", "*", creds.username, 0, creds.accessToken))

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
        Log.d(TAG, "handleIncomingCall: Processing new incoming call object from PjsipAccount.")
        val session = CallSession(pjsipCall)
        currentSession = session
        val remoteIdentity = try { pjsipCall.info.remoteUri } catch (e: Exception) { "Unknown" }

        pjsipCall.onStateChanged = { call, state -> handleCallStateChange(call, state) }
        pjsipCall.onMediaStateCallback = { connectMedia(it) }

        MainScope().launch {
            listener?.onCallReceived(session, CallType.INCOMING, remoteIdentity)
        }
    }

    private fun handleCallStateChange(call: PjsipCall, state: CallState) {
        MainScope().launch {
            if (currentSession?.pjsipCall?.id == call.id) {
                Log.d(TAG, "Forwarding onCallStateChanged for call ${call.id} to app listener.")
                listener?.onCallStateChanged(currentSession!!, state)

                if (state == CallState.DISCONNECTED) {
                    Log.i(TAG, "Call session ${call.id} has ended. Clearing currentSession reference to allow GC.")
                    currentSession = null
                }
            } else {
                Log.w(TAG, "Received state change for an unknown or stale call (ID: ${call.id})")
            }
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
            val creds = sipCreds ?: run { Log.e(TAG, "startRegistration failed: No credentials available"); return@launch }

            Log.d(TAG, "Creating new PjsipCall object for this session.")
            val pjsipCall = PjsipCall(
                acc, -1,
                onStateChanged = { call, state -> handleCallStateChange(call, state) },
                onMediaStateCallback = { connectMedia(it) }
            )

            currentSession = CallSession(pjsipCall)
            val opParam = CallOpParam(true)
            val callSetting = opParam.opt
            callSetting.videoCount = 0
            callSetting.textCount = 0

            Log.d(TAG, "Building custom SIP headers for the INVITE request.")
            val headers = SipHeaderVector().apply {
                add(SipHeader().apply { hName = "token"; hValue = creds.accessToken })
                add(SipHeader().apply { hName = "X-Transaction-Id"; hValue = meta.transactionId ?: "" })
                add(SipHeader().apply { hName = "X-Job-Id"; hValue = meta.jobId ?: "" })
                add(SipHeader().apply { hName = "X-Reference-Id"; hValue = meta.candidateId ?: "" })
            }
            opParam.txOption = SipTxOption().apply { this.headers = headers }

            try {
                val destUri = "sip:$number@$domain:9080;transport=tls"
                Log.i(TAG, "Constructed destination URI: $destUri")

                withContext(Dispatchers.Main) {
                    listener?.onCallReceived(currentSession!!, CallType.OUTGOING, number)
                }

                Log.i(TAG, ">>> Invoking pjsipCall.makeCall() - Crossing into JNI/Native layer <<<")
                pjsipCall.makeCall(destUri, opParam)
            } catch (e: Exception) {
                Log.e(TAG, "❌ makeCall Exception", e)
                pjsipCall.delete()
                currentSession = null
            }
        }
    }

    fun answerCall(session: CallSession) {
        Log.i(TAG, "📞 Answering call with ID: ${session.callId}")
        sipScope.launch {
            try {
                val prm = CallOpParam()
                prm.statusCode = pjsip_status_code.PJSIP_SC_OK

                val callSetting = prm.opt
                callSetting.videoCount = 0
                callSetting.textCount = 0

                session.pjsipCall.answer(prm)
                Log.d(TAG, "pjsipCall.answer() invoked for call ID: ${session.callId}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ answerCall Exception", e)
            }
        }
    }

    fun hangupCall(session: CallSession) {
        Log.i(TAG, "📞 Hanging up call with ID: ${session.callId}")
        sipScope.launch {
            try {
                val prm = CallOpParam()
                prm.statusCode = pjsip_status_code.PJSIP_SC_DECLINE
                session.pjsipCall.hangup(prm)
                Log.d(TAG, "pjsipCall.hangup() invoked for call ID: ${session.callId}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ hangupCall Exception", e)
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
            if (account?.isValid == true && account?.info?.regIsActive == true) {
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