package com.frejun.androidsoftphonesdk.core

import android.content.Context
import android.util.Log
import com.frejun.androidsoftphonesdk.SoftphoneListener
import com.frejun.androidsoftphonesdk.data.CallMetaData
import com.frejun.androidsoftphonesdk.data.SipCredentials

internal class SipManager(private val context: Context) {
    private val TAG = "Softphone-SipManager"
    private var userAgent: SipUserAgent? = null
    private var listener: SoftphoneListener? = null

    fun setListener(l: SoftphoneListener?) {
        this.listener = l
        userAgent?.setListener(l)
    }

    fun isStarted(): Boolean = (userAgent != null)

    fun start(sipCreds: SipCredentials, edgeDomain: String) {
        Log.i(TAG, "start: Initializing SIP flow")

        if (userAgent == null) {
            Log.i(TAG, "start: Creating and starting new SipUserAgent")
            userAgent = SipUserAgent(context)
            userAgent!!.setListener(this.listener)
            userAgent!!.start(sipCreds, edgeDomain)
        } else {
            Log.w(TAG, "start: SipUserAgent already initialized.")
            userAgent!!.setListener(this.listener)
            userAgent!!.start(sipCreds, edgeDomain)
        }
    }

    suspend fun restart(sipCreds: SipCredentials, edgeDomain: String) {
        Log.i(TAG, "Restarting SIP flow for new edge domain: $edgeDomain")
        stop()
        kotlinx.coroutines.delay(500)
        start(sipCreds, edgeDomain)
    }

    fun makeCall(destination: String, metadata: CallMetaData, sipToken: String) {
        Log.d(TAG, "makeCall: Forwarding request to SipUserAgent.")
        userAgent?.makeCall(destination, metadata, sipToken)
            ?: Log.e(TAG, "makeCall: FAILED. UserAgent is not started.")
    }

    fun answerCall(session: CallSession) {
        userAgent?.answerCall(session)
            ?: Log.e(TAG, "answerCall: FAILED. UserAgent is not started.")
    }

    fun hangupCall(session: CallSession) {
        userAgent?.hangupCall(session)
            ?: Log.e(TAG, "hangupCall: FAILED. UserAgent is not started.")
    }

    fun stop() {
        Log.i(TAG, "stop: Stopping SipManager and its UserAgent")
        userAgent?.stop()
        userAgent = null
    }
}