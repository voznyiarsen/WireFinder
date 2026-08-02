package com.wirefinder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "ap_networks")
data class APNetwork(
    @PrimaryKey
    val mac: String,  // BSSID as primary key (normalized to lowercase)
    val ssid: String,
    val password: String,
    val lastSeen: Long = 0,
    val lastSignalStrength: Int = 0,
    val detectionCount: Int = 0,
    // Last detection's match type: 0 = none, 1 = SSID only, 2 = MAC only, 3 = both
    val lastMatchType: Int = 0
) : Serializable {

    companion object {
        fun normalizeMac(mac: String): String = mac.lowercase().replace("-", ":")
        fun normalizeSsid(ssid: String): String = ssid.trim().lowercase()
    }

    val normalizedMac: String
        get() = normalizeMac(mac)

    val normalizedSsid: String
        get() = normalizeSsid(ssid)
}