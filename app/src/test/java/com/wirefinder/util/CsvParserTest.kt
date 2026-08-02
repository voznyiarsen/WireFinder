package com.wirefinder.util

import com.wirefinder.data.APNetwork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(MockitoJUnitRunner::class)
class CsvParserTest {

    @Test
    fun parseCsvContent_basicParsing() {
        val content = "00:11:22:33:44:55,MyNetwork,password123\naa:bb:cc:dd:ee:ff,AnotherAP,secret456"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(2, result.networks.size)
        assertEquals(0, result.errors.size)
        assertEquals(2, result.linesParsed)
        
        val network1 = result.networks[0]
        assertEquals("00:11:22:33:44:55", network1.mac)
        assertEquals("MyNetwork", network1.ssid)
        assertEquals("password123", network1.password)
        
        val network2 = result.networks[1]
        assertEquals("aa:bb:cc:dd:ee:ff", network2.mac)
        assertEquals("AnotherAP", network2.ssid)
        assertEquals("secret456", network2.password)
    }

    @Test
    fun parseCsvContent_caseInsensitiveSsid() {
        val content = "00:11:22:33:44:55,MyNetwork,password123\nAA:BB:CC:DD:EE:FF,mynetwork,differentpass"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(2, result.networks.size)
        // Both should be stored (different MACs, same SSID case-insensitive)
        assertEquals("MyNetwork", result.networks[0].ssid)
        assertEquals("mynetwork", result.networks[1].ssid)
    }

    @Test
    fun parseCsvContent_macNormalization() {
        val content = "00-11-22-33-44-55,Test,pass\nAABBCCDDEEFF,Test2,pass2"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(2, result.networks.size)
        assertEquals("00:11:22:33:44:55", result.networks[0].mac)
        assertEquals("aa:bb:cc:dd:ee:ff", result.networks[1].mac)
    }

    @Test
    fun parseCsvContent_quotedFields() {
        val content = "\"00:11:22:33:44:55\",\"My,Network\",\"pass,word\""
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(1, result.networks.size)
        assertEquals("00:11:22:33:44:55", result.networks[0].mac)
        assertEquals("My,Network", result.networks[0].ssid)
        assertEquals("pass,word", result.networks[0].password)
    }

    @Test
    fun parseCsvContent_escapedQuotes() {
        val content = "\"00:11:22:33:44:55\",\"My \"\"Network\"\",\"password\""
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(1, result.networks.size)
        assertEquals("My \"Network", result.networks[0].ssid)
    }

    @Test
    fun parseCsvContent_skipsEmptyLinesAndComments() {
        val content = "# This is a comment\n\n00:11:22:33:44:55,Test,pass\n  \n# Another comment\nAA:BB:CC:DD:EE:FF,Test2,pass2"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(2, result.networks.size)
        assertEquals(2, result.linesParsed)
    }

    @Test
    fun parseCsvContent_invalidMac() {
        val content = "invalid-mac,Test,pass\n00:11:22:33:44:55,Valid,pass2"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(1, result.networks.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Invalid MAC"))
    }

    @Test
    fun parseCsvContent_emptySsid() {
        val content = "00:11:22:33:44:55,,password"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(0, result.networks.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Empty SSID"))
    }

    @Test
    fun parseCsvContent_insufficientFields() {
        val content = "00:11:22:33:44:55,OnlyTwoFields"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(0, result.networks.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Expected 3 fields"))
    }

    @Test
    fun parseCsvContent_mixedCaseMac() {
        val content = "AA:BB:CC:DD:EE:FF,Test,pass"
        val result = CsvParser.parseCsvContent(content)
        
        assertEquals(1, result.networks.size)
        assertEquals("aa:bb:cc:dd:ee:ff", result.networks[0].mac)
    }
}