package com.frejun.androidsoftphonesdk.data

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

/**
 * Represents a single virtual number (Caller ID) available to the user.
 *
 * Implements Parcelable manually to allow lists of this object to be passed via Intents if needed.
 */
data class VirtualNumber(
    @SerializedName("id")
    val id: Int,

    @SerializedName("name")
    val name: String,

    @SerializedName("country_code")
    val countryCode: String,

    @SerializedName("number")
    val number: String,

    @SerializedName("location")
    val location: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("default_calling_number")
    val isDefaultCallingNumber: Boolean,

    @SerializedName("default_sms_number")
    val isDefaultSmsNumber: Boolean

) : Parcelable {

    // Secondary constructor for deserializing from a Parcel
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
    )

    /**
     * Writes the object's data to the provided Parcel.
     * The order of writing MUST match the order of reading in the constructor.
     */
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeString(name)
        parcel.writeString(countryCode)
        parcel.writeString(number)
        parcel.writeString(location)
        parcel.writeString(type)
        parcel.writeByte(if (isDefaultCallingNumber) 1 else 0)
        parcel.writeByte(if (isDefaultSmsNumber) 1 else 0)
    }

    /**
     * Describes the kinds of special objects contained in this Parcelable instance's marshaled representation.
     */
    override fun describeContents(): Int {
        return 0
    }

    /**
     * The CREATOR object is required for creating instances from a Parcel.
     */
    companion object CREATOR : Parcelable.Creator<VirtualNumber> {
        override fun createFromParcel(parcel: Parcel): VirtualNumber {
            return VirtualNumber(parcel)
        }

        override fun newArray(size: Int): Array<VirtualNumber?> {
            return arrayOfNulls(size)
        }
    }
}