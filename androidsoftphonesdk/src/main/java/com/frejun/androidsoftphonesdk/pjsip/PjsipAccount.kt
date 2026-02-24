package com.frejun.androidsoftphonesdk.pjsip

import android.util.Log
import com.frejun.androidsoftphonesdk.ConnectionState
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.pjsip_status_code

internal class PjsipAccount(
    private val onRegStateCallback: (state: ConnectionState, reason: String?) -> Unit,
    private val onIncomingCallCallback: (call: PjsipCall) -> Unit
) : Account() {

    private val TAG = "PjsipAccount"

    init {
        Log.d(TAG+"Phase 3", "PjsipAccount instance created.")
    }

    override fun onRegState(prm: OnRegStateParam) {
        val isActive = prm.code == pjsip_status_code.PJSIP_SC_OK
        val state = if (isActive) ConnectionState.REGISTERED else ConnectionState.FAILED
        Log.i(TAG, "Registration state changed: Code=${prm.code}, Reason='${prm.reason}', New State=$state")

        onRegStateCallback(state, prm.reason)
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        Log.i(TAG, "onIncomingCall: New call with ID ${prm.callId} received.")
        // When an incoming call arrives, we create a PjsipCall instance for it.
        // We pass null for the listeners initially; the SipManager will attach them.
        val call = PjsipCall(this, prm.callId, null, null)

        try {
            val opPrm = CallOpParam()
            opPrm.statusCode = pjsip_status_code.PJSIP_SC_RINGING

            val callSetting = opPrm.opt
            callSetting.videoCount = 0
            callSetting.textCount = 0

            call.answer(opPrm)
            Log.i(TAG, "Sent 180 Ringing for call ${prm.callId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send 180 Ringing", e)
            // If we can't even send ringing, something is wrong, so we reject.
            try {
                val opPrm = CallOpParam()
                opPrm.statusCode = pjsip_status_code.PJSIP_SC_INTERNAL_SERVER_ERROR
                call.hangup(opPrm)
            } catch (hangupE: Exception) {
                Log.e(TAG, "Failed to hangup after ringing failure", hangupE)
            }
            call.delete()
            return
        }

        try {
            Log.d(TAG, "Incoming call details: From='${call.info.remoteUri}', To='${call.info.localUri}'")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get info for incoming call", e)
        }
        onIncomingCallCallback(call)
    }
}