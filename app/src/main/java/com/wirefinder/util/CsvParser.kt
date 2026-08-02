package com.wirefinder.util

import com.wirefinder.data.APNetwork
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

object CsvParser {

    data class ParseResult(
        val networks: List<APNetwork>,
        val errors: List<String>,
        val linesParsed: Int
    )

    /**
     * Parses a CSV file with format: MAC,SSID,PASSWORD
     * - Case-insensitive SSID matching
     * - Handles quoted fields
     * - Skips empty lines and comments (starting with #)
     * - Normalizes MAC addresses to lowercase with colons
     */
    fun parseCsv(file: File, password: String? = null): ParseResult {
        val networks = mutableListOf<APNetwork>()
        val errors = mutableListOf<String>()
        var linesParsed = 0

        val inputStream = if (password != null && password.isNotEmpty()) {
            // File is encrypted, decrypt first
            try {
                EncryptionService.decryptFile(file, password)
            } catch (e: Exception) {
                return ParseResult(emptyList(), listOf("Decryption failed: ${e.message}"), 0)
            }
        } else {
            FileInputStream(file)
        }

        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))

        try {
            var line: String?
            var lineNumber = 0
            while (reader.readLine().also { line = it } != null) {
                lineNumber++
                val trimmed = line!!.trim()
                
                // Skip empty lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue
                }

                linesParsed++
                
                // Parse CSV line (handle quoted fields)
                val fields = parseCsvLine(trimmed)
                if (fields.size < 3) {
                    errors.add("Line $lineNumber: Expected 3 fields (MAC,SSID,PASSWORD), got ${fields.size}")
                    continue
                }

                val mac = fields[0].trim()
                val ssid = fields[1].trim()
                val passwordField = fields[2].trim()

                // Validate MAC address format
                if (!isValidMac(mac)) {
                    errors.add("Line $lineNumber: Invalid MAC address '$mac'")
                    continue
                }

                if (ssid.isEmpty()) {
                    errors.add("Line $lineNumber: Empty SSID")
                    continue
                }

                val normalizedMac = APNetwork.normalizeMac(mac)
                val normalizedSsid = APNetwork.normalizeSsid(ssid)

                networks.add(APNetwork(
                    mac = normalizedMac,
                    ssid = normalizedSsid,
                    password = passwordField
                ))
            }
        } finally {
            reader.close()
        }

        return ParseResult(networks, errors, linesParsed)
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote
                        sb.append('"')
                        i += 2
                    } else {
                        inQuotes = !inQuotes
                        i++
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(sb.toString())
                    sb.setLength(0)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        fields.add(sb.toString())
        return fields
    }

    private fun isValidMac(mac: String): Boolean {
        // Accept formats: AA:BB:CC:DD:EE:FF or AA-BB-CC-DD-EE-FF or AABBCCDDEEFF
        val colonPattern = "^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$".toRegex()
        val dashPattern = "^([0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}$".toRegex()
        val noSepPattern = "^[0-9A-Fa-f]{12}$".toRegex()
        return mac.matches(colonPattern) || mac.matches(dashPattern) || mac.matches(noSepPattern)
    }

    fun parseCsvContent(content: String): ParseResult {
        val networks = mutableListOf<APNetwork>()
        val errors = mutableListOf<String>()
        var linesParsed = 0

        content.lines().forEachIndexed { index, line ->
            val lineNumber = index + 1
            val trimmed = line.trim()
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEachIndexed
            }

            linesParsed++
            val fields = parseCsvLine(trimmed)
            
            if (fields.size < 3) {
                errors.add("Line $lineNumber: Expected 3 fields (MAC,SSID,PASSWORD), got ${fields.size}")
                return@forEachIndexed
            }

            val mac = fields[0].trim()
            val ssid = fields[1].trim()
            val passwordField = fields[2].trim()

            if (!isValidMac(mac)) {
                errors.add("Line $lineNumber: Invalid MAC address '$mac'")
                return@forEachIndexed
            }

            if (ssid.isEmpty()) {
                errors.add("Line $lineNumber: Empty SSID")
                return@forEachIndexed
            }

            val normalizedMac = APNetwork.normalizeMac(mac)
            val normalizedSsid = APNetwork.normalizeSsid(ssid)

            networks.add(APNetwork(
                mac = normalizedMac,
                ssid = normalizedSsid,
                password = passwordField
            ))
        }

        return ParseResult(networks, errors, linesParsed)
    }
}