package com.frejun.androidsoftphonesdk.pjsip

import android.util.Log
import com.frejun.androidsoftphonesdk.CallState
import org.pjsip.pjsua2.*

internal class PjsipCall(
    account: Account,
    callId: Int = -1,
    var onStateChanged: ((call: PjsipCall, state: CallState) -> Unit)?,
    internal var onMediaStateCallback: ((call: PjsipCall) -> Unit)?
) : Call(account, callId) {

    private val TAG = "Softphone-PjsipCall"

    override fun onCallState(prm: OnCallStateParam) {
        val ci: CallInfo
        try {
            ci = info
        } catch (e: Exception) {
            Log.e(TAG, "onCallState: Failed to get info for call $id")
            // If we can't get info, the call is already invalid.
            // Notify with DISCONNECTED and return.
            onStateChanged?.invoke(this, CallState.DISCONNECTED)
            return
        }

        Log.i(TAG, "CALLBACK onCallState | ID: ${ci.id} | New State: ${ci.stateText} | Last Status: ${ci.lastStatusCode} '${ci.lastReason}'")

        val state = when (ci.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CALLING -> CallState.DIALING
            pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> CallState.RINGING
            pjsip_inv_state.PJSIP_INV_STATE_EARLY -> CallState.RINGING
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.DIALING
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.DISCONNECTED
            else -> null // Or a default state if necessary
        }

        state?.let {
            Log.v(TAG, "Mapping PJSIP state '${ci.stateText}' to SDK state '$it'. Invoking onStateChanged callback.")
            onStateChanged?.invoke(this, it)
        }

        // *** CRITICAL FIX: The delete() call is removed from here. ***
        // The object's lifecycle will now be managed by its owner (SipUserAgent)
        // based on the DISCONNECTED state propagated by the callback.
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        Log.i(TAG, "🎵 [MEDIA STATE] ID: $id - Media updated. Triggering connection...")
        onMediaStateCallback?.invoke(this)
    }
}