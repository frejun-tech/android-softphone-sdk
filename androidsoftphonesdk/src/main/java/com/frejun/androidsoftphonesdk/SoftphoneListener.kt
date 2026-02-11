package com.frejun.androidsoftphonesdk

import com.frejun.androidsoftphonesdk.core.CallSession
import com.frejun.androidsoftphonesdk.data.TokenPayload

// Public interface for the app to receive events from the SDK.
interface SoftphoneListener {
        // type: "UserAgentState" or "RegistererState"
        // state: "Connected", "Registered", "Disconnected", etc.
        fun onConnectionStateChanged(type: String, state: String, isError: Boolean, detail: String? = null)

        fun onCallReceived(callSession: CallSession, type: CallType, remoteContact: String)
        fun onCallStateChanged(callSession: CallSession, state: CallState)
        fun onSessionRefreshed(payload: TokenPayload)
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, REGISTERING, REGISTERED, UNREGISTERING, FAILED
}

enum class CallType {
    INCOMING, OUTGOING
}

enum class CallState {
    IDLE, DIALING, RINGING, ACTIVE, HELD, DISCONNECTED
}