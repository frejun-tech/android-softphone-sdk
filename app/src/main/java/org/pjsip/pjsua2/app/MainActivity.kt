package org.pjsip.pjsua2.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.frejun.androidsoftphonesdk.*
import com.frejun.androidsoftphonesdk.core.CallSession
import com.frejun.androidsoftphonesdk.data.TokenPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SoftphoneListener {

    private val TAG = "UI-MainActivity"

    private lateinit var loginButton: Button
    private lateinit var authenticatedRootLayout: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var connectButton: Button
    private lateinit var destinationEditText: EditText
    private lateinit var callButton: Button
    private lateinit var incomingCallLayout: LinearLayout
    private lateinit var incomingCallTextView: TextView
    private lateinit var answerButton: Button
    private lateinit var hangupButton: Button
    private lateinit var logoutButton: Button

    private var activeCallSession: CallSession? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) Toast.makeText(this, "Mic permission required", Toast.LENGTH_SHORT).show()
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.v(TAG+"Phase 1", "onCreate() called")
        setContentView(R.layout.activity_main)
        setupUI()
        handleIntent(intent)
        refreshAuthUI()
        requestAudioPermission()
    }

    private fun refreshAuthUI() {
        val loggedIn = SoftphoneSDK.isLoggedIn()
        loginButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        authenticatedRootLayout.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "frejun") {
                Log.d(TAG+"Phase 2", "Received Deep Link: $uri")
                SoftphoneSDK.handleRedirect(uri)
                lifecycleScope.launch {
                    delay(2000)
                    refreshAuthUI()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupUI() {
        loginButton = findViewById(R.id.loginButton)
        authenticatedRootLayout = findViewById(R.id.authenticatedRootLayout)
        statusTextView = findViewById(R.id.statusTextView)
        connectButton = findViewById(R.id.connectButton)
        destinationEditText = findViewById(R.id.destinationEditText)
        callButton = findViewById(R.id.callButton)
        incomingCallLayout = findViewById(R.id.incomingCallLayout)
        incomingCallTextView = findViewById(R.id.incomingCallTextView)
        answerButton = findViewById(R.id.answerButton)
        hangupButton = findViewById(R.id.hangupButton)
        logoutButton = findViewById(R.id.logoutButton)

        loginButton.setOnClickListener { SoftphoneSDK.login() }

        connectButton.setOnClickListener {
            Log.i(TAG+"Phase 3", "Starting SDK start() flow...")
            lifecycleScope.launch {
                statusTextView.text = "Initializing SIP..."
                SoftphoneSDK.start(this@MainActivity)
            }
        }

        callButton.setOnClickListener {
            val dest = destinationEditText.text.toString()
            if (dest.isNotBlank()) SoftphoneSDK.makeCall(dest)
        }

        answerButton.setOnClickListener { activeCallSession?.answer() }
        hangupButton.setOnClickListener { activeCallSession?.hangup() }
        logoutButton.setOnClickListener {
            SoftphoneSDK.logout()
            refreshAuthUI()
        }
    }

    private fun requestAudioPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // --- SoftphoneListener Callbacks ---

    override fun onConnectionStateChanged(type: String, state: String, isError: Boolean, detail: String?) {
        Log.i(TAG, "🔔 [SDK UPDATE] $type -> $state (Error: $isError)")
        runOnUiThread {
            refreshAuthUI()
            statusTextView.text = "[$type] $state"
            if (isError) Toast.makeText(this, "SDK Error: $detail", Toast.LENGTH_LONG).show()

            val isRegistered = (type == "RegistererState" && state == "Registered")
            connectButton.isEnabled = !isRegistered
            callButton.isEnabled = isRegistered
        }
    }

    override fun onCallReceived(callSession: CallSession, type: CallType, remoteContact: String) {
        Log.i(TAG, "🔔 [CALL RECEIVED] From: $remoteContact | Type: $type")
        runOnUiThread {
            activeCallSession = callSession
            incomingCallLayout.visibility = View.VISIBLE
            incomingCallTextView.text = "Call with: $remoteContact"
            answerButton.visibility = if (type == CallType.INCOMING) View.VISIBLE else View.GONE
        }
    }

    override fun onCallStateChanged(callSession: CallSession, state: CallState) {
        Log.i(TAG, "🔔 [CALL STATE CHANGE] New State: $state")
        runOnUiThread {
            statusTextView.text = "Call Status: ${state.name}"
            if (state == CallState.DISCONNECTED) {
                activeCallSession = null
                incomingCallLayout.visibility = View.GONE
            }
        }
    }

    override fun onSessionRefreshed(payload: TokenPayload) {
        Log.d(TAG, "Token session refreshed.")
    }
}