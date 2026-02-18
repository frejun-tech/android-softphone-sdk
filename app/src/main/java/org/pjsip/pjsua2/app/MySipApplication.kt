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
            clientId = "EfdeS7KOU861D6ntSAnAwLOfPYZER5ctmcQcyZN9",       // <-- IMPORTANT: Replace with your actual Client ID
            clientSecret = "pbkdf2_sha256\$390000\$hr3fUqhq0HkkZgTbgwgsxg\$jPrGIMyMRgDrLyTZoeWXsLeh45RgRQiwVLDaW5vFuw0="  // <-- IMPORTANT: Replace with your actual Client Secret
        )
    }
}