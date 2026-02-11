// File: sip/AndroidSoftphoneSDK/src/main/java/com/frejun/androidsoftphonesdk/pjsip/PjsipLogWriter.kt
package com.frejun.androidsoftphonesdk.pjsip

import android.util.Log
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter

/**
 * A custom LogWriter to redirect PJSIP's native logs to Android's Logcat.
 * This allows for easy debugging of SIP messages and library status.
 */
internal class PjsipLogWriter : LogWriter() {

    private val TAG = "PJSIP"

    /**
     * This method is called by the PJSIP native layer for every log message.
     * @param entry The log entry containing the message and metadata.
     */
    override fun write(entry: LogEntry) {
        // We simply forward the message from the LogEntry to Android's Logcat.
        // The TAG "PJSIP" can be used to filter logs specifically for the SIP stack.
        Log.d(TAG, entry.msg)
    }
}