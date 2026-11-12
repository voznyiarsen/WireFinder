package com.wirefinder.util

import com.wirefinder.data.APNetwork
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NetworkMatcher {

    enum class MatchType(val dbValue: Int) {
        SSID_ONLY(1),
        MAC_ONLY(2),
        BOTH(3);

        companion object {
            fun fromDb(value: Int): MatchType? = when (value) {
                1 -> SSID_ONLY
                2 -> MAC_ONLY
                3 -> BOTH
                else -> null
            }
        }
    }

    data class MatchResult(val network: APNetwork, val type: MatchType)

    private data class KnownMaps(
        val byMac: Map<String, APNetwork>,
        val bySsid: Map<String, APNetwork>
    )

    @Volatile
    private var knownMaps: KnownMaps = KnownMaps(emptyMap(), emptyMap())

    private val lastNotificationTime = ConcurrentHashMap<String, Long>()

    companion object {
        val NOTIFICATION_DEBOUNCE_MS = TimeUnit.MINUTES.toMillis(5)
        const val MIN_SIGNAL_STRENGTH_DBM = -85
        const val PARTIAL_SSID_MIN_LEN = 4
    }

    fun updateKnownNetworks(networks: List<APNetwork>) {
        val byMac = HashMap<String, APNetwork>(networks.size)
        val bySsid = HashMap<String, APNetwork>(networks.size)
        for (network in networks) {
            byMac[network.normalizedMac] = network
            bySsid[network.normalizedSsid] = network
        }
        knownMaps = KnownMaps(byMac, bySsid)

        val now = System.currentTimeMillis()
        lastNotificationTime.entries.removeIf {
            now - it.value >= NOTIFICATION_DEBOUNCE_MS
        }
    }

    fun matchNetwork(
        detectedBssid: String,
        detectedSsid: String,
        signalStrength: Int,
        requireBssid: Boolean = false
    ): MatchResult? {
        if (signalStrength < MIN_SIGNAL_STRENGTH_DBM) {
            return null
        }

        val normalizedBssid = APNetwork.normalizeMac(detectedBssid)
        val normalizedSsid = APNetwork.normalizeSsid(detectedSsid)

        val maps = knownMaps
        if (isUsableBssid(normalizedBssid)) {
            val bssidMatch = maps.byMac[normalizedBssid]
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

        if (!requireBssid && normalizedSsid.isNotEmpty()) {
            val ssidMatch = maps.bySsid[normalizedSsid]
            if (ssidMatch != null) {
                return checkDebounceAndReturn(ssidMatch, normalizedSsid)?.let {
                    MatchResult(it, MatchType.SSID_ONLY)
                }
            }

            // Partial SSID match: detect networks whose SSID contains or is contained by a known SSID.
            val partial = maps.bySsid.entries.asSequence().firstOrNull { (known, _) ->
                known.length >= PARTIAL_SSID_MIN_LEN &&
                    (normalizedSsid.contains(known) || known.contains(normalizedSsid))
            }?.value
            if (partial != null) {
                return checkDebounceAndReturn(partial, normalizedSsid)?.let {
                    MatchResult(it, MatchType.SSID_ONLY)
                }
            }
        }

        return null
    }

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

    fun clearDebounce(key: String) {
        lastNotificationTime.remove(key)
    }

    fun clearAllDebounce() {
        lastNotificationTime.clear()
    }

    val knownNetworkCount: Int
        get() = knownMaps.byMac.size
}
