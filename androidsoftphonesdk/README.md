# FreJun Android Softphone SDK

A drop-in Android library that adds VoIP calling to your app using the FreJun platform. It handles OAuth login, SIP registration, inbound and outbound calls, and automatic token refresh — so you only need to build the UI.

---

## Requirements

| Item | Minimum |
|------|---------|
| Android API level | 29 (Android 10) |
| Kotlin | 1.8+ |
| Java compatibility | 11 |
| FreJun account | Client ID + Client Secret from FreJun dashboard |

> **API level notes:**
> - API 29+ is required because `foregroundServiceType="phoneCall"` on the SDK's foreground service is enforced from Android 10.
> - `android.permission.FOREGROUND_SERVICE_PHONE_CALL` (declared in the manifest below) is only enforced on API 34+. It is safe to declare on older devices.

---

## Installation

The SDK is distributed as two local modules that you copy into your project root:

| Module | Purpose |
|--------|---------|
| `androidsoftphonesdk` | The SDK itself (Kotlin classes, API surface) |
| `pjsua2` | The PJSIP native engine — pre-built `.so` files and Java bindings required by the SDK at runtime |

Both modules must be present; the SDK will not link without `pjsua2`.

**1. Copy both modules into your project root.**

**2. Add them to your root `settings.gradle`:**

```groovy
include ':androidsoftphonesdk'
include ':pjsua2'
```

**3. Add the dependency in your app's `build.gradle`:**

```groovy
dependencies {
    implementation project(':androidsoftphonesdk')
}
```

**4. Make sure your app's `build.gradle` targets at least Java 11:**

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = '11'
    }
}
```

---

## ProGuard / R8

If your app uses ProGuard or R8 (the default for release builds), add the following rules to your `proguard-rules.pro`:

```
# Keep PJSIP JNI bindings
-keep class org.pjsip.** { *; }
-dontwarn org.pjsip.**

# Keep SDK public surface
-keep class com.frejun.androidsoftphonesdk.** { *; }
```

Without these rules, PJSIP's JNI classes will be stripped and calls will fail silently in release builds.

---

## Manifest Setup

Add the following to your app's `AndroidManifest.xml`:

### Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
```

### Foreground Service

Register the SDK's foreground service inside `<application>`:

```xml
<service
    android:name="com.frejun.androidsoftphonesdk.service.SoftphoneService"
    android:exported="false"
    android:foregroundServiceType="phoneCall" />
```

### OAuth Deep Link (for browser login)

Add an intent filter to the activity that will receive the OAuth redirect. The SDK uses the `frejun://` scheme:

```xml
<activity
    android:name=".YourActivity"
    android:launchMode="singleTop">

    <!-- Existing launcher intent filter -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- OAuth redirect -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="frejun" />
    </intent-filter>
</activity>
```

> `launchMode="singleTop"` is required so `onNewIntent()` is called when the browser redirects back to your already-running activity instead of creating a new instance.

---

## Quick Start

### Step 1 — Initialize the SDK

Call `initialize()` once when your app starts. The best place is your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        SoftphoneSDK.initialize(
            context = this,
            clientId = "YOUR_CLIENT_ID",
            clientSecret = "YOUR_CLIENT_SECRET"
        )
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application android:name=".MyApplication" ... />
```

---

### Step 2 — Implement `SoftphoneListener`

Your activity (or any class) receives all SDK events by implementing this interface.

> **Threading:** All callbacks are delivered on a **background thread**. Wrap any UI updates in `runOnUiThread {}` or `lifecycleScope.launch(Dispatchers.Main) {}`.

```kotlin
class MainActivity : AppCompatActivity(), SoftphoneListener {

    override fun onConnectionStateChanged(
        type: String,
        state: String,
        isError: Boolean,
        detail: String?
    ) {
        // type:    "UserAgentState" or "RegistererState"
        // state:   see state table below
        // isError: true when something went wrong

        if (type == "RegistererState" && state == "Registered") {
            // SIP registration succeeded — safe to make calls now
        }

        if (isError) {
            // Show error to user using detail message
        }
    }

