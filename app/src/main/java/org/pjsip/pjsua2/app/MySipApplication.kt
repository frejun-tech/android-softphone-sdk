package org.pjsip.pjsua2.app

import android.app.Application
import com.frejun.androidsoftphonesdk.SoftphoneSDK
import android.util.Log

class MySipApplication : Application() {

    companion object {
        private const val TAG = "MySipApplication Phase1"
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Application onCreate - Initializing SoftphoneSDK")

        // Initialize the SDK once when the application starts
        SoftphoneSDK.initialize(
            context = this,
            clientId = "o8GgOFtnpuMK620HHRn5LTnHB9PXvpjCWeiMW6Ci",       // <-- IMPORTANT: Replace with your actual Client ID
            clientSecret = "pbkdf2_sha256\$600000\$vfya4Qbnv5q3eaMaq0Ihj3\$REvpB37Wut2WIzt5WF5P82aWDNy0H2bXBAQehigZMMc="  // <-- IMPORTANT: Replace with your actual Client Secret
        )
    }
}