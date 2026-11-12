package com.wirefinder.data

import com.wirefinder.util.NetworkMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class KnownNetworkRepository @Inject constructor(
    private val apNetworkDao: APNetworkDao,
    private val networkMatcher: NetworkMatcher
) {

    fun observeAll(): Flow<List<APNetwork>> = apNetworkDao.getAllNetworks()

    suspend fun getAll(): List<APNetwork> = apNetworkDao.getAllNetworksOnce()

    suspend fun replace(networks: List<APNetwork>) {
        apNetworkDao.insertAll(networks)
        networkMatcher.updateKnownNetworks(networks)
    }

    suspend fun reload() {
        networkMatcher.updateKnownNetworks(apNetworkDao.getAllNetworksOnce())
    }

    suspend fun clear() {
        apNetworkDao.clearAll()
        networkMatcher.updateKnownNetworks(emptyList())
    }

    suspend fun recordDetections(updates: List<DetectionUpdate>) {
        if (updates.isNotEmpty()) {
            apNetworkDao.updateDetections(updates)
        }
    }
}
