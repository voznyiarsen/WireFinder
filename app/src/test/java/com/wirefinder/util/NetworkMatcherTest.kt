package com.wirefinder.util

import com.wirefinder.data.APNetwork
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import java.util.concurrent.TimeUnit

@RunWith(MockitoJUnitRunner::class)
class NetworkMatcherTest {

    private lateinit var matcher: NetworkMatcher

    @Before
    fun setup() {
        matcher = NetworkMatcher()
    }

    @Test
    fun matchNetwork_exactBssidMatch() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123"),
            APNetwork("aa:bb:cc:dd:ee:ff", "AnotherAP", "secret456")
        )
        matcher.updateKnownNetworks(networks)

        val result = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -50)
        assertNotNull(result)
        assertEquals("00:11:22:33:44:55", result!!.network.mac)
        assertEquals(NetworkMatcher.MatchType.BOTH, result.type)
    }

    @Test
    fun matchNetwork_caseInsensitiveSsidMatch() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // Different case SSID should match
        val result = matcher.matchNetwork("aa:bb:cc:dd:ee:ff", "mynetwork", -50)
        assertNotNull(result)
        assertEquals("MyNetwork", result!!.network.ssid)
        assertEquals(NetworkMatcher.MatchType.SSID_ONLY, result.type)
    }

    @Test
    fun matchNetwork_caseInsensitiveSsidMatch_upperCase() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "mynetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        val result = matcher.matchNetwork("aa:bb:cc:dd:ee:ff", "MYNETWORK", -50)
        assertNotNull(result)
        assertEquals("mynetwork", result!!.network.ssid)
        assertEquals(NetworkMatcher.MatchType.SSID_ONLY, result.type)
    }

    @Test
    fun matchNetwork_bssidTakesPriorityOverSsid() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "NetworkA", "pass1"),
            APNetwork("aa:bb:cc:dd:ee:ff", "NetworkB", "pass2")
        )
        matcher.updateKnownNetworks(networks)

        // Same BSSID as first, but different SSID - should match by BSSID
        val result = matcher.matchNetwork("00:11:22:33:44:55", "NetworkB", -50)
        assertNotNull(result)
        assertEquals("NetworkA", result!!.network.ssid)
        assertEquals(NetworkMatcher.MatchType.MAC_ONLY, result.type)
    }

    @Test
    fun matchNetwork_noMatch_unknownNetwork() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        val result = matcher.matchNetwork("aa:bb:cc:dd:ee:ff", "UnknownNetwork", -50)
        assertNull(result)
    }

    @Test
    fun matchNetwork_weakSignalIgnored() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // Signal weaker than threshold (-85 dBm)
        val result = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -90)
        assertNull(result)
    }

    @Test
    fun matchNetwork_debouncingPreventsDuplicateNotifications() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // First detection
        val result1 = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -50)
        assertNotNull(result1)

        // Immediate second detection should be debounced
        val result2 = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -50)
        assertNull(result2)
    }

    @Test
    fun matchNetwork_debounceClearedAfterTime() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // First detection
        val result1 = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -50)
        assertNotNull(result1)

        // Clear debounce manually
        matcher.clearDebounce("00:11:22:33:44:55")

        // Should match again after debounce cleared
        val result2 = matcher.matchNetwork("00:11:22:33:44:55", "MyNetwork", -50)
        assertNotNull(result2)
    }

    @Test
    fun matchNetwork_macNormalization() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // Test with different MAC formats
        val result1 = matcher.matchNetwork("00-11-22-33-44-55", "MyNetwork", -50)
        assertNotNull(result1)

        matcher.clearAllDebounce()
        val result2 = matcher.matchNetwork("001122334455", "MyNetwork", -50)
        assertNotNull(result2)
    }

    @Test
    fun matchNetwork_ssidFallbackBlockedWhenRequireBssid() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // Rogue AP with same SSID but unknown BSSID must NOT match in
        // background mode (requireBssid = true)
        val result = matcher.matchNetwork("aa:bb:cc:dd:ee:ff", "MyNetwork", -50, requireBssid = true)
        assertNull(result)

        // In interactive mode the SSID fallback is allowed
        val interactive = matcher.matchNetwork("aa:bb:cc:dd:ee:ff", "MyNetwork", -50)
        assertNotNull(interactive)
        assertEquals(NetworkMatcher.MatchType.SSID_ONLY, interactive!!.type)
    }

    @Test
    fun matchNetwork_macOnlyMatchWhenSsidDiffers() {
        val networks = listOf(
            APNetwork("00:11:22:33:44:55", "MyNetwork", "pass123")
        )
        matcher.updateKnownNetworks(networks)

        // Known BSSID, renamed SSID (or spoofed broadcast) -> MAC_ONLY
        val result = matcher.matchNetwork("00:11:22:33:44:55", "SomethingElse", -50)
        assertNotNull(result)
        assertEquals(NetworkMatcher.MatchType.MAC_ONLY, result!!.type)
    }

    @Test
    fun knownNetworkCount() {
        assertEquals(0, matcher.knownNetworkCount)
        
        matcher.updateKnownNetworks(listOf(
            APNetwork("00:11:22:33:44:55", "Network1", "pass"),
            APNetwork("aa:bb:cc:dd:ee:ff", "Network2", "pass")
        ))
        assertEquals(2, matcher.knownNetworkCount)
        
        matcher.updateKnownNetworks(emptyList())
        assertEquals(0, matcher.knownNetworkCount)
    }
}