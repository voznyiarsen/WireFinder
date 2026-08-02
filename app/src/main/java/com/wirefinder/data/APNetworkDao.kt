package com.wirefinder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface APNetworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(networks: List<APNetwork>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(network: APNetwork)

    @Update
    suspend fun update(network: APNetwork)

    @Query("DELETE FROM ap_networks")
    suspend fun clearAll()

    @Query("SELECT * FROM ap_networks ORDER BY ssid ASC, mac ASC")
    fun getAllNetworks(): Flow<List<APNetwork>>

    @Query("SELECT * FROM ap_networks ORDER BY ssid ASC, mac ASC")
    suspend fun getAllNetworksOnce(): List<APNetwork>

    @Query("SELECT * FROM ap_networks WHERE mac = :mac")
    suspend fun getByMac(mac: String): APNetwork?

    @Query("SELECT * FROM ap_networks WHERE ssid = :ssid COLLATE NOCASE")
    suspend fun getBySsid(ssid: String): List<APNetwork>

    @Query("SELECT * FROM ap_networks WHERE mac = :mac OR ssid = :ssid COLLATE NOCASE")
    suspend fun findMatching(mac: String, ssid: String): List<APNetwork>

    @Query("UPDATE ap_networks SET lastSeen = :lastSeen, lastSignalStrength = :signal, detectionCount = detectionCount + 1, lastMatchType = :matchType WHERE mac = :mac")
    suspend fun updateDetection(mac: String, lastSeen: Long, signal: Int, matchType: Int)

    @Query("SELECT COUNT(*) FROM ap_networks")
    suspend fun count(): Int
}