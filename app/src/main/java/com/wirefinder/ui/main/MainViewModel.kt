package com.wirefinder.ui.main

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirefinder.R
import com.wirefinder.data.APNetwork
import com.wirefinder.data.APNetworkDao
import com.wirefinder.util.AppLog
import com.wirefinder.util.CsvParser
import com.wirefinder.util.EncryptionService
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val apNetworkDao: APNetworkDao,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _csvLoadResult = MutableStateFlow<CsvLoadResult?>(null)
    val csvLoadResult: StateFlow<CsvLoadResult?> = _csvLoadResult

    private val _manualScanResult = MutableStateFlow<ManualScanResult?>(null)
    val manualScanResult: StateFlow<ManualScanResult?> = _manualScanResult

    private val _connectResult = MutableStateFlow<ConnectResult?>(null)
    val connectResult: StateFlow<ConnectResult?> = _connectResult

    private var scanTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var connectHandled = false
    private var activeConnectCallback: ConnectivityManager.NetworkCallback? = null
    private var activeConnectivityManager: ConnectivityManager? = null

    data class UiState(
        val networks: List<APNetwork> = emptyList(),
        val isLoading: Boolean = false,
        val isScanning: Boolean = false,
        val error: String? = null,
        val csvPath: String? = null,
        val hasCsv: Boolean = false
    )

    sealed class CsvLoadResult {
        data class Success(
            val count: Int,
            val errors: List<String>,
            val needsEncryption: Boolean = false
        ) : CsvLoadResult()
        data class Error(val message: String, val details: String? = null) : CsvLoadResult()
        object NeedsBiometricDecrypt : CsvLoadResult()
        object Encrypted : CsvLoadResult()
    }

    sealed class ManualScanResult {
        data class Match(val network: APNetwork, val signalStrength: Int, val matchType: Int = 0)
        data class Detected(val matches: List<Match>, val note: String? = null) : ManualScanResult()
        data class None(val note: String? = null) : ManualScanResult()
        data class Error(
            val message: String,
            val details: String? = null,
            val needsLocation: Boolean = false,
            val needsPermissions: List<String> = emptyList()
        ) : ManualScanResult()
    }

    sealed class ConnectResult {
        data class Connecting(val ssid: String) : ConnectResult()
        data class Connected(val ssid: String) : ConnectResult()
        data class Failed(val ssid: String, val message: String) : ConnectResult()
        data class MissingPermission(val network: APNetwork, val permissions: List<String>) : ConnectResult()
        data class Disconnected(val ssid: String) : ConnectResult()
    }

    fun scanRequiredPermissions(): List<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add("android.permission.NEARBY_WIFI_DEVICES")
        }
        return perms
    }

    suspend fun isScanningEnabled(): Boolean = preferencesManager.scanningEnabled.first()

    suspend fun isFlagSecureEnabled(): Boolean = preferencesManager.flagSecure.first()

    private fun buildScanDiagnostics(wifiManager: WifiManager, requiredPermissions: List<String>): String {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val permissionState = requiredPermissions.joinToString(", ") {
            "$it=${ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED}"
        }
        return buildString {
            append("OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            append("\nWi-Fi enabled: ${wifiManager.isWifiEnabled}")
            append("\nScan always available: ${wifiManager.isScanAlwaysAvailable}")
            append("\nLocation services: ${locationManager?.isLocationEnabled ?: "unknown"}")
            append("\nPermissions: $permissionState")
            append("\nCached scan results: ${wifiManager.scanResults.size}")
        }
    }

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Load saved CSV path
            val savedPath = preferencesManager.csvPath.first()
            if (savedPath != null) {
                loadCsvFromPath(savedPath)
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }

            // Show only APs detected within the last scan window (2x scan interval)
            val interval = preferencesManager.scanIntervalMinutes.first()
            val detectedWindowMs = maxOf(2 * 60_000L, interval * 2 * 60_000L)
            
            // Observe network changes from database
            apNetworkDao.getAllNetworks().onEach { networks ->
                val now = System.currentTimeMillis()
                val detected = networks.filter {
                    it.lastSeen > 0 && now - it.lastSeen < detectedWindowMs
                }
                _uiState.update { it.copy(networks = detected, hasCsv = networks.isNotEmpty()) }
            }.launchIn(viewModelScope)
        }
    }

    fun onCsvSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            _csvLoadResult.value = null

            try {
                val file = withContext(Dispatchers.IO) { copyUriToFile(uri) }
                if (file != null) {
                    // Save the path for persistence
                    preferencesManager.setCsvPath(file.absolutePath)
                    loadCsvFromPath(file.absolutePath)
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Failed to access selected file") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Error: ${e.message}") }
                _csvLoadResult.value = CsvLoadResult.Error(
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

    fun loadCsvFromPath(path: String, password: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists()) {
                    _uiState.update { it.copy(isLoading = false, error = "File not found: $path") }
                    _csvLoadResult.value = CsvLoadResult.Error("File not found: $path")
                    return@withContext
                }
                if (file.length() > MAX_CSV_SIZE) {
                    _uiState.update { it.copy(isLoading = false) }
                    _csvLoadResult.value = CsvLoadResult.Error(
                        context.getString(R.string.csv_too_large)
                    )
                    return@withContext
                }

                when (EncryptionService.detectFormat(file)) {
                    EncryptionService.FileFormat.BIOMETRIC_ENCRYPTED -> {
                        _uiState.update { it.copy(isLoading = false, csvPath = path) }
                        _csvLoadResult.value = CsvLoadResult.NeedsBiometricDecrypt
                    }
                    EncryptionService.FileFormat.PASSWORD_ENCRYPTED -> {
                        // Legacy WFC1 file: password flow (never persisted)
                        if (password.isNullOrEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                            _csvLoadResult.value = CsvLoadResult.Error("Password required")
                            return@withContext
                        }
                        try {
                            val decryptedContent = EncryptionService.decryptToString(file, password)
                            parseAndStore(decryptedContent, path)
                        } catch (e: SecurityException) {
                            _uiState.update { it.copy(isLoading = false) }
                            _csvLoadResult.value = CsvLoadResult.Error("Password required")
                        }
                    }
                    EncryptionService.FileFormat.PLAINTEXT -> {
                        // Plaintext: load immediately, then prompt to secure the stored copy
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
            _uiState.update {
                it.copy(
                    isLoading = false,
                    csvPath = path,
                    hasCsv = true,
                    error = null
                )
            }
            // Stored copy is plaintext at this point; ask the user to secure it
            val needsEncryption =
                EncryptionService.detectFormat(File(path)) == EncryptionService.FileFormat.PLAINTEXT
            _csvLoadResult.value = CsvLoadResult.Success(
                result.networks.size,
                result.errors,
                needsEncryption
            )
        } else {
            _uiState.update {
                it.copy(isLoading = false, error = "No valid networks found in CSV")
            }
            _csvLoadResult.value = CsvLoadResult.Error(
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
            AppLog.e(TAG, "Decrypt: cannot create cipher", e)
            _csvLoadResult.value = CsvLoadResult.Error(
                context.getString(R.string.encrypt_key_invalidated),
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
                AppLog.e(TAG, "Decrypt: biometric decryption failed", e)
                _uiState.update { it.copy(isLoading = false) }
                _csvLoadResult.value = CsvLoadResult.Error(
                    "Decryption failed: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            }
        }
    }

    fun prepareBiometricEncrypt(): javax.crypto.Cipher? {
        return try {
            EncryptionService.createEncryptCipher()
        } catch (e: Exception) {
            AppLog.e(TAG, "Encrypt: cannot create cipher", e)
            _csvLoadResult.value = CsvLoadResult.Error(
                context.getString(R.string.encrypt_key_invalidated),
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
                _csvLoadResult.value = CsvLoadResult.Encrypted
                AppLog.i(TAG, "CSV re-encrypted with keystore key")
            } catch (e: Exception) {
                File(file.parentFile, "${file.name}.enc.tmp").delete()
                AppLog.e(TAG, "Encrypt: biometric encryption failed", e)
                _csvLoadResult.value = CsvLoadResult.Error(
                    "Encryption failed: ${e.message}",
                    AppLog.getStackTraceString(e)
                )
            }
        }
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
            _uiState.update { it.copy(csvPath = null, hasCsv = false, error = null) }
        }
    }

    fun clearCsv() {
        viewModelScope.launch {
            apNetworkDao.clearAll()
            preferencesManager.setCsvPath(null)
            _uiState.update { it.copy(networks = emptyList(), csvPath = null, hasCsv = false, error = null) }
        }
    }

    fun refresh() {
        _uiState.value.csvPath?.let { loadCsvFromPath(it) }
    }

    fun manualScan() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val requiredPermissions = scanRequiredPermissions()
            val diagnostics = wifiManager?.let { buildScanDiagnostics(it, requiredPermissions) }
                ?: "WifiManager service unavailable"

            if (wifiManager == null) {
                AppLog.e(TAG, "Manual scan: no WifiManager service")
                _manualScanResult.value = ManualScanResult.Error(
                    context.getString(R.string.scan_error_no_wifi_service),
                    diagnostics
                )
                return@launch
            }
            if (!wifiManager.isWifiEnabled) {
                AppLog.e(TAG, "Manual scan: Wi-Fi is off")
                _manualScanResult.value = ManualScanResult.Error(
                    context.getString(R.string.scan_error_wifi_off),
                    diagnostics
                )
                return@launch
            }
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                AppLog.e(TAG, "Manual scan: missing permissions $missing")
                _manualScanResult.value = ManualScanResult.Error(
                    context.getString(R.string.scan_error_permission, missing.joinToString(", ")),
                    diagnostics,
                    needsPermissions = missing
                )
                return@launch
            }
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager?.isLocationEnabled == false) {
                AppLog.e(TAG, "Manual scan: location services are off")
                _manualScanResult.value = ManualScanResult.Error(
                    context.getString(R.string.scan_error_location_off),
                    diagnostics,
                    needsLocation = true
                )
                return@launch
            }

            val known = apNetworkDao.getAllNetworksOnce()
            if (known.isEmpty()) {
                _manualScanResult.value = ManualScanResult.None()
                return@launch
            }

            _uiState.update { it.copy(isScanning = true) }
            var completed = false

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                    context.unregisterReceiver(this)
                    if (!completed) {
                        completed = true
                        viewModelScope.launch {
                            processScanResults(known, wifiManager.scanResults)
                        }
                    }
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )

            val started = try {
                wifiManager.startScan()
            } catch (e: SecurityException) {
                AppLog.e(TAG, "Manual scan: SecurityException on startScan", e)
                _uiState.update { it.copy(isScanning = false) }
                context.unregisterReceiver(receiver)
                val missingNow = requiredPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missingNow.isNotEmpty()) {
                    _manualScanResult.value = ManualScanResult.Error(
                        context.getString(R.string.scan_error_permission, missingNow.joinToString(", ")),
                        AppLog.getStackTraceString(e) + "\n\n" + diagnostics,
                        needsPermissions = missingNow
                    )
                } else {
                    _manualScanResult.value = ManualScanResult.Error(
                        context.getString(R.string.scan_error_failed) + " " +
                            context.getString(R.string.scan_error_throttled),
                        AppLog.getStackTraceString(e) + "\n\n" + diagnostics
                    )
                }
                return@launch
            }
            if (!started) {
                AppLog.e(TAG, "Manual scan: startScan() returned false")
                _uiState.update { it.copy(isScanning = false) }
                context.unregisterReceiver(receiver)
                val cached = wifiManager.scanResults
                if (cached.isNotEmpty()) {
                    processScanResults(
                        known,
                        cached,
                        context.getString(R.string.scan_note_cached) + "\n\n" + diagnostics
                    )
                } else {
                    _manualScanResult.value = ManualScanResult.Error(
                        context.getString(R.string.scan_error_failed) + " " +
                            context.getString(R.string.scan_error_throttled),
                        diagnostics
                    )
                }
                return@launch
            }

            AppLog.i(TAG, "Manual scan: startScan() accepted, waiting for results")
            scanTimeoutJob?.cancel()
            scanTimeoutJob = viewModelScope.launch {
                delay(10_000)
                if (!completed) {
                    completed = true
                    context.unregisterReceiver(receiver)
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val locationOff = locationManager?.isLocationEnabled == false
                    val cached = wifiManager.scanResults
                    AppLog.w(TAG, "Manual scan: timed out after 10s (locationOff=$locationOff, cached=${cached.size})")
                    if (locationOff) {
                        _manualScanResult.value = ManualScanResult.Error(
                            context.getString(R.string.scan_error_location_off),
                            diagnostics,
                            needsLocation = true
                        )
                    } else if (cached.isNotEmpty()) {
                        processScanResults(
                            known,
                            cached,
                            context.getString(R.string.scan_note_stale) + "\n\n" + diagnostics
                        )
                    } else {
                        processScanResults(known, emptyList())
                    }
                }
            }
        }
    }

    private suspend fun processScanResults(
        known: List<APNetwork>,
        scanResults: List<ScanResult>,
        note: String? = null
    ) {
        val knownByMac = known.associateBy { it.normalizedMac }
        val knownBySsid = known.associateBy { it.normalizedSsid }
        val matches = mutableListOf<ManualScanResult.Match>()
        val seen = mutableSetOf<String>()
        for (result in scanResults) {
            val bssid = APNetwork.normalizeMac(result.BSSID)
            val ssid = APNetwork.normalizeSsid(result.SSID.replace("\"", ""))
            val match = if (bssid.isNotEmpty() && bssid != "00:00:00:00:00:00") {
                knownByMac[bssid] ?: knownBySsid[ssid]
            } else if (ssid.isNotEmpty()) {
                knownBySsid[ssid]
            } else {
                null
            }
            if (match != null && seen.add(match.mac)) {
                val matchType = if (bssid.isNotEmpty() && bssid != "00:00:00:00:00:00") {
                    if (knownByMac[bssid] != null) {
                        if (match.normalizedSsid == ssid) 3 else 2
                    } else {
                        1
                    }
                } else {
                    1
                }
                matches.add(ManualScanResult.Match(match, result.level, matchType))
            }
        }
        matches.sortByDescending { it.signalStrength }
        val now = System.currentTimeMillis()
        for (match in matches) {
            apNetworkDao.updateDetection(match.network.mac, now, match.signalStrength, match.matchType)
        }
        _uiState.update { it.copy(isScanning = false) }
        _manualScanResult.value = if (matches.isEmpty()) {
            ManualScanResult.None(note)
        } else {
            ManualScanResult.Detected(matches, note)
        }
    }

    fun dismissManualScanResult() {
        _manualScanResult.value = null
    }

    fun connectToNetwork(network: APNetwork) {
        if (_connectResult.value is ConnectResult.Connecting) return
        viewModelScope.launch {
            val requiredPermissions = scanRequiredPermissions()
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                AppLog.e(TAG, "Connect: missing permissions $missing")
                _connectResult.value = ConnectResult.MissingPermission(network, missing)
                return@launch
            }

            _connectResult.value = ConnectResult.Connecting(network.ssid)
            AppLog.i(TAG, "Connecting to ${network.ssid} (${network.mac})")

            val specifier = try {
                WifiNetworkSpecifier.Builder()
                    .setSsid(network.ssid)
                    .apply {
                        try {
                            setBssid(MacAddress.fromString(network.mac))
                        } catch (_: IllegalArgumentException) {
                        }
                        if (network.password.isNotEmpty()) {
                            setWpa2Passphrase(network.password)
                        }
                    }
                    .build()
            } catch (e: Exception) {
                AppLog.e(TAG, "Connect: specifier build failed", e)
                _connectResult.value = ConnectResult.Failed(
                    network.ssid,
                    "Invalid network configuration: ${e.message}"
                )
                return@launch
            }

            // A specifier request must be a network REQUEST (not a callback registration)
            // for the system to present the connection prompt to the user.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectHandled = false
            connectTimeoutJob?.cancel()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    if (connectHandled) return
                    connectHandled = true
                    finishConnectAttempt()
                    AppLog.i(TAG, "Connected to ${network.ssid}")
                    _connectResult.value = ConnectResult.Connected(network.ssid)
                }

                override fun onUnavailable() {
                    if (connectHandled) return
                    connectHandled = true
                    finishConnectAttempt()
                    AppLog.w(TAG, "Could not connect to ${network.ssid}")
                    _connectResult.value = ConnectResult.Failed(
                        network.ssid,
                        context.getString(R.string.connect_error_unavailable)
                    )
                }

                override fun onLost(net: Network) {
                    AppLog.i(TAG, "Disconnected from ${network.ssid}")
                    _connectResult.value = ConnectResult.Disconnected(network.ssid)
                }
            }

            try {
                connectivityManager.requestNetwork(request, callback)
                AppLog.i(TAG, "requestNetwork submitted for ${network.ssid}")
            } catch (e: SecurityException) {
                AppLog.e(TAG, "Connect: SecurityException on requestNetwork", e)
                finishConnectAttempt()
                val missingNow = requiredPermissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missingNow.isNotEmpty()) {
                    _connectResult.value = ConnectResult.MissingPermission(network, missingNow)
                } else {
                    _connectResult.value = ConnectResult.Failed(
                        network.ssid,
                        "SecurityException: ${e.message}"
                    )
                }
                return@launch
            }
            activeConnectCallback = callback
            activeConnectivityManager = connectivityManager

            connectTimeoutJob = viewModelScope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (!connectHandled) {
                    connectHandled = true
                    finishConnectAttempt()
                    _connectResult.value = ConnectResult.Failed(
                        network.ssid,
                        context.getString(R.string.connect_error_timeout)
                    )
                }
            }
        }
    }

    private fun finishConnectAttempt() {
        val callback = activeConnectCallback
        if (callback != null) {
            try {
                activeConnectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        activeConnectCallback = null
        activeConnectivityManager = null
        connectTimeoutJob?.cancel()
    }

    fun cancelConnect() {
        viewModelScope.launch {
            connectHandled = true
            finishConnectAttempt()
            _connectResult.value = null
        }
    }

    fun dismissConnectResult() {
        _connectResult.value = null
    }

    private companion object {
        const val TAG = "WireFinderScan"
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val MAX_CSV_SIZE = 5L * 1024 * 1024
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}