    override fun onCallReceived(
        callSession: CallSession,
        type: CallType,        // CallType.INCOMING or CallType.OUTGOING
        remoteContact: String  // Phone number or SIP URI
    ) {
        // Store the session so you can answer/hangup later
        activeCallSession = callSession

        if (type == CallType.INCOMING) {
            // Show incoming call UI with answer/reject buttons
        }
    }

    override fun onCallStateChanged(callSession: CallSession, state: CallState) {
        when (state) {
            CallState.DIALING      -> { /* Outgoing — waiting for answer */ }
            CallState.RINGING      -> { /* Remote side is ringing */ }
            CallState.ACTIVE       -> { /* Call connected — audio is live */ }
            CallState.DISCONNECTED -> { /* Call ended — clear your UI */ }
            else -> {}
        }
    }

    override fun onSessionRefreshed(payload: TokenPayload) {
        // OAuth tokens were silently refreshed — nothing you need to do
        // payload contains the new accessToken and refreshToken if you need them
    }
}
```

#### `onConnectionStateChanged` — state values

| `type` | `state` | Meaning |
|--------|---------|---------|
| `"UserAgentState"` | `"Started"` | PJSIP engine is running |
| `"UserAgentState"` | `"Stopped"` | PJSIP engine shut down |
| `"RegistererState"` | `"Registering"` | SIP REGISTER sent, waiting for response |
| `"RegistererState"` | `"Registered"` | Registered — ready to make and receive calls |
| `"RegistererState"` | `"Unregistered"` | Deliberately unregistered (e.g. after logout) |
| `"RegistererState"` | `"Failed"` | Registration failed; `isError = true`, `detail` has the reason |
| `"RegistererState"` | `"Disconnected"` | Connection lost |

---

### Step 3 — Register and Unregister the Listener

`start()` (Step 5) requires a listener and holds a reference to it. Use `setListener()` to swap the active listener as your activity enters and leaves the foreground:

```kotlin
override fun onResume() {
    super.onResume()
    SoftphoneSDK.setListener(this)
}

override fun onPause() {
    super.onPause()
    SoftphoneSDK.setListener(null)
}
```

> **Background calls:** When the listener is `null`, `onCallReceived` and `onCallStateChanged` are not delivered to your UI. The SDK's foreground service continues to keep the SIP connection alive in the background, but your activity will not be notified of an incoming call until it resumes and calls `setListener(this)` again. If you need to wake your activity for incoming calls (e.g. show a full-screen incoming call screen), implement that in the foreground service or use a notification with a `PendingIntent`.

---

### Step 4 — Login

**Option A — Browser-based OAuth (recommended)**

This opens a Chrome Custom Tab where the user logs in on the FreJun website:

```kotlin
loginButton.setOnClickListener {
    SoftphoneSDK.login()
}
```

When the user completes login, the browser redirects back to your app via the deep link. Handle it in `onNewIntent()`:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { uri ->
        if (uri.scheme == "frejun") {
            SoftphoneSDK.handleRedirect(uri)

            // Token exchange happens asynchronously after handleRedirect returns.
            // Poll isLoggedIn() for up to ~5 s before starting SIP.
            // On slow networks you may need to increase this delay.
            lifecycleScope.launch {
                delay(3000)
                if (SoftphoneSDK.isLoggedIn()) {
                    SoftphoneSDK.start(this@MainActivity)
                } else {
                    // Auth did not complete in time — show an error or retry
                }
            }
        }
    }
}
```

**Option B — Direct token login (for server-authenticated flows)**

If your backend already has valid FreJun OAuth tokens for the user, you can pass them directly (this is a `suspend` function, call it from a coroutine):

