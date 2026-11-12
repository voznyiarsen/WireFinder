package com.wirefinder.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.wirefinder.util.KeePassImporter
import androidx.lifecycle.viewModelScope
import com.wirefinder.data.KnownNetworkRepository
import com.wirefinder.util.AppLog
import com.wirefinder.util.CsvFileStore
import com.wirefinder.util.CsvParser
import com.wirefinder.util.EncryptionService
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val knownNetworkRepository: KnownNetworkRepository,
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
        object NeedsPassword : LoadResult()
    }

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val snapshot = preferencesManager.snapshot()
            _uiState.update {
                it.copy(
                    csvPath = snapshot.csvPath,
                    scanIntervalMinutes = snapshot.scanIntervalMinutes,
                    scanningEnabled = snapshot.scanningEnabled,
                    flagSecureEnabled = snapshot.flagSecure,
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
                val file = withContext(Dispatchers.IO) { CsvFileStore.copyFromUri(context, uri) }
                if (file != null) {
                    preferencesManager.setCsvPath(file.absolutePath)
                    loadCsvFromPath(file.absolutePath)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    _loadResult.value = LoadResult.Error("Failed to access selected file")
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
                if (file.length() > CsvFileStore.MAX_CSV_SIZE) {
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
                            storeResult(
                                CsvParser.parseCsvContent(decryptedContent),
                                path,
                                needsEncryption = false
                            )
                        } catch (e: SecurityException) {
                            _uiState.update { it.copy(isLoading = false) }
                            _loadResult.value = LoadResult.Error("Password required")
                        }
                    }
                    EncryptionService.FileFormat.PLAINTEXT -> {
                        storeResult(
                            CsvParser.parseCsv(file),
                            path,
                            needsEncryption = true
                        )
                    }
                }
            }
        }
    }

    private suspend fun storeResult(
        result: CsvParser.ParseResult,
        path: String,
        needsEncryption: Boolean
    ) {
        if (result.networks.isNotEmpty()) {
            try {
                knownNetworkRepository.replace(result.networks)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _loadResult.value = LoadResult.Error(
                    "Failed to store networks",
                    AppLog.getStackTraceString(e)
                )
                return
            }
            _uiState.update { it.copy(isLoading = false, csvPath = path) }
            _loadResult.value = LoadResult.Success(result.networks.size, result.errors)
            if (needsEncryption) {
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
                val result = withContext(Dispatchers.IO) {
                    CsvParser.parseCsvContent(
                        EncryptionService.decryptBiometricToString(File(path), cipher)
                    )
                }
                storeResult(result, path, needsEncryption = false)
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
        _encryptPending.value = true
    }

    private val _encryptPending = MutableStateFlow(false)
    val encryptPending: StateFlow<Boolean> = _encryptPending

    private val _keepassUri = MutableStateFlow<Uri?>(null)

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

    fun dismissLoadResult() {
        _loadResult.value = null
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
                CsvFileStore.delete(context)
            }
            preferencesManager.setCsvPath(null)
            _uiState.update { it.copy(csvPath = null) }
        }
    }

    fun onKeePassSelected(uri: Uri) {
        _keepassUri.value = uri
        _loadResult.value = LoadResult.NeedsPassword
    }

    fun importKeePass(password: String) {
        val uri = _keepassUri.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        KeePassImporter.import(stream, password)
                    } ?: KeePassImporter.Result(emptyList(), listOf("Failed to open file"))
                }
                if (result.networks.isNotEmpty()) {
                    knownNetworkRepository.import(result.networks)
                    _uiState.update { it.copy(isLoading = false) }
                    _loadResult.value = LoadResult.Success(result.networks.size, result.errors)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    _loadResult.value = LoadResult.Error(
                        "No valid networks found in KeePass database",
                        result.errors.joinToString("\n")
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                _loadResult.value = LoadResult.Error(
                    "Failed to import KeePass database: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            } finally {
                _keepassUri.value = null
            }
        }
    }

    fun setScanInterval(interval: Int) {
        val clamped = interval.coerceIn(
            PreferencesManager.MIN_SCAN_INTERVAL,
            PreferencesManager.MAX_SCAN_INTERVAL
        )
        viewModelScope.launch {
            preferencesManager.setScanIntervalMinutes(clamped)
            _uiState.update { it.copy(scanIntervalMinutes = clamped) }
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
                _uiState.value.csvPath?.let { File(it).delete() }
                CsvFileStore.delete(context)
                File(context.filesDir, "crash_log.txt").delete()
                val dbDir = context.getDatabasePath("wirefinder_db").parentFile
                if (dbDir != null) {
                    for (suffix in listOf("", "-wal", "-shm")) {
                        File(dbDir, "wirefinder_db_legacy.bak$suffix").delete()
                    }
                }
            }
            knownNetworkRepository.clear()
            preferencesManager.clearAll()
            _uiState.update {
                it.copy(
                    csvPath = null,
                    scanIntervalMinutes = PreferencesManager.DEFAULT_SCAN_INTERVAL,
                    scanningEnabled = true,
                    flagSecureEnabled = true
                )
            }
            _loadResult.value = LoadResult.DataCleared
        }
    }
}