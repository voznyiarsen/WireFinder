package com.wirefinder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

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
) {

    companion object {
        fun normalizeMac(mac: String): String {
            val trimmed = mac.trim()
            val hex = StringBuilder(12)
            for (c in trimmed) {
                when {
                    c == ':' || c == '-' -> Unit
                    c.isLetterOrDigit() -> hex.append(c)
                    else -> return trimmed.lowercase().replace('-', ':')
                }
            }
            if (hex.length != 12) return trimmed.lowercase().replace('-', ':')

            val normalized = StringBuilder(17)
            for (i in 0 until 12) {
                if (i > 0 && i % 2 == 0) normalized.append(':')
                normalized.append(hex[i].lowercaseChar())
            }
            return normalized.toString()
        }

        fun normalizeSsid(ssid: String): String = ssid.trim().lowercase()
    }

    val normalizedMac: String
        get() = normalizeMac(mac)

    val normalizedSsid: String
        get() = normalizeSsid(ssid)
}