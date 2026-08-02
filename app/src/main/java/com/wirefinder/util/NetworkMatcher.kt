package com.wirefinder.util

import com.wirefinder.data.APNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Matches detected Wi-Fi networks against known networks from CSV.
 * Uses case-insensitive SSID matching and BSSID (MAC) matching.
 * Implements debouncing to avoid notification spam.
 */
class NetworkMatcher {

    enum class MatchType(val dbValue: Int) {
        SSID_ONLY(1),
        MAC_ONLY(2),
        BOTH(3);

        companion object {
            fun fromDb(value: Int): MatchType? = entries.firstOrNull { it.dbValue == value }
        }
    }

    data class MatchResult(val network: APNetwork, val type: MatchType)

    private val knownNetworks = ConcurrentHashMap<String, APNetwork>()
    private val lastNotificationTime = ConcurrentHashMap<String, Long>()

    companion object {
        val NOTIFICATION_DEBOUNCE_MS = TimeUnit.MINUTES.toMillis(5)
        const val MIN_SIGNAL_STRENGTH_DBM = -85
    }

    private val _knownNetworksFlow = MutableStateFlow<List<APNetwork>>(emptyList())
    val knownNetworksFlow: StateFlow<List<APNetwork>> = _knownNetworksFlow.asStateFlow()

    /**
     * Updates the set of known networks from CSV.
     */
    fun updateKnownNetworks(networks: List<APNetwork>) {
        knownNetworks.clear()
        for (network in networks) {
            knownNetworks[network.normalizedMac] = network
            // Also index by SSID for case-insensitive matching
            knownNetworks["SSID:${network.normalizedSsid}"] = network
        }
        _knownNetworksFlow.value = networks
    }

    /**
     * Checks if a detected network matches any known network.
     * Matches by BSSID (MAC) first; falls back to case-insensitive SSID matching
     * (spoofable by a rogue AP with the same name).
     * When requireBssid is true (background alerts), only exact BSSID matches
     * trigger, so a rogue AP with the same SSID cannot cause a false alert.
     * The returned MatchResult carries how the match was made: SSID_ONLY,
     * MAC_ONLY (BSSID matched but SSID differed), or BOTH.
     */
    fun matchNetwork(detectedBssid: String, detectedSsid: String, signalStrength: Int, requireBssid: Boolean = false): MatchResult? {
        // Skip weak signals
        if (signalStrength < MIN_SIGNAL_STRENGTH_DBM) {
            return null
        }

        val normalizedBssid = APNetwork.normalizeMac(detectedBssid)
        val normalizedSsid = APNetwork.normalizeSsid(detectedSsid)

        // First try exact BSSID match (if a usable MAC was reported)
        if (isUsableBssid(normalizedBssid)) {
            val bssidMatch = knownNetworks[normalizedBssid]
            if (bssidMatch != null) {
                val type = if (bssidMatch.normalizedSsid == normalizedSsid) {
                    MatchType.BOTH
                } else {
                    MatchType.MAC_ONLY
                }
                return checkDebounceAndReturn(bssidMatch, normalizedBssid)?.let {
                    MatchResult(it, type)
                }
            }
        }

        // Fall back to case-insensitive SSID match (if an SSID was reported,
        // e.g. hidden networks report an empty SSID) — only when a BSSID
        // match is not required
        if (!requireBssid && normalizedSsid.isNotEmpty()) {
            val ssidKey = "SSID:$normalizedSsid"
            val ssidMatch = knownNetworks[ssidKey]
            if (ssidMatch != null) {
                return checkDebounceAndReturn(ssidMatch, ssidKey)?.let {
                    MatchResult(it, MatchType.SSID_ONLY)
                }
            }
        }

        return null
    }

    /**
     * A BSSID is usable for matching when it was actually reported.
     * Android may report an empty string, or a "00:00:00:00:00:00" placeholder
     * when location-derived data is unavailable.
     */
    private fun isUsableBssid(bssid: String): Boolean {
        if (bssid.isEmpty()) return false
        if (bssid == "00:00:00:00:00:00") return false
        return true
    }

    private fun checkDebounceAndReturn(network: APNetwork, key: String): APNetwork? {
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTime[key] ?: 0

        if (now - lastTime >= NOTIFICATION_DEBOUNCE_MS) {
            lastNotificationTime[key] = now
            return network
        }
        return null
    }

    /**
     * Clears the debounce timer for a network (useful for testing).
     */
    fun clearDebounce(key: String) {
        lastNotificationTime.remove(key)
    }

    /**
     * Clears all debounce timers.
     */
    fun clearAllDebounce() {
        lastNotificationTime.clear()
    }

    /**
     * Gets the count of known networks.
     */
    val knownNetworkCount: Int
        get() = knownNetworks.size / 2 // Each network stored twice (by MAC and by SSID)
}