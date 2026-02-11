package com.frejun.androidsoftphonesdk.data

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

/**
 * Holds the SIP credentials required to register the PJSIP account.
 * This data is fetched from the FreJun backend after a successful login.
 *
 * Implements Parcelable manually to be passed via Intents.
 *
 * @property username The SIP username (e.g., "user123").
 * @property accessToken The SIP password or token used for authentication.
 */
data class SipCredentials(
    @SerializedName("username")
    val username: String,

    @SerializedName("access_token")
    val accessToken: String,

    @SerializedName("success")
    val success: Boolean = true

) : Parcelable {

    // Secondary constructor for creating an instance from a Parcel
    constructor(parcel: Parcel) : this(
        parcel.readString()!!, // Read username
        parcel.readString()!!, // Read accessToken
        parcel.readByte() != 0.toByte() // Read success (as a byte) and convert back to Boolean
    )

    /**
     * Writes the object's data to the provided Parcel.
     * The order of writing MUST match the order of reading in the constructor.
     */
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(username)
        parcel.writeString(accessToken)
        // Booleans are written as bytes (1 for true, 0 for false)
        parcel.writeByte(if (success) 1 else 0)
    }

    /**
     * Describes the kinds of special objects contained in this Parcelable instance's marshaled representation.
     * Almost always returns 0.
     */
    override fun describeContents(): Int {
        return 0
    }

    /**
     * The CREATOR object is required for the Android framework to be able to create
     * instances of your Parcelable class from a Parcel.
     */
    companion object CREATOR : Parcelable.Creator<SipCredentials> {
        override fun createFromParcel(parcel: Parcel): SipCredentials {
            return SipCredentials(parcel)
        }

        override fun newArray(size: Int): Array<SipCredentials?> {
            return arrayOfNulls(size)
        }
    }
}