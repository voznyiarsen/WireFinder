package com.wirefinder.util

import com.wirefinder.data.APNetwork
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.StandardCharsets

object CsvParser {

    private const val MAX_ERRORS = 100

    data class ParseResult(
        val networks: List<APNetwork>,
        val errors: List<String>,
        val linesParsed: Int
    )

    fun parseCsv(file: File, password: String? = null): ParseResult {
        return try {
            val reader = if (!password.isNullOrEmpty()) {
                BufferedReader(StringReader(EncryptionService.decryptToString(file, password)))
            } else {
                BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8))
            }
            reader.use { parse(it) }
        } catch (e: SecurityException) {
            ParseResult(emptyList(), listOf("Decryption failed: ${e.message}"), 0)
        } catch (e: Exception) {
            ParseResult(emptyList(), listOf("Failed to read CSV: ${e.message}"), 0)
        }
    }

    fun parseCsvContent(content: String): ParseResult {
        return BufferedReader(StringReader(content)).use { parse(it) }
    }

    private fun parse(reader: BufferedReader): ParseResult {
        val networks = LinkedHashMap<String, APNetwork>()
        val errors = ArrayList<String>()
        var linesParsed = 0
        var lineNumber = 0

        while (true) {
            val line = reader.readLine() ?: break
            lineNumber++
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            linesParsed++
            val fields = parseCsvLine(trimmed)
            if (fields.size < 3) {
                addError(
                    errors,
                    "Line $lineNumber: Expected at least 3 fields (MAC[,MAC,...],SSID,PASSWORD), got ${fields.size}"
                )
                continue
            }

            // All fields except the last two are MAC addresses for the same SSID/password.
            // Supports both "MAC1,MAC2,SSID,PASSWORD" and quoted '"MAC1,MAC2",SSID,PASSWORD".
            val ssid = fields[fields.size - 2].trim()
            val password = fields[fields.size - 1].trim()

            if (ssid.isEmpty()) {
                addError(errors, "Line $lineNumber: Empty SSID")
                continue
            }

            val macs = mutableListOf<String>()
            for (i in 0 until fields.size - 2) {
                for (part in fields[i].split(",")) {
                    val m = part.trim()
                    if (m.isNotEmpty()) macs.add(m)
                }
            }
            if (macs.isEmpty()) {
                addError(errors, "Line $lineNumber: No MAC addresses provided")
                continue
            }

            for (mac in macs) {
                if (!isValidMac(mac)) {
                    addError(errors, "Line $lineNumber: Invalid MAC address '$mac'")
                    continue
                }
                val normalizedMac = APNetwork.normalizeMac(mac)
                networks[normalizedMac] = APNetwork(
                    mac = normalizedMac,
                    ssid = ssid,
                    password = password
                )
            }
        }

        return ParseResult(networks.values.toList(), errors, linesParsed)
    }

    private fun addError(errors: MutableList<String>, message: String) {
        if (errors.size < MAX_ERRORS) {
            errors.add(message)
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = ArrayList<String>(3)
        val field = StringBuilder(line.length)
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        if (i + 2 >= line.length || line[i + 2] == ',') {
                            inQuotes = false
                        } else {
                            field.append('"')
                        }
                        i += 2
                    } else {
                        inQuotes = !inQuotes
                        i++
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        fields.add(field.toString())
        return fields
    }

    fun isValidMac(mac: String): Boolean {
        var hexDigits = 0
        for (c in mac) {
            when {
                c == ':' || c == '-' -> Unit
                c.isDigit() || c.lowercaseChar() in 'a'..'f' -> hexDigits++
                else -> return false
            }
        }
        return hexDigits == 12
    }
}
