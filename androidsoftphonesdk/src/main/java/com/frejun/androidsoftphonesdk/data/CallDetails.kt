package com.frejun.androidsoftphonesdk.data

/**
 * A simple data class to hold contextual details about a call.
 *
 * @property candidate The phone number or SIP URI of the remote party.
 */
data class CallDetails(
    val candidate: String?
)