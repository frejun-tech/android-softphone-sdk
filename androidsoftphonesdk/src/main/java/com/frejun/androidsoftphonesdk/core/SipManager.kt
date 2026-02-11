// File: sip/AndroidSoftphoneSDK/src/main/java/com/frejun/androidsoftphonesdk/core/SipManager.kt

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

    fun setListener(l: SoftphoneListener) {
        this.listener = l
    }

    fun isStarted(): Boolean = (userAgent != null)

    fun start(sipCreds: SipCredentials, edgeDomain: String) {
        Log.i(TAG, "start: Initializing SIP flow")
        val l = listener ?: run {
            Log.e(TAG, "start: FAILED. No listener attached.")
            return
        }

        if (userAgent == null) {
            Log.i(TAG, "start: Creating and starting new SipUserAgent")
            userAgent = SipUserAgent(context)
            userAgent!!.start(sipCreds, edgeDomain, l)
        } else {
            Log.w(TAG, "start: SipUserAgent already initialized.")
        }
    }

    fun makeCall(destination: String, metadata: CallMetaData, sipToken: String) {
        userAgent?.makeCall(destination, metadata, sipToken)
            ?: Log.e(TAG, "makeCall: FAILED. UserAgent is not started.")
    }

    fun stop() {
        Log.i(TAG, "stop: Stopping SipManager and its UserAgent")
        userAgent?.stop()
        userAgent = null
    }
}