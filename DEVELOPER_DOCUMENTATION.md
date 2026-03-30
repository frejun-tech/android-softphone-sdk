# FreJun Android Softphone SDK — Developer Documentation

> Written for new developers joining the project. Assumes familiarity with Android and Kotlin basics.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Module Structure](#2-module-structure)
3. [App Module](#3-app-module)
4. [SDK Module (`androidsoftphonesdk`)](#4-sdk-module-androidsoftphonesdk)
   - [Public API](#41-public-api--softphonesdk)
   - [Authentication](#42-authentication--authmanager)
   - [SIP Layer](#43-sip-layer)
   - [Foreground Service](#44-foreground-service--softphoneservice)
   - [Data Models](#45-data-models)
   - [Storage](#46-storage--tokenstorage)
   - [Networking](#47-networking--apiclient--frejunapiservice)
5. [Key Flows — End to End](#5-key-flows--end-to-end)
   - [App Startup](#51-app-startup)
   - [Login (OAuth)](#52-login-oauth)
   - [SIP Registration](#53-sip-registration)
   - [Making a Call](#54-making-a-call)
   - [Receiving a Call](#55-receiving-a-call)
   - [Token Refresh](#56-token-refresh)
   - [Logout](#57-logout)
6. [SoftphoneListener Callbacks](#6-softphonelistener-callbacks)
7. [Threading Model](#7-threading-model)
8. [Error Handling](#8-error-handling)
9. [Permissions & Manifest](#9-permissions--manifest)
10. [Build Configuration](#10-build-configuration)
11. [Architecture Diagram](#11-architecture-diagram)

---

## 1. Project Overview

This project is a **VoIP softphone** built for the FreJun platform. It lets users make and receive phone calls from a mobile app using SIP (Session Initiation Protocol) over the internet.

The project has two main pieces:

- **`androidsoftphonesdk`** — a reusable Android library that handles everything: login, SIP registration, calls, and token management. Other apps can import this SDK and use it.
- **`app`** — a minimal demo application that imports and uses the SDK. It is the reference implementation showing how to integrate the SDK.

The underlying SIP engine is **PJSIP** (via the `pjsua2` module). You do not need to understand PJSIP internals to work on this project. Think of it as a black-box engine that the SDK wraps.

---

## 2. Module Structure

```
android/
├── app/                        # Demo application
│   └── src/main/java/org/pjsip/pjsua2/app/
│       ├── MySipApplication.kt     # Application class - initializes SDK
│       └── MainActivity.kt         # Single activity - all UI
│
├── androidsoftphonesdk/        # The SDK library (this is what matters)
│   └── src/main/java/com/frejun/androidsoftphonesdk/
│       ├── SoftphoneSDK.kt         # Public entry point (singleton)
│       ├── SoftphoneListener.kt    # Callback interface for the app
│       ├── core/
│       │   ├── SipManager.kt           # Manages SipUserAgent lifecycle
│       │   ├── SipUserAgent.kt         # Core SIP logic (PJSIP wrapper)
│       │   ├── CallSession.kt          # Public call wrapper
│       │   └── SoftphoneService.kt     # Foreground service
│       ├── auth/
│       │   ├── AuthManager.kt          # OAuth + SIP credentials
│       │   ├── TokenStorage.kt         # Encrypted token persistence
│       │   ├── ApiClient.kt            # Retrofit + interceptors
│       │   └── FrejunApiService.kt     # API endpoint definitions
│       ├── pjsip/
│       │   ├── PjsipAccount.kt         # PJSIP account callbacks
│       │   ├── PjsipCall.kt            # PJSIP call callbacks
│       │   └── PjsipLogWriter.kt       # Routes PJSIP logs to Logcat
│       ├── models/                     # Data classes
│       └── exceptions/                 # Custom exceptions
│
└── pjsua2/                     # PJSIP native bindings (third-party, do not modify)
```

---

## 3. App Module

The app module is intentionally thin — it only demonstrates how to use the SDK.

### `MySipApplication.kt`

The Android `Application` class. It runs before any activity. Its only job is to initialize the SDK singleton:

```kotlin
SoftphoneSDK.initialize(context, clientId, clientSecret)
```

Credentials (`clientId`, `clientSecret`) are hardcoded here for the demo. In a real integration, these would come from a config file or build variant.

---

### `MainActivity.kt`

The only activity in the app. It implements `SoftphoneListener` so the SDK can push events back to the UI.

**UI states:**

| State | What is visible |
|-------|----------------|
| Not logged in | Login button |
| Logged in, not registered | Status text ("Connecting...") |
| Registered, idle | Dialer card (number input + call button + VN picker) |
| Call in progress | Call card (remote contact + answer/hangup buttons) |

**Lifecycle hooks:**

- `onCreate()` — sets up click listeners, checks if already logged in (if so, starts the SDK immediately)
- `onResume()` — registers `this` as the SDK listener (`SoftphoneSDK.setListener(this)`)
- `onPause()` — removes the listener to avoid memory leaks
- `onNewIntent()` — handles the OAuth redirect deep link (`frejun://...?code=...&email=...`) after the user logs in via browser

**Key click handlers:**

| Button | Action |
|--------|--------|
| Login | `SoftphoneSDK.login()` → opens browser |
| Call | `SoftphoneSDK.makeCall(destination, selectedVN)` |
| Answer | `SoftphoneSDK.answerCall(activeCallSession)` |
| Hangup | `SoftphoneSDK.hangupCall(activeCallSession)` |
| VN Picker | Shows `AlertDialog` with `virtualNumbers` list |

---

## 4. SDK Module (`androidsoftphonesdk`)

### 4.1 Public API — `SoftphoneSDK`

`SoftphoneSDK` is a Kotlin `object` (singleton). It is the **only** class the host app needs to interact with directly.

**Initialization (call once, in Application class):**

```kotlin
SoftphoneSDK.initialize(context, clientId, clientSecret)
```

**Authentication:**

```kotlin
SoftphoneSDK.login()                                      // Opens browser for OAuth
SoftphoneSDK.login(accessToken, refreshToken, email)      // Direct token login (suspend)
SoftphoneSDK.handleRedirect(uri)                          // Called after OAuth redirect
SoftphoneSDK.isLoggedIn(): Boolean
SoftphoneSDK.logout()
```

**Start SIP engine (call after login):**

```kotlin
SoftphoneSDK.start(listener: SoftphoneListener)
```

This starts the `SoftphoneService` foreground service, which in turn starts PJSIP and registers to the SIP server.

**Calls:**

```kotlin
SoftphoneSDK.makeCall(destination, fromVirtualNumber?, transactionId?, jobId?, candidateId?)
SoftphoneSDK.answerCall(session: CallSession)
SoftphoneSDK.hangupCall(session: CallSession)
```

**Utility:**

```kotlin
SoftphoneSDK.getVirtualNumbers(): List<VirtualNumber>   // Caller IDs for the user
SoftphoneSDK.getTokens(): TokenPayload?
SoftphoneSDK.setListener(listener: SoftphoneListener)
```

**Internal retry logic:**

All API operations run inside `executeWithRetry()`. If an API call returns 401, the SDK automatically refreshes the access token and retries. If refresh also fails, it calls `listener.onConnectionStateChanged()` with an error and logs the user out.

---

### 4.2 Authentication — `AuthManager`

`AuthManager` owns everything related to identity and tokens. It is created by `SoftphoneSDK` and never exposed publicly.

**Responsibilities:**

1. **OAuth Authorization Code flow** — builds the browser authorization URL and exchanges the code for tokens.
2. **Token storage** — saves/loads tokens via `TokenStorage`.
3. **Permission check** — after login, validates the user has the "integrations (iframe and sdk)" role. If not, it logs the user out immediately.
4. **SIP credentials** — fetches the SIP username and SIP access token from the backend (separate from the OAuth token).
5. **User profile** — fetches edge domain and virtual numbers; cached in memory.
6. **Token refresh** — called automatically by `TokenAuthenticator` on 401 responses.
7. **Primary virtual number update** — PATCHes the backend when the user changes their caller ID.

**OAuth flow step by step:**

```
1. getAuthorizationUrl()
   → https://product.frejun.com/oauth/authorize/?client_id=...

2. exchangeCodeForToken(code, email)
   → POST https://api.frejun.com/api/v1/oauth/token/
   → Basic auth: Base64(clientId:clientSecret)
   → Saves tokens to TokenStorage

3. validateUserPermissions(email)
   → GET https://api.frejun.com/api/v1/auth/retrieve-user-roles/?email=...
   → Checks for "integrations (iframe and sdk)" permission

4. getSipCredentials(email)
   → GET https://api.frejun.com/api/v1/calls/register-softphone/?email=...
   → Returns { username, access_token } for SIP Digest auth
```

---

### 4.3 SIP Layer

The SIP layer has three classes in a chain:

```
SipManager → SipUserAgent → PjsipAccount / PjsipCall
```

#### `SipManager.kt`

A thin lifecycle wrapper around `SipUserAgent`. It holds the single instance and delegates all calls to it.

```kotlin
sipManager.start()       // Creates and starts SipUserAgent
sipManager.restart()     // Used when edge domain changes (re-registration)
sipManager.makeCall(...)
sipManager.answerCall(...)
sipManager.hangupCall(...)
sipManager.stop()
```

---

#### `SipUserAgent.kt`

The core of the SDK. This is where PJSIP is configured and all SIP logic lives.

**Initialization (runs on `SipWorkerThread`):**

1. Creates a PJSIP `Endpoint`
2. Configures it:
   - TLS transport on port **9080**
   - STUN server: `stun.l.google.com:19302`
   - Echo cancellation: WebRTC + noise suppression (100ms tail)
   - ICE enabled (1 host candidate, RTCP mux — no separate RTCP component)
   - SRTP: optional
   - All video codecs: **disabled**
3. Calls `startRegistration()`

**Registration:**

- Creates a `PjsipAccount` with:
  - SIP URI: `sip:username@edgeDomain:9080`
  - Digest auth: username = SIP username, password = SIP access token
  - Custom SIP header `token: <sip_access_token>` on REGISTER
  - Custom SIP header `Authorization: Bearer <sip_access_token>` on REGISTER
  - Registrar timeout: 600 seconds
- On success → `listener.onConnectionStateChanged(REGISTERED)`
- On failure → retries up to **3 times** with **5-second delays**, then notifies error

**Outgoing call (`makeCall`):**

```
SipUserAgent.makeCall(destination, sipCredentials, callMetaData)
  → Creates PjsipCall object
  → Builds SIP headers:
       token:            <sip_access_token>
       X-Transaction-Id: <transactionId>
       X-Job-Id:         <jobId>
       X-Reference-Id:   <candidateId>
  → Calls pjsipCall.makeCall("sip:destination@domain:9080;transport=tls")
  → Notifies listener: onCallReceived(session, OUTGOING, destination)
```

**Incoming call (`handleIncomingCall`):**

```
PjsipAccount.onIncomingCall()
  → Creates PjsipCall, sends 180 Ringing
  → SipUserAgent.handleIncomingCall()
  → Wraps in CallSession
  → Notifies listener: onCallReceived(session, INCOMING, remoteUri)
```

**Media (audio):**

When a call is confirmed, `connectMedia()` is called:
- Links the device microphone capture → call's audio
- Links the call's audio → device speaker/earpiece
This is what makes the two-way audio work.

---

#### `PjsipAccount.kt`

Extends PJSIP's `Account`. Overrides:
- `onRegState()` — called by PJSIP when registration state changes. Passes the state to `SipUserAgent.handleRegistrationState()`.
- `onIncomingCall()` — called by PJSIP when an INVITE arrives. Creates a `PjsipCall` and sends 180 Ringing, then notifies `SipUserAgent`.

#### `PjsipCall.kt`

Extends PJSIP's `Call`. Overrides:
- `onCallState()` — maps PJSIP call states to the SDK's `CallState` enum and fires the registered callback.
- `onCallMediaState()` — tells `SipUserAgent` to set up the audio streams.

> **Important:** PJSIP call objects are not auto-deleted. `SipUserAgent` manages their lifecycle manually — this prevents crashes from premature garbage collection.

#### `PjsipLogWriter.kt`

Redirects all PJSIP native debug output to Android Logcat under the tag `PJSIP`. Useful for debugging SIP signaling issues (REGISTER, INVITE, etc.).

---

### 4.4 Foreground Service — `SoftphoneService`

Android requires a foreground service for long-running audio/telephony processes so the OS doesn't kill the process during a call.

**Lifecycle:**

- `onCreate()` — gets the `SipManager` reference from the SDK singleton.
- `onStartCommand()` — extracts SIP credentials and edge domain from the `Intent` extras, creates the notification, starts foreground, then calls `sipManager.start()`.
- `onDestroy()` — calls `sipManager.stop()`.

**Notification:**
- Channel: `SoftphoneServiceChannel`
- Title: "FreJun Softphone Active"
- Text: "Connected and ready to make calls."

The service is declared with `foregroundServiceType="phoneCall"` in the manifest, which is required by Android for call-type services.

---

### 4.5 Data Models

| Class | Fields | Used For |
|-------|--------|----------|
| `TokenPayload` | access_token, refresh_token, email | Auth token storage and public API |
| `SipCredentials` | username, access_token | Passed via Intent to SoftphoneService; used for PJSIP Digest auth |
| `UserProfile` | edgeDomain, virtualNumbers | Cached user config |
| `VirtualNumber` | id, name, countryCode, number, location, type, isDefaultCallingNumber, isDefaultSmsNumber | Caller ID selection in UI |
| `CallMetaData` | transactionId, jobId, candidateId | Optional metadata attached to outgoing calls via SIP headers |
| `CallSession` | callId, remoteUri, state | Public call handle given to the app; wraps internal `PjsipCall` |

**Enums:**

```kotlin
enum class ConnectionState { DISCONNECTED, CONNECTING, REGISTERING, REGISTERED, UNREGISTERING, FAILED }
enum class CallType       { INCOMING, OUTGOING }
enum class CallState      { IDLE, DIALING, RINGING, ACTIVE, HELD, DISCONNECTED }
```

---

### 4.6 Storage — `TokenStorage`

Uses **AndroidX `EncryptedSharedPreferences`** backed by:
- Key encryption: AES-256-SIV
- Value encryption: AES-256-GCM
- File name: `frejun_secure_sdk_prefs`

Tokens are serialized to JSON (Gson) before encryption. This means tokens survive app restarts without requiring the user to log in again.

---

### 4.7 Networking — `ApiClient` & `FrejunApiService`

**Base URL:** `https://api.frejun.com/api/v1/`

**Retrofit setup includes two OkHttp interceptors:**

1. **`AuthInterceptor`** — adds `Authorization: Bearer {accessToken}` to every outgoing request.
2. **`TokenAuthenticator`** — responds to 401 responses:
   - Synchronized to prevent multiple simultaneous refresh attempts.
   - Calls `refreshTokenAction()` lambda (provided by `AuthManager`).
   - Retries the failed request with the new token.
   - Notifies the app via `onSessionRefreshed()`.

**API Endpoints:**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `oauth/token/` | Exchange auth code for tokens |
| POST | `oauth/token/refresh/` | Refresh expired access token |
| GET | `calls/register-softphone/` | Get SIP credentials |
| GET | `integrations/profile/` | Get edge domain + virtual numbers |
| GET | `auth/retrieve-user-roles/` | Check user permissions |
| PATCH | `auth/update-userprofile/` | Update primary virtual number |

---

## 5. Key Flows — End to End

### 5.1 App Startup

```
MySipApplication.onCreate()
  └── SoftphoneSDK.initialize(context, clientId, clientSecret)
        └── Creates AuthManager + SipManager

MainActivity.onCreate()
  └── If SoftphoneSDK.isLoggedIn() == true
        └── SoftphoneSDK.start(this)
              └── Starts SoftphoneService via Intent
                    └── SipManager.start()
                          └── SipUserAgent.start() [on SipWorkerThread]
```

---

### 5.2 Login (OAuth)

```
User taps Login
  └── SoftphoneSDK.login()
        └── Opens CustomTabsIntent to FreJun OAuth page

User logs in on FreJun web page
  └── Server redirects to frejun://...?code=CODE&email=EMAIL

MainActivity.onNewIntent(intent)
  └── SoftphoneSDK.handleRedirect(uri)
        └── AuthManager.exchangeCodeForToken(code, email)
              └── POST /oauth/token/  → access_token, refresh_token
              └── TokenStorage.saveTokens()
        └── AuthManager.validateUserPermissions(email)
              └── GET /auth/retrieve-user-roles/
              └── Must have "integrations (iframe and sdk)" role
        └── SoftphoneSDK.start(this)   → starts SIP
```

---

### 5.3 SIP Registration

```
SoftphoneService.onStartCommand()
  └── sipManager.start()
        └── SipUserAgent.start()
              └── Endpoint.libCreate() + libInit() + libStart()  [PJSIP init]
              └── TLS transport on port 9080 created
              └── startRegistration()
                    └── PjsipAccount created with:
                          - SIP URI: sip:username@edgeDomain:9080
                          - Digest auth: password = SIP access token
                          - Custom headers: token, Authorization
                    └── PJSIP sends REGISTER to sip:edgeDomain:9080;transport=tls
                    └── Server responds 200 OK

PjsipAccount.onRegState()
  └── SipUserAgent.handleRegistrationState()
        └── listener.onConnectionStateChanged("RegistererState", "Registered")
              └── MainActivity updates status text, enables call button
              └── SoftphoneSDK.getVirtualNumbers() → populates VN dropdown
```

---

### 5.4 Making a Call

```
User enters number, selects VN, taps Call
  └── SoftphoneSDK.makeCall(destination, selectedVN, ...)
        └── [If VN changed] AuthManager.updatePrimaryVirtualNumber()
        └── [If edge domain changed] SipManager.restart() → re-registers
        └── SipUserAgent.makeCall(destination, sipCredentials, callMetaData)
              └── PjsipCall created
              └── SIP headers built (token, X-Transaction-Id, etc.)
              └── INVITE sent to sip:destination@edgeDomain:9080;transport=tls
              └── listener.onCallReceived(session, OUTGOING, destination)
                    └── MainActivity shows call card

PJSIP state progression: CALLING → EARLY → CONFIRMED
  └── PjsipCall.onCallState() → listener.onCallStateChanged()
        └── MainActivity.updateCallUI(): DIALING → RINGING → ACTIVE

CONFIRMED state:
  └── PjsipCall.onCallMediaState()
        └── SipUserAgent.connectMedia()
              └── Mic capture → call audio in
              └── Call audio out → Speaker/earpiece
              └── Two-way audio established
```

---

### 5.5 Receiving a Call

```
PJSIP receives INVITE
  └── PjsipAccount.onIncomingCall()
        └── PjsipCall(id) created
        └── 180 Ringing sent automatically
        └── SipUserAgent.handleIncomingCall(call)
              └── CallSession wrapper created
              └── listener.onCallReceived(session, INCOMING, remoteUri)
                    └── MainActivity shows Answer + Reject buttons

User taps Answer
  └── SoftphoneSDK.answerCall(session)
        └── SipUserAgent.answerCall()
              └── pjsipCall.answer(200 OK)
              └── PJSIP state → CONFIRMED
              └── connectMedia() → audio starts

User taps Hangup (or remote hangs up)
  └── SoftphoneSDK.hangupCall(session)
        └── pjsipCall.hangup()
              └── PJSIP state → DISCONNECTED
              └── listener.onCallStateChanged(DISCONNECTED)
                    └── MainActivity shows dialer again
```

---

### 5.6 Token Refresh

This happens automatically, transparent to the user.

```
Any API call made (e.g. getVirtualNumbers)
  └── AuthInterceptor adds Bearer token to request
  └── Server returns 401 (token expired)
  └── TokenAuthenticator.authenticate()
        └── [Synchronized - only one refresh runs at a time]
        └── AuthManager.refreshAccessToken()
              └── POST /oauth/token/refresh/ with refresh_token
              └── New access_token + refresh_token saved
        └── Original request retried with new token
        └── listener.onSessionRefreshed(newTokenPayload)
              └── MainActivity shows toast
```

---

### 5.7 Logout

```
SoftphoneSDK.logout()
  └── AuthManager.clearTokens()
  └── sipScope cancelled (stops all SDK coroutines)
  └── SoftphoneService stopped (sipManager.stop())
  └── SipUserAgent.destroySipStack() → PJSIP destroyed cleanly
  └── listener.onConnectionStateChanged("UserAgentState", "Disconnected")
        └── MainActivity shows login button
```

---

## 6. SoftphoneListener Callbacks

The app implements this interface to receive events from the SDK.

```kotlin
interface SoftphoneListener {

    // Called when SIP connection or registration state changes
    // type:   "UserAgentState" | "RegistererState"
    // state:  "Connected" | "Registered" | "Disconnected" | "Failed" | etc.
    // isError: true if this is an error condition
    fun onConnectionStateChanged(type: String, state: String, isError: Boolean, detail: String?)

    // Called when an incoming call arrives OR when an outgoing call is initiated
    // type: INCOMING | OUTGOING
    fun onCallReceived(callSession: CallSession, type: CallType, remoteContact: String)

    // Called whenever the call state changes (DIALING → RINGING → ACTIVE → DISCONNECTED)
    fun onCallStateChanged(callSession: CallSession, state: CallState)

    // Called after a silent token refresh completes
    fun onSessionRefreshed(payload: TokenPayload)
}
```

---

## 7. Threading Model

| Thread | What runs here |
|--------|---------------|
| **Main thread** | All UI updates. Listener callbacks are always posted here via `MainScope().launch` |
| **`Dispatchers.IO` (sdkScope)** | All REST API calls (login, getVirtualNumbers, etc.) |
| **`SipWorkerThread`** (single thread) | All PJSIP operations — endpoint creation, registration, makeCall, answerCall. PJSIP is not thread-safe so all calls must go here |

> Never call PJSIP functions from the main thread or from IO threads. Always use `sipScope.launch(sipDispatcher)` inside `SipUserAgent`.

---

## 8. Error Handling

| Scenario | What happens |
|----------|-------------|
| SIP registration fails | Retries 3× with 5-second delays, then `onConnectionStateChanged(FAILED, isError=true)` |
| API call returns 401 | `TokenAuthenticator` auto-refreshes token and retries once |
| Refresh token also invalid | SDK calls `logout()` and fires `onConnectionStateChanged(FAILED)` |
| User lacks required role | `validateUserPermissions()` throws, SDK logs out, error surfaced to listener |
| SDK used before `initialize()` | `SdkNotInitializedException` thrown |
| Edge domain changes (VN change) | `SipManager.restart()` is called to re-register with new domain |

---

## 9. Permissions & Manifest

**Permissions declared in `app/AndroidManifest.xml`:**

| Permission | Reason |
|-----------|--------|
| `INTERNET` | All API and SIP traffic |
| `RECORD_AUDIO` | Microphone for calls (also requested at runtime) |
| `READ_PHONE_STATE` | Detect interruptions from cellular calls |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE` | Network change detection |
| `WRITE_EXTERNAL_STORAGE` | PJSIP log files |
| `FOREGROUND_SERVICE` | Required for `SoftphoneService` |
| `FOREGROUND_SERVICE_PHONE_CALL` | Required for `foregroundServiceType=phoneCall` on Android 14+ |
| `MANAGE_OWN_CALLS` | Integrates with Android's telecom framework |

**Deep link (OAuth redirect):**

`MainActivity` is registered to handle `frejun://` URIs. After the user logs in via browser, the server redirects to `frejun://...?code=CODE&email=EMAIL`, and Android routes this back to `MainActivity.onNewIntent()`.

---

## 10. Build Configuration

| Property | Value |
|----------|-------|
| `compileSdk` | 36 |
| `minSdk` | 24 (Android 7.0) |
| `targetSdk` | 33 |
| Kotlin version | 2.2.10 |
| Android Gradle Plugin | 9.0.0 |
| Java target | 11 |

**SDK module key dependencies:**

| Library | Purpose |
|---------|---------|
| `retrofit2` + `converter-gson` | REST API client |
| `okhttp3` + `logging-interceptor` | HTTP client + request logging |
| `kotlinx-coroutines` | Async/background work |
| `security-crypto` | Encrypted SharedPreferences for tokens |
| `jwtdecode` | Validates JWT expiration |
| `androidx.browser` | Custom Tabs for OAuth login |
| `:pjsua2` | PJSIP native SIP engine |

---

## 11. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          APP MODULE                              │
│                                                                   │
│  MySipApplication ──initialize()──► SoftphoneSDK (singleton)    │
│                                                                   │
│  MainActivity ◄──── SoftphoneListener callbacks ◄──────────────┐│
│       │                                                          ││
│       └──── login() / start() / makeCall() / answerCall() ──────►│
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ANDROIDSOFTPHONESDK                          │
│                                                                   │
│  SoftphoneSDK                                                     │
│      ├── AuthManager                                              │
│      │       ├── TokenStorage (EncryptedSharedPrefs)             │
│      │       └── ApiClient (Retrofit)                            │
│      │               └── FrejunApiService                        │
│      │                   https://api.frejun.com/api/v1/          │
│      │                                                            │
│      └── SipManager                                               │
│              └── SipUserAgent  [SipWorkerThread]                  │
│                      ├── PjsipAccount (Registration callbacks)    │
│                      └── PjsipCall    (Call state + media)        │
│                                │                                  │
│              SoftphoneService (Foreground Service)                │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PJSUA2 MODULE (third-party)                    │
│              PJSIP native SIP/media engine (JNI)                 │
│   Transport: TLS :9080 │ STUN: stun.l.google.com │ ICE/SRTP     │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
                    FreJun SIP Server (cloud)
                    FreJun API (api.frejun.com)
```

---

## Quick Reference — Where to find things

| "I want to..." | Look here |
|----------------|-----------|
| Change SIP server config (STUN, TLS port, codecs) | `SipUserAgent.kt` constructor |
| Add a new SIP header to outgoing calls | `SipUserAgent.makeCall()` |
| Add a new API endpoint | `FrejunApiService.kt` + `AuthManager.kt` |
| Change what happens on registration success/failure | `SipUserAgent.handleRegistrationState()` |
| Change how the UI reacts to calls | `MainActivity.kt` → `SoftphoneListener` impl |
| Change what is stored per-user | `models/` + `TokenStorage.kt` |
| Debug SIP signaling (REGISTER, INVITE) | Logcat tag `PJSIP` |
| Debug API calls | Logcat tag `OkHttp` |
| Change the foreground notification text | `SoftphoneService.kt` |
| Change OAuth client credentials | `MySipApplication.kt` |