```kotlin
lifecycleScope.launch {
    SoftphoneSDK.login(
        accessToken = "access_token_from_your_server",
        refreshToken = "refresh_token_from_your_server",
        email = "user@example.com"
    )
    SoftphoneSDK.start(this@MainActivity)
}
```

---

### Step 5 — Start the SDK

`start()` fetches SIP credentials from the FreJun backend, then starts a foreground service that registers to the SIP server. Call this after login is confirmed. It is a `suspend` function:

```kotlin
lifecycleScope.launch {
    SoftphoneSDK.start(this@MainActivity)
}
```

If the user is already logged in from a previous session (app restarted), check and start immediately in `onCreate()`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (SoftphoneSDK.isLoggedIn()) {
        lifecycleScope.launch {
            SoftphoneSDK.start(this@MainActivity)
        }
    }
}
```

Once SIP registration succeeds, `onConnectionStateChanged("RegistererState", "Registered", ...)` is called. Only after that callback should you enable call functionality in your UI.

---

### Step 6 — Make a Call

```kotlin
// Minimum — just a destination number
SoftphoneSDK.makeCall(destination = "+911234567890")

// With a specific caller ID (virtual number)
SoftphoneSDK.makeCall(
    destination = "+911234567890",
    fromVirtualNumber = "+911234567890"   // must be one of the user's virtual numbers
)

// With optional metadata (for CRM / ATS integrations)
SoftphoneSDK.makeCall(
    destination = "+911234567890",
    fromVirtualNumber = "+911234567890",
    transactionId = "txn_abc123",
    jobId = "job_456",
    candidateId = "cand_789"
)
```

After calling `makeCall()`, `onCallReceived()` fires immediately with `CallType.OUTGOING`, followed by `onCallStateChanged()` as the call progresses.

---

### Step 7 — Answer or Reject an Incoming Call

When `onCallReceived()` fires with `CallType.INCOMING`, store the session and show your UI:

```kotlin
// Answer
answerButton.setOnClickListener {
    activeCallSession?.let { SoftphoneSDK.answerCall(it) }
}

// Reject / Hang up
hangupButton.setOnClickListener {
    activeCallSession?.let { SoftphoneSDK.hangupCall(it) }
}
```

---

### Step 8 — Fetch Virtual Numbers (Caller IDs)

Call this after SIP registration to populate a caller ID picker:

```kotlin
lifecycleScope.launch {
    val numbers: List<VirtualNumber>? = SoftphoneSDK.getVirtualNumbers()
    numbers?.forEach { vn ->
        // vn.id                    — unique identifier (use this as the item key in your picker)
        // vn.name                  — display name (e.g. "Support Line")
        // vn.countryCode           — e.g. "+91"
        // vn.number                — e.g. "9876543210"
        // vn.isDefaultCallingNumber — true for the user's primary caller ID
    }
}
```

---

### Step 9 — Logout

```kotlin
logoutButton.setOnClickListener {
    SoftphoneSDK.logout()
    // SIP unregisters, foreground service stops, tokens cleared
    // Show your login screen here
}
```

---

## Complete Integration Example

```kotlin
class MainActivity : AppCompatActivity(), SoftphoneListener {

    private var activeCallSession: CallSession? = null

