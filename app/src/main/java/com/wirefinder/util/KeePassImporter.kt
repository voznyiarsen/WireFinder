package com.wirefinder.util

import com.wirefinder.data.APNetwork
import org.linguafranca.pwdb.format.KdbxCredentials
import org.linguafranca.pwdb.kdbx.jackson.KdbxDatabase
import java.io.InputStream
import java.nio.charset.StandardCharsets

object KeePassImporter {

    data class Result(
        val networks: List<APNetwork>,
        val errors: List<String>
    )

    fun import(inputStream: InputStream, password: String): Result {
        val networks = mutableListOf<APNetwork>()
        val errors = mutableListOf<String>()
        var count = 0
        return try {
            inputStream.use { stream ->
                val credentials = KdbxCredentials(password.toByteArray(StandardCharsets.UTF_8))
                val database = KdbxDatabase.read(credentials, stream)
                fun walk(group: org.linguafranca.pwdb.Group) {
                    for (entry in group.entries) {
                        count++
                        val ssid = entry.title?.trim().orEmpty()
                        val bssidField = entry.username?.trim().orEmpty()
                        val pwd = entry.password.orEmpty()
                        if (ssid.isEmpty()) {
                            errors.add("Entry $count: empty title/SSID")
                            continue
                        }
                        val macs = bssidField.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (macs.isEmpty()) {
                            errors.add("Entry $count: missing BSSID/MAC")
                            continue
                        }
                        var added = false
                        for (mac in macs) {
                            if (!CsvParser.isValidMac(mac)) {
                                errors.add("Entry $count: invalid BSSID '$mac'")
                                continue
                            }
                            networks.add(
                                APNetwork(
                                    mac = APNetwork.normalizeMac(mac),
                                    ssid = ssid,
                                    password = pwd
                                )
                            )
                            added = true
                        }
                        if (!added) continue
                    }
                    for (child in group.groups) {
                        walk(child)
                    }
                }
                walk(database.rootGroup)
            }
            Result(networks, errors)
        } catch (e: Exception) {
            Result(emptyList(), listOf("Failed to read KeePass database: ${e.message}"))
        }
    }
}