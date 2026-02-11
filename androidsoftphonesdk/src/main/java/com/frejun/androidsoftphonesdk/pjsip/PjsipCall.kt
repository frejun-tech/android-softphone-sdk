package com.frejun.androidsoftphonesdk.pjsip

import android.util.Log
import com.frejun.androidsoftphonesdk.CallState
import org.pjsip.pjsua2.*

internal class PjsipCall(
    account: Account,
    callId: Int = -1,
    private var onStateChanged: ((call: PjsipCall, state: CallState) -> Unit)?,
    internal var onMediaStateCallback: ((call: PjsipCall) -> Unit)?
) : Call(account, callId) {

    private val TAG = "Softphone-PjsipCall"

    override fun onCallState(prm: OnCallStateParam) {
        val ci: CallInfo = try { info } catch (e: Exception) {
            Log.e(TAG, "onCallState: Failed to get info for call $id")
            return
        }

        Log.i(TAG, "🔄 [CALL STATE] ID: ${ci.id} | State: ${ci.stateText} | Last Code: ${ci.lastStatusCode}")

        val state = when (ci.state) {
            pjsip_inv_state.PJSIP_INV_STATE_CALLING -> CallState.DIALING
            pjsip_inv_state.PJSIP_INV_STATE_EARLY, pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> CallState.RINGING
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.DIALING
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.DISCONNECTED
            else -> null
        }

        state?.let {
            Log.v(TAG, "Mapping PJSIP state to SDK state: $it")
            onStateChanged?.invoke(this, it)
        }

        if (ci.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            Log.w(TAG, "Call ${ci.id} disconnected. Disconnect reason: ${ci.lastReason}")
            delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        Log.i(TAG, "🎵 [MEDIA STATE] ID: $id - Media updated. Triggering connection...")
        onMediaStateCallback?.invoke(this)
    }
}