package com.wirefinder.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wirefinderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wirefinder_prefs"
)

@Singleton
class PreferencesManager @Inject constructor(
    context: Context
) {
    private val dataStore: DataStore<Preferences> = context.wirefinderDataStore

    // Keys
    private val KEY_CSV_PATH = stringPreferencesKey("csv_path")
    private val KEY_SCAN_INTERVAL_MINUTES = intPreferencesKey("scan_interval_minutes")
    private val KEY_SCANNING_ENABLED = booleanPreferencesKey("scanning_enabled")
    private val KEY_FLAG_SECURE = booleanPreferencesKey("flag_secure")
    private val KEY_FIRST_RUN = booleanPreferencesKey("first_run")

    // Defaults
    companion object {
        const val DEFAULT_SCAN_INTERVAL = 15 // minutes
        const val MIN_SCAN_INTERVAL = 5
        const val MAX_SCAN_INTERVAL = 120
    }

    data class Snapshot(
        val csvPath: String?,
        val scanIntervalMinutes: Int,
        val scanningEnabled: Boolean,
        val flagSecure: Boolean
    )

    suspend fun snapshot(): Snapshot {
        val prefs = dataStore.data.first()
        return Snapshot(
            csvPath = prefs[KEY_CSV_PATH],
            scanIntervalMinutes = prefs[KEY_SCAN_INTERVAL_MINUTES] ?: DEFAULT_SCAN_INTERVAL,
            scanningEnabled = prefs[KEY_SCANNING_ENABLED] ?: true,
            flagSecure = prefs[KEY_FLAG_SECURE] ?: true
        )
    }

    // CSV Path
    val csvPath: Flow<String?> = dataStore.data.map { it[KEY_CSV_PATH] }

    suspend fun setCsvPath(path: String?) {
        dataStore.edit { prefs ->
            if (path.isNullOrEmpty()) {
                prefs.remove(KEY_CSV_PATH)
            } else {
                prefs[KEY_CSV_PATH] = path
            }
        }
    }

    // Scan Interval
    val scanIntervalMinutes: Flow<Int> = dataStore.data.map {
        it[KEY_SCAN_INTERVAL_MINUTES] ?: DEFAULT_SCAN_INTERVAL
    }

    suspend fun setScanIntervalMinutes(interval: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SCAN_INTERVAL_MINUTES] = interval.coerceIn(MIN_SCAN_INTERVAL, MAX_SCAN_INTERVAL)
        }
    }

    // Scanning Enabled
    val scanningEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_SCANNING_ENABLED] ?: true
    }

    suspend fun setScanningEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SCANNING_ENABLED] = enabled
        }
    }

    // FLAG_SECURE for password-bearing screens (default on)
    val flagSecure: Flow<Boolean> = dataStore.data.map {
        it[KEY_FLAG_SECURE] ?: true
    }

    suspend fun setFlagSecure(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_FLAG_SECURE] = enabled
        }
    }

    // First Run
    val isFirstRun: Flow<Boolean> = dataStore.data.map {
        it[KEY_FIRST_RUN] ?: true
    }

    suspend fun setFirstRunComplete() {
        dataStore.edit { prefs ->
            prefs[KEY_FIRST_RUN] = false
        }
    }

    // Clear all data
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
