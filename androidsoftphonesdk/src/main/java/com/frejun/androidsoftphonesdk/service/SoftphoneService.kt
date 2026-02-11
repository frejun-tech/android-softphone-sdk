package com.frejun.androidsoftphonesdk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frejun.androidsoftphonesdk.R
import com.frejun.androidsoftphonesdk.SoftphoneSDK
import com.frejun.androidsoftphonesdk.core.SipManager
import com.frejun.androidsoftphonesdk.data.SipCredentials

class SoftphoneService : Service() {

    private lateinit var sipManager: SipManager
    private val TAG = "SoftphoneService"

    companion object {
        const val EXTRA_SIP_CREDS = "sip_creds"
        const val EXTRA_EDGE_DOMAIN = "edge_domain"
        private const val NOTIFICATION_CHANNEL_ID = "SoftphoneServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG+"Phase 3", "Service onCreate.")
//        sipManager = SipManager(applicationContext)
        sipManager = SoftphoneSDK.getSipManagerInternal()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG+"Phase 3", "Service onStartCommand.")
        val sipCreds = intent?.getParcelableExtra<SipCredentials>(EXTRA_SIP_CREDS)
        val edgeDomain = intent?.getStringExtra(EXTRA_EDGE_DOMAIN)

        Log.d(TAG+"Phase 3", "Received SIP credentials: $sipCreds")
        Log.d(TAG+"Phase 3", "Received edge domain: $edgeDomain")

        if (sipCreds != null && edgeDomain != null) {
            Log.d(TAG+"Phase 3", "Received SIP credentials and domain. Starting service in foreground.")
            val notification = createNotification()
            Log.d(TAG+"Phase 3", "Notification created.")
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG+"Phase 3", "Service started in foreground.")
            sipManager.start(sipCreds, edgeDomain)
            Log.d(TAG+"Phase 3", "SIP manager started.")
        } else {
            Log.e(TAG, "Service started without necessary extras. Stopping self.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy.")
        sipManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called, returning null.")
        return null
    }

    private fun createNotification(): Notification {
        Log.d(TAG+"Phase 3", "Creating foreground service notification.")
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FreJun Softphone Active")
            .setContentText("Connected and ready to make calls.")
            .setSmallIcon(R.drawable.ic_stat_call) // You need to add this icon
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG+"Phase 3", "Creating notification channel for Android O+.")
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Softphone Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG+"Phase 3", "Notification channel created.")
        }
    }
}