    // Modern permission launcher (replaces deprecated requestPermissions)
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request microphone permission
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)

        // Auto-start if already logged in
        if (SoftphoneSDK.isLoggedIn()) {
            lifecycleScope.launch {
                SoftphoneSDK.start(this@MainActivity)
            }
        }

        findViewById<Button>(R.id.loginButton).setOnClickListener {
            SoftphoneSDK.login()
        }

        findViewById<Button>(R.id.callButton).setOnClickListener {
            val number = findViewById<EditText>(R.id.numberInput).text.toString()
            SoftphoneSDK.makeCall(destination = number)
        }

        findViewById<Button>(R.id.answerButton).setOnClickListener {
            activeCallSession?.let { SoftphoneSDK.answerCall(it) }
        }

        findViewById<Button>(R.id.hangupButton).setOnClickListener {
            activeCallSession?.let { SoftphoneSDK.hangupCall(it) }
        }

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            SoftphoneSDK.logout()
        }
    }

    override fun onResume() {
        super.onResume()
        SoftphoneSDK.setListener(this)
    }

    override fun onPause() {
        super.onPause()
        SoftphoneSDK.setListener(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "frejun") {
                SoftphoneSDK.handleRedirect(uri)
                lifecycleScope.launch {
                    delay(3000)
                    if (SoftphoneSDK.isLoggedIn()) {
                        SoftphoneSDK.start(this@MainActivity)
                    }
                }
            }
        }
    }

    // --- SoftphoneListener ---
    // All callbacks arrive on a background thread — use runOnUiThread for UI updates.

    override fun onConnectionStateChanged(type: String, state: String, isError: Boolean, detail: String?) {
        runOnUiThread {
            if (isError) Toast.makeText(this, "Error: $detail", Toast.LENGTH_LONG).show()

            if (type == "RegistererState" && state == "Registered") {
                // Enable call button, load virtual numbers
                lifecycleScope.launch {
                    val numbers = SoftphoneSDK.getVirtualNumbers()
                    // populate your VN picker with numbers
                }
            }
        }
    }

    override fun onCallReceived(callSession: CallSession, type: CallType, remoteContact: String) {
        activeCallSession = callSession
        runOnUiThread {
            // Show call UI — if INCOMING, show answer button
        }
    }

    override fun onCallStateChanged(callSession: CallSession, state: CallState) {
        if (state == CallState.DISCONNECTED) activeCallSession = null
        runOnUiThread {
            // Update call UI based on state
        }
    }

    override fun onSessionRefreshed(payload: TokenPayload) {
        // Tokens refreshed silently — no action required
    }
}
```

---

## API Reference

### `SoftphoneSDK` (singleton object)

| Method | Description |
|--------|-------------|
| `initialize(context, clientId, clientSecret)` | **Call once** in your `Application.onCreate()`. Must be called before anything else. |
| `login()` | Opens browser for OAuth login. |
| `login(accessToken, refreshToken, email)` *(suspend)* | Direct token login without browser. |
| `handleRedirect(uri)` | Call from `onNewIntent()` with the OAuth redirect URI. |
| `isLoggedIn(): Boolean` | Returns `true` if valid tokens are stored. |
| `start(listener)` *(suspend)* | Fetches SIP credentials, starts registration, and sets the initial listener. Call after login. Requires a class implementing `SoftphoneListener`. |
| `setListener(listener?)` | Swap or clear the active event listener. Pass `null` to stop receiving callbacks (the SIP connection remains active). |
| `makeCall(destination, fromVirtualNumber?, transactionId?, jobId?, candidateId?)` | Initiates an outgoing call. |
| `answerCall(session?)` | Answers an incoming call. |
| `hangupCall(session?)` | Ends or rejects a call. |
| `getVirtualNumbers()` *(suspend)* | Returns the user's list of caller IDs. |
| `getTokens(): TokenPayload?` | Returns the currently stored tokens. |
| `logout()` | Clears tokens, stops SIP, stops service. |

---

### `SoftphoneListener` Interface

> All callbacks are delivered on a **background thread**. Always dispatch UI updates to the main thread.

| Callback | When it fires |
|----------|--------------|
| `onConnectionStateChanged(type, state, isError, detail?)` | SIP connection or registration state changes. See the state table in Step 2 for all possible `type`/`state` combinations. |
| `onCallReceived(callSession, type, remoteContact)` | Fires when a call starts: `CallType.OUTGOING` immediately after `makeCall()`, or `CallType.INCOMING` when you receive a call. |
| `onCallStateChanged(callSession, state)` | Fires as the call progresses through states. |
| `onSessionRefreshed(payload)` | OAuth tokens were silently refreshed in the background. |

---

### Enums

```kotlin
enum class CallType  { INCOMING, OUTGOING }

enum class CallState { IDLE, DIALING, RINGING, ACTIVE, HELD, DISCONNECTED }
```

---

### `VirtualNumber` Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | Int | Unique identifier — use this as the key in your caller ID picker |
| `name` | String | Display name (e.g. "Support Line") |
| `number` | String | The phone number digits |
| `countryCode` | String | E.164 country code (e.g. `"+91"`) |
| `location` | String | Geographic label |
| `type` | String | Number type |
| `isDefaultCallingNumber` | Boolean | `true` if this is the user's primary outbound caller ID |
| `isDefaultSmsNumber` | Boolean | `true` if this is the user's primary SMS number |

---

### `CallSession` Properties

| Property | Type | Description |
|----------|------|-------------|
| `callId` | Int | Unique ID for this call |
| `remoteUri` | String | The remote party's SIP URI or phone number |
| `state` | CallState | Current state of the call |

---

## Runtime Permissions

`RECORD_AUDIO` must be requested at runtime before calls will work. Use the modern `ActivityResultContracts` API:

```kotlin
private val requestMicPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // Inform the user that audio will not work without this permission
        }
    }

// In onCreate():
requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
```

The SDK will not crash if the permission is missing, but audio will not function.

---

## Behaviour Notes

**Foreground service:** The SDK runs a persistent foreground service (`"FreJun Softphone Active"`) while registered. This is required by Android to keep the SIP connection alive and to handle incoming calls when the app is in the background. Users will see this notification.

**Automatic token refresh:** If an API call returns a 401 (token expired), the SDK automatically refreshes the OAuth token and retries the call. Your app is notified via `onSessionRefreshed()` but does not need to take any action.

**Caller ID changes:** If you pass a `fromVirtualNumber` to `makeCall()` that differs from the user's current primary number, the SDK automatically updates it on the FreJun backend before dialing. If the new number is on a different edge domain, the SDK restarts the SIP connection transparently.

**Registration retry:** If SIP registration fails, the SDK retries up to 3 times with 5-second delays before reporting failure via `onConnectionStateChanged`.

**Single listener:** The SDK holds a reference to one listener at a time. Always clear it in `onPause()` to avoid memory leaks. While the listener is `null`, the SIP connection remains active but no UI callbacks are delivered.

---

## Troubleshooting

| Symptom | Likely cause |
|---------|-------------|
| `onConnectionStateChanged` never fires `"Registered"` | Check that `RECORD_AUDIO` permission is granted and the device has internet access. Check Logcat tag `PJSIP` for SIP-level errors. |
| Login browser does not redirect back to app | Verify the `frejun://` intent filter is in your manifest and `launchMode="singleTop"` is set on the receiving activity. |
| `SdkNotInitializedException` thrown | `SoftphoneSDK.initialize()` was not called before using the SDK. Move it to your `Application.onCreate()`. |
| `IllegalStateException: SDK not started` on `makeCall()` | `start()` was not called or SIP registration has not completed yet. Wait for `"Registered"` before enabling the call button. |
| No audio during calls | Microphone permission not granted, or no audio focus. Request `RECORD_AUDIO` before starting calls. |
| Calls drop when app goes to background | Ensure the `SoftphoneService` is declared correctly in your manifest with `foregroundServiceType="phoneCall"`. |
| Release build crashes on first call | Missing ProGuard rules — PJSIP JNI classes were stripped. Add the rules from the ProGuard section above. |
| `isLoggedIn()` returns `false` after OAuth redirect | Token exchange may have taken longer than the 3-second delay in `onNewIntent`. Increase the `delay()` value or show a loading indicator and retry. |

For SIP-level debugging, filter Logcat by tag `PJSIP`.
For API/network debugging, filter Logcat by tag `OkHttp`.
