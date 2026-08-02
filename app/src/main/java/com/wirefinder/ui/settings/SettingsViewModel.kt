package com.wirefinder.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirefinder.data.APNetworkDao
import com.wirefinder.util.AppLog
import com.wirefinder.util.CsvParser
import com.wirefinder.util.EncryptionService
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apNetworkDao: APNetworkDao,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _loadResult = MutableStateFlow<LoadResult?>(null)
    val loadResult: StateFlow<LoadResult?> = _loadResult

    data class UiState(
        val csvPath: String? = null,
        val scanIntervalMinutes: Int = PreferencesManager.DEFAULT_SCAN_INTERVAL,
        val scanningEnabled: Boolean = true,
        val flagSecureEnabled: Boolean = true,
        val isLoading: Boolean = false
    )

    sealed class LoadResult {
        data class Success(val count: Int, val errors: List<String> = emptyList()) : LoadResult()
        data class Error(val message: String, val details: String? = null) : LoadResult()
        object NeedsBiometricDecrypt : LoadResult()
        object Encrypted : LoadResult()
        object DataCleared : LoadResult()
    }

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val csvPath = preferencesManager.csvPath.first()
            val scanInterval = preferencesManager.scanIntervalMinutes.first()
            val scanningEnabled = preferencesManager.scanningEnabled.first()
            val flagSecure = preferencesManager.flagSecure.first()
            
            _uiState.update { 
                it.copy(
                    csvPath = csvPath,
                    scanIntervalMinutes = scanInterval,
                    scanningEnabled = scanningEnabled,
                    flagSecureEnabled = flagSecure,
                    isLoading = false
                ) 
            }
        }
    }

    fun onCsvSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _loadResult.value = null
            
            try {
                val file = withContext(Dispatchers.IO) { copyUriToFile(uri) }
                if (file != null) {
                    preferencesManager.setCsvPath(file.absolutePath)
                    loadCsvFromPath(file.absolutePath)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _loadResult.value = LoadResult.Error(
                    "Error: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            }
        }
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val permanentFile = File(context.filesDir, "selected_networks.csv")
            val outputStream = FileOutputStream(permanentFile)
            try {
                val buffer = ByteArray(8192)
                var total = 0L
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    total += read
                    if (total > MAX_CSV_SIZE) {
                        return null
                    }
                    outputStream.write(buffer, 0, read)
                }
            } finally {
                inputStream.close()
                outputStream.close()
            }
            permanentFile
        } catch (e: Exception) {
            File(context.filesDir, "selected_networks.csv").delete()
            null
        }
    }

    private fun loadCsvFromPath(path: String, password: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists()) {
                    _uiState.update { it.copy(isLoading = false) }
                    _loadResult.value = LoadResult.Error("File not found")
                    return@withContext
                }
                if (file.length() > MAX_CSV_SIZE) {
                    _uiState.update { it.copy(isLoading = false) }
                    _loadResult.value = LoadResult.Error(
                        context.getString(com.wirefinder.R.string.csv_too_large)
                    )
                    return@withContext
                }

                when (EncryptionService.detectFormat(file)) {
                    EncryptionService.FileFormat.BIOMETRIC_ENCRYPTED -> {
                        _uiState.update { it.copy(isLoading = false, csvPath = path) }
                        _loadResult.value = LoadResult.NeedsBiometricDecrypt
                    }
                    EncryptionService.FileFormat.PASSWORD_ENCRYPTED -> {
                        // Legacy WFC1 file: password flow (never persisted)
                        if (password.isNullOrEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                            _loadResult.value = LoadResult.Error("Password required")
                            return@withContext
                        }
                        try {
                            val decryptedContent = EncryptionService.decryptToString(file, password)
                            parseAndStore(decryptedContent, path)
                        } catch (e: SecurityException) {
                            _uiState.update { it.copy(isLoading = false) }
                            _loadResult.value = LoadResult.Error("Password required")
                        }
                    }
                    EncryptionService.FileFormat.PLAINTEXT -> {
                        parseAndStore(file.readText(), path)
                    }
                }
            }
        }
    }

    private suspend fun parseAndStore(content: String, path: String) {
        val result = withContext(Dispatchers.IO) { CsvParser.parseCsvContent(content) }
        if (result.networks.isNotEmpty()) {
            apNetworkDao.insertAll(result.networks)
            _uiState.update { it.copy(isLoading = false, csvPath = path) }
            _loadResult.value = LoadResult.Success(result.networks.size, result.errors)
            if (withContext(Dispatchers.IO) {
                    EncryptionService.detectFormat(File(path)) == EncryptionService.FileFormat.PLAINTEXT
                }
            ) {
                promptEncrypt()
            }
        } else {
            _uiState.update { it.copy(isLoading = false) }
            _loadResult.value = LoadResult.Error(
                "No valid networks found in CSV",
                result.errors.joinToString("\n")
            )
        }
    }

    fun prepareBiometricDecrypt(): javax.crypto.Cipher? {
        val path = _uiState.value.csvPath ?: return null
        return try {
            EncryptionService.createDecryptCipher(EncryptionService.readBiometricIv(File(path)))
        } catch (e: Exception) {
            AppLog.e("WireFinderScan", "Settings decrypt: cannot create cipher", e)
            _loadResult.value = LoadResult.Error(
                context.getString(com.wirefinder.R.string.encrypt_key_invalidated),
                AppLog.getStackTraceString(e)
            )
            null
        }
    }

    fun decryptWithBiometric(cipher: javax.crypto.Cipher) {
        viewModelScope.launch {
            val path = _uiState.value.csvPath ?: return@launch
            try {
                val content = withContext(Dispatchers.IO) {
                    EncryptionService.decryptBiometricToString(File(path), cipher)
                }
                parseAndStore(content, path)
            } catch (e: Exception) {
                AppLog.e("WireFinderScan", "Settings decrypt: biometric decryption failed", e)
                _uiState.update { it.copy(isLoading = false) }
                _loadResult.value = LoadResult.Error(
                    "Decryption failed: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            }
        }
    }

    private fun promptEncrypt() {
        viewModelScope.launch {
            _encryptPending.value = true
        }
    }

    private val _encryptPending = MutableStateFlow(false)
    val encryptPending: StateFlow<Boolean> = _encryptPending

    fun prepareBiometricEncrypt(): javax.crypto.Cipher? {
        return try {
            EncryptionService.createEncryptCipher()
        } catch (e: Exception) {
            AppLog.e("WireFinderScan", "Settings encrypt: cannot create cipher", e)
            _encryptPending.value = false
            _loadResult.value = LoadResult.Error(
                context.getString(com.wirefinder.R.string.encrypt_key_invalidated),
                AppLog.getStackTraceString(e)
            )
            null
        }
    }

    fun encryptWithBiometric(cipher: javax.crypto.Cipher) {
        viewModelScope.launch {
            val path = _uiState.value.csvPath ?: return@launch
            val file = File(path)
            try {
                withContext(Dispatchers.IO) {
                    val temp = File(file.parentFile, "${file.name}.enc.tmp")
                    EncryptionService.encryptFileBiometric(file, temp, cipher)
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }
                _encryptPending.value = false
                _loadResult.value = LoadResult.Encrypted
            } catch (e: Exception) {
                File(file.parentFile, "${file.name}.enc.tmp").delete()
                AppLog.e("WireFinderScan", "Settings encrypt: biometric encryption failed", e)
                _loadResult.value = LoadResult.Error(
                    "Encryption failed: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            }
        }
    }

    fun clearEncryptPending() {
        _encryptPending.value = false
    }

    private companion object {
        const val MAX_CSV_SIZE = 5L * 1024 * 1024
    }

    fun retryWithPassword(password: String) {
        viewModelScope.launch {
            _uiState.value.csvPath?.let { loadCsvFromPath(it, password) }
        }
    }

    fun deleteStoredCsv() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _uiState.value.csvPath?.let { File(it).delete() }
            }
            preferencesManager.setCsvPath(null)
            _uiState.update { it.copy(csvPath = null) }
        }
    }

    fun setScanInterval(interval: Int) {
        viewModelScope.launch {
            preferencesManager.setScanIntervalMinutes(interval)
            _uiState.update { it.copy(scanIntervalMinutes = interval) }
        }
    }

    fun setScanningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setScanningEnabled(enabled)
            _uiState.update { it.copy(scanningEnabled = enabled) }
            if (enabled) {
                com.wirefinder.service.WifiScanForegroundService.start(context)
            } else {
                com.wirefinder.service.WifiScanForegroundService.stop(context)
            }
        }
    }

    fun setFlagSecure(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setFlagSecure(enabled)
            _uiState.update { it.copy(flagSecureEnabled = enabled) }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Delete the stored CSV (contains all passwords)
                _uiState.value.csvPath?.let { File(it).delete() }
                // Delete crash log
                File(context.filesDir, "crash_log.txt").delete()
                // Delete any legacy plaintext database backups
                val dbDir = context.getDatabasePath("wirefinder_db").parentFile
                if (dbDir != null) {
                    for (suffix in listOf("", "-wal", "-shm")) {
                        File(dbDir, "wirefinder_db_legacy.bak$suffix").delete()
                    }
                }
            }
            apNetworkDao.clearAll()
            preferencesManager.clearAll()
            _uiState.update { 
                it.copy(csvPath = null, scanIntervalMinutes = PreferencesManager.DEFAULT_SCAN_INTERVAL, scanningEnabled = true) 
            }
            _loadResult.value = LoadResult.DataCleared
        }
    }
}