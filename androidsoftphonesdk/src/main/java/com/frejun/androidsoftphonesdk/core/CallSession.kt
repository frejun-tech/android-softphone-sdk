// File: sip/AndroidSoftphoneSDK/src/main/java/com/frejun/androidsoftphonesdk/core/CallSession.kt

package com.frejun.androidsoftphonesdk.core

import com.frejun.androidsoftphonesdk.CallState
import com.frejun.androidsoftphonesdk.pjsip.PjsipCall
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code

/**
 * A clean wrapper around a PjsipCall instance, exposed to the app.
 * The app can hold a reference to this object to interact with a call.
 */
class CallSession internal constructor(internal val pjsipCall: PjsipCall) {

    val callId: Int get() = pjsipCall.id
    val remoteUri: String get() = try { pjsipCall.info.remoteUri } catch (_: Exception) { "Unknown" }

    /**
     * Gets the current state of the call.
     */
    val state: CallState
        get() {
            return try {
                val ci: CallInfo = pjsipCall.info
                when (ci.state) {
                    pjsip_inv_state.PJSIP_INV_STATE_CALLING, pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.DIALING
                    pjsip_inv_state.PJSIP_INV_STATE_EARLY, pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> CallState.RINGING
                    pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
                    pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.DISCONNECTED
                    else -> CallState.IDLE
                }
            } catch (e: Exception) {
                // If getInfo() fails, the call is likely gone.
                CallState.DISCONNECTED
            }
        }


    fun answer() {
        val prm = CallOpParam()
        prm.statusCode = pjsip_status_code.PJSIP_SC_OK
        try {
            pjsipCall.answer(prm)
        } catch (e: Exception) {
            println("CallSession Error: Failed to answer call. ${e.message}")
        }
    }

    fun hangup() {
        val prm = CallOpParam()
        prm.statusCode = pjsip_status_code.PJSIP_SC_DECLINE
        try {
            pjsipCall.hangup(prm)
        } catch (e: Exception) {
            println("CallSession Error: Failed to hangup call. ${e.message}")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CallSession
        return pjsipCall.id == other.pjsipCall.id
    }

    override fun hashCode(): Int {
        return pjsipCall.id
    }
}