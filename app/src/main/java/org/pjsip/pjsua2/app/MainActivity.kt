package org.pjsip.pjsua2.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.frejun.androidsoftphonesdk.*
import com.frejun.androidsoftphonesdk.core.CallSession
import com.frejun.androidsoftphonesdk.data.TokenPayload
import com.frejun.androidsoftphonesdk.data.VirtualNumber
import kotlinx.coroutines.launch

// Simple enum to manage UI states for the call
enum class CallUiState {
    IDLE, DIALING, RINGING, INCOMING, ACTIVE
}

class MainActivity : AppCompatActivity(), SoftphoneListener {

    private val TAG = "SampleApp"

    // UI Components
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var loginButton: Button
    private lateinit var authenticatedRootLayout: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var logoutButton: Button
    private lateinit var dialerCard: CardView
    private lateinit var callCard: CardView
    private lateinit var virtualNumberSelector: TextView
    private lateinit var destinationEditText: EditText
    private lateinit var callButton: Button
    private lateinit var callStatusTitle: TextView
    private lateinit var remoteContactTextView: TextView
    private lateinit var callActionsLayout: LinearLayout
    private lateinit var hangupButton: Button
    private lateinit var answerButton: Button

    // State
    private var virtualNumbers: List<VirtualNumber> = emptyList()
    private var selectedVirtualNumber: String? = null
    private var activeCallSession: CallSession? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.v(TAG, "onCreate() called")
        setContentView(R.layout.activity_main)

        setupUI()
        requestPermissions()

        lifecycleScope.launch {
            loadingIndicator.visibility = View.VISIBLE
            loginButton.visibility = View.GONE

            if (SoftphoneSDK.isLoggedIn()) {
                Log.d(TAG, "User is already logged in, starting SDK...")
                try {
                    SoftphoneSDK.start(this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start SDK on init", e)
                }
            }
            refreshUI()
            loadingIndicator.visibility = View.GONE
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            if (uri.scheme == "frejun") {
                Log.d(TAG, "Received Deep Link: $uri")
                loadingIndicator.visibility = View.VISIBLE
                SoftphoneSDK.handleRedirect(uri)

                lifecycleScope.launch {
                    kotlinx.coroutines.delay(3000) // Wait for auth to complete
                    if (SoftphoneSDK.isLoggedIn()) {
                        try {
                            SoftphoneSDK.start(this@MainActivity)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start SDK after redirect", e)
                        }
                    }
                    refreshUI()
                    loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun setupUI() {
        loadingIndicator = findViewById(R.id.loadingIndicator)
        loginButton = findViewById(R.id.loginButton)
        authenticatedRootLayout = findViewById(R.id.authenticatedRootLayout)
        statusTextView = findViewById(R.id.statusTextView)
        logoutButton = findViewById(R.id.logoutButton)
        dialerCard = findViewById(R.id.dialerCard)
        callCard = findViewById(R.id.callCard)
        virtualNumberSelector = findViewById(R.id.virtualNumberSelector)
        destinationEditText = findViewById(R.id.destinationEditText)
        callButton = findViewById(R.id.callButton)
        callStatusTitle = findViewById(R.id.callStatusTitle)
        remoteContactTextView = findViewById(R.id.remoteContactTextView)
        callActionsLayout = findViewById(R.id.callActionsLayout)
        hangupButton = findViewById(R.id.hangupButton)
        answerButton = findViewById(R.id.answerButton)

        loginButton.setOnClickListener { SoftphoneSDK.login() }
        logoutButton.setOnClickListener {
            SoftphoneSDK.logout()
            refreshUI()
        }
        callButton.setOnClickListener {
            val destination = destinationEditText.text.toString()
            if (destination.isNotBlank()) {
                SoftphoneSDK.makeCall(destination, selectedVirtualNumber)
            }
        }
        virtualNumberSelector.setOnClickListener { showVnPicker() }

        answerButton.setOnClickListener { SoftphoneSDK.answerCall(activeCallSession) }
        hangupButton.setOnClickListener { SoftphoneSDK.hangupCall(activeCallSession) }
    }

    private fun refreshUI() {
        val isLoggedIn = SoftphoneSDK.isLoggedIn()
        loginButton.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        authenticatedRootLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        if (!isLoggedIn) {
            updateCallUI(CallUiState.IDLE)
        }
    }

    private fun updateCallUI(state: CallUiState, remoteContact: String? = null) {
        runOnUiThread {
            when (state) {
                CallUiState.IDLE -> {
                    dialerCard.visibility = View.VISIBLE
                    callCard.visibility = View.GONE
                }
                CallUiState.INCOMING -> {
                    dialerCard.visibility = View.GONE
                    callCard.visibility = View.VISIBLE
                    callStatusTitle.text = "Incoming Call"
                    remoteContactTextView.text = remoteContact
                    answerButton.visibility = View.VISIBLE
                    hangupButton.text = "Reject"
                }
                CallUiState.DIALING, CallUiState.RINGING -> {
                    dialerCard.visibility = View.GONE
                    callCard.visibility = View.VISIBLE
                    callStatusTitle.text = if (state == CallUiState.DIALING) "Dialing..." else "Ringing..."
                    remoteContactTextView.text = remoteContact
                    answerButton.visibility = View.GONE
                    hangupButton.text = "Hangup"
                }
                CallUiState.ACTIVE -> {
                    dialerCard.visibility = View.GONE
                    callCard.visibility = View.VISIBLE
                    callStatusTitle.text = "In Call"
                    remoteContactTextView.text = remoteContact
                    answerButton.visibility = View.GONE
                    hangupButton.text = "Hangup"
                }
            }
        }
    }

    private fun showVnPicker() {
        if (virtualNumbers.isEmpty()) {
            Toast.makeText(this, "No virtual numbers found", Toast.LENGTH_SHORT).show()
            return
        }
        val items = virtualNumbers.map { "${it.name} (${it.countryCode}${it.number})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Caller ID")
            .setItems(items) { dialog, which ->
                val selected = virtualNumbers[which]
                selectedVirtualNumber = "${selected.countryCode}${selected.number}"
                virtualNumberSelector.text = items[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    // --- SoftphoneListener Callbacks ---

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onConnectionStateChanged(type: String, state: String, isError: Boolean, detail: String?) {
        Log.i(TAG, "🔔 [SDK UPDATE] $type -> $state (Error: $isError, Detail: $detail)")
        runOnUiThread {
            statusTextView.text = "Status: [$type] $state"
            if (isError) {
                Toast.makeText(this, "SDK Error: $detail", Toast.LENGTH_LONG).show()
            }
            val isRegistered = (type == "RegistererState" && state == "Registered")
            callButton.isEnabled = isRegistered
            if (isRegistered) {
                lifecycleScope.launch {
                    try {
                        virtualNumbers = SoftphoneSDK.getVirtualNumbers() ?: emptyList()
                        val defaultVn = virtualNumbers.find { it.isDefaultCallingNumber } ?: virtualNumbers.firstOrNull()
                        if (defaultVn != null) {
                            selectedVirtualNumber = "${defaultVn.countryCode}${defaultVn.number}"
                            virtualNumberSelector.text = "${defaultVn.name} (${selectedVirtualNumber})"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get virtual numbers", e)
                    }
                }
            }
        }
    }

    override fun onCallReceived(callSession: CallSession, type: CallType, remoteContact: String) {
        Log.i(TAG, "🔔 [CALL RECEIVED] From: $remoteContact | Type: $type")
        this.activeCallSession = callSession
        updateCallUI(if (type == CallType.INCOMING) CallUiState.INCOMING else CallUiState.DIALING, remoteContact)
    }

    override fun onCallStateChanged(callSession: CallSession, state: CallState) {
        Log.i(TAG, "🔔 [CALL STATE CHANGE] New State: $state")
        this.activeCallSession = if (state == CallState.DISCONNECTED) null else callSession

        val uiState = when(state) {
            CallState.DIALING -> CallUiState.DIALING
            CallState.RINGING -> CallUiState.RINGING
            CallState.ACTIVE -> CallUiState.ACTIVE
            CallState.DISCONNECTED -> CallUiState.IDLE
            else -> null
        }
        if (uiState != null) {
            updateCallUI(uiState, remoteContactTextView.text.toString())
        }
    }

    override fun onSessionRefreshed(payload: TokenPayload) {
        Log.d(TAG, "Token session refreshed. New access token: ${payload.accessToken}")
        runOnUiThread {
            Toast.makeText(this, "Session automatically refreshed", Toast.LENGTH_SHORT).show()
        }
    }
}