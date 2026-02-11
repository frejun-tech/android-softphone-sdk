package com.frejun.androidsoftphonesdk.data

import com.google.gson.annotations.SerializedName

/**
 * Models the user profile data fetched from the FreJun API.
 *
 * @property edgeDomain The WebSocket server domain (e.g., "edge-1.frejun.com") to which the SIP client must connect.
 * @property virtualNumbers A list of virtual numbers assigned to the user, used for caller ID.
 */
data class UserProfile(
    @SerializedName("edge_domain")
    val edgeDomain: String,

    @SerializedName("virtual_numbers")
    val virtualNumbers: List<VirtualNumber>
)