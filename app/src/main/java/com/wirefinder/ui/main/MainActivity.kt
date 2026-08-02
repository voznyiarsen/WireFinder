package com.wirefinder.ui.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirefinder.R
import com.wirefinder.databinding.ActivityMainBinding
import com.wirefinder.ui.settings.SettingsActivity
import com.wirefinder.util.BiometricPromptHelper
import com.wirefinder.util.ErrorDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.onCsvSelected(it) }
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions granted, scanning service can start
            checkLocationServices()
            performPendingAction()
            maybeStartScanService()
        } else {
            pendingNetwork = null
            pendingScan = false
            permissions.forEach { (permission, granted) ->
                if (!granted) {
                    val denials = (permissionDenialCounts[permission] ?: 0) + 1
                    permissionDenialCounts[permission] = denials
                    // After a second denial Android 11+ never shows the system dialog again;
                    // the only option left is the app settings screen.
                    if (denials >= 2) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        })
                    }
                }
            }
        }
    }

    private val permissionDenialCounts = mutableMapOf<String, Int>()

    private var pendingNetwork: com.wirefinder.data.APNetwork? = null
    private var pendingScan = false

    private fun performPendingAction() {
        pendingNetwork?.let { network ->
            pendingNetwork = null
            viewModel.connectToNetwork(network)
        }
        if (pendingScan) {
            pendingScan = false
            viewModel.manualScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val adapter = NetworkAdapter()
        adapter.onNetworkClick = { network -> viewModel.connectToNetwork(network) }
        adapter.onShareClick = { network -> showQrDialog(network) }
        binding.recyclerView.adapter = adapter

        setupObservers()
        setupClickListeners()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()
        
        // ACCESS_FINE_LOCATION required for Wi-Fi scanning on all Android versions
        // and for the location-type foreground service on Android 14+
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // NEARBY_WIFI_DEVICES required for WifiNetworkSpecifier on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add("android.permission.NEARBY_WIFI_DEVICES")
            }
        }

        // POST_NOTIFICATIONS for Android 13+ notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            checkLocationServices()
            maybeStartScanService()
        }
    }

    private fun maybeStartScanService() {
        lifecycleScope.launch {
            if (viewModel.isScanningEnabled()) {
                com.wirefinder.service.WifiScanForegroundService.start(this@MainActivity)
            }
        }
    }

    private fun checkLocationServices() {
        val locationManager = getSystemService(LocationManager::class.java)
        if (!locationManager.isLocationEnabled) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_location_required_title)
                .setMessage(R.string.dialog_location_required_message)
                .setPositiveButton(R.string.btn_enable_location) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.btn_later, null)
                .show()
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.csvLoadResult.collect { result ->
                result?.let { handleLoadResult(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.manualScanResult.collect { result ->
                result?.let { handleScanResult(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.connectResult.collect { result ->
                result?.let { handleConnectResult(it) }
            }
        }
    }

    private fun handleScanResult(result: MainViewModel.ManualScanResult) {
        when (result) {
            is MainViewModel.ManualScanResult.Detected -> {
                val message = getString(R.string.scan_detected_count, result.matches.size)
                if (result.note != null) {
                    ErrorDialog.show(this, getString(R.string.scan_manual_title), message + "\n\n" + result.note)
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
            is MainViewModel.ManualScanResult.None -> {
                if (result.note != null) {
                    ErrorDialog.show(
                        this,
                        getString(R.string.scan_manual_title),
                        getString(R.string.scan_result_none) + "\n\n" + result.note
                    )
                } else {
                    Toast.makeText(this, R.string.scan_result_none, Toast.LENGTH_SHORT).show()
                }
            }
            is MainViewModel.ManualScanResult.Error -> {
                val details = result.details?.let { "\n\n$it" } ?: ""
                if (result.needsPermissions.isNotEmpty()) {
                    pendingScan = true
                    requestPermissionsLauncher.launch(result.needsPermissions.toTypedArray())
                } else if (result.needsLocation) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_location_required_title)
                        .setMessage(result.message + details)
                        .setPositiveButton(R.string.btn_enable_location) { _, _ ->
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                        .setNegativeButton(R.string.btn_later, null)
                        .show()
                } else {
                    ErrorDialog.show(
                        this,
                        getString(R.string.scan_manual_title),
                        result.message + details
                    )
                }
            }
        }
        viewModel.dismissManualScanResult()
    }

    private var connectingDialog: androidx.appcompat.app.AlertDialog? = null

    private fun handleConnectResult(result: MainViewModel.ConnectResult) {
        connectingDialog?.dismiss()
        connectingDialog = null
        when (result) {
            is MainViewModel.ConnectResult.Connecting -> {
                connectingDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.connect_progress_title)
                    .setMessage(getString(R.string.connect_progress_message, result.ssid))
                    .setCancelable(true)
                    .setOnCancelListener { viewModel.cancelConnect() }
                    .show()
            }
            is MainViewModel.ConnectResult.Connected -> {
                Toast.makeText(
                    this,
                    getString(R.string.connect_success, result.ssid),
                    Toast.LENGTH_LONG
                ).show()
            }
            is MainViewModel.ConnectResult.Failed -> {
                ErrorDialog.show(
                    this,
                    getString(R.string.connect_failed_title),
                    getString(R.string.connect_failed_message, result.ssid) + "\n\n" + result.message
                )
            }
            is MainViewModel.ConnectResult.MissingPermission -> {
                pendingNetwork = result.network
                requestPermissionsLauncher.launch(result.permissions.toTypedArray())
            }
            is MainViewModel.ConnectResult.Disconnected -> {
                Toast.makeText(
                    this,
                    getString(R.string.connect_disconnected, result.ssid),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        viewModel.dismissConnectResult()
    }

    private fun showQrDialog(network: com.wirefinder.data.APNetwork) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_share_qr, null)
        val qrImage = dialogView.findViewById<android.widget.ImageView>(R.id.qrImage)
        val qrSsid = dialogView.findViewById<android.widget.TextView>(R.id.qrSsid)
        val qrPassword = dialogView.findViewById<android.widget.TextView>(R.id.qrPassword)
        val wifiString = com.wirefinder.util.QrCode.wifiQrString(network.ssid, network.password)

        qrSsid.text = network.ssid
        qrPassword.text = if (network.password.isEmpty()) {
            getString(R.string.qr_open_network)
        } else {
            getString(R.string.qr_password_label, network.password)
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val bitmap = com.wirefinder.util.QrCode.generate(wifiString)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                qrImage.setImageBitmap(bitmap)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_dialog_title)
            .setView(dialogView)
            .setNeutralButton(R.string.btn_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(network.ssid, wifiString)
                )
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.btn_share) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, wifiString)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share_network)))
            }
            .show()
        lifecycleScope.launch {
            if (viewModel.isFlagSecureEnabled()) {
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private fun updateUI(state: MainViewModel.UiState) {
        binding.apply {
            // Show only currently detected networks; empty state otherwise
            if (state.hasCsv) {
                swipeRefreshLayout.visibility = View.VISIBLE
                toolbar.subtitle = state.csvPath?.let { File(it).name } ?: ""
                if (state.networks.isNotEmpty()) {
                    emptyStateLayout.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.GONE
                    emptyStateLayout.visibility = View.VISIBLE
                    tvEmptyTitle.text = getString(R.string.empty_no_detected)
                    tvEmptyDesc.text = getString(R.string.empty_no_detected_desc)
                }
            } else {
                emptyStateLayout.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                swipeRefreshLayout.visibility = View.GONE
                toolbar.subtitle = ""
                tvEmptyTitle.text = getString(R.string.empty_no_csv)
                tvEmptyDesc.text = getString(R.string.empty_no_csv_desc)
            }

            // Update loading state
            swipeRefreshLayout.isRefreshing = state.isLoading || state.isScanning
            
            // Show error if any
            state.error?.let {
                ErrorDialog.show(
                    this@MainActivity,
                    getString(R.string.error_details_title),
                    it
                )
                viewModel.dismissError()
            }

            // Update adapter
            (recyclerView.adapter as? NetworkAdapter)?.submitList(state.networks)
        }
    }

    private fun handleLoadResult(result: MainViewModel.CsvLoadResult) {
        when (result) {
            is MainViewModel.CsvLoadResult.Success -> {
                if (result.errors.isNotEmpty()) {
                    ErrorDialog.show(
                        this,
                        getString(R.string.csv_load_issues_title),
                        getString(R.string.toast_csv_loaded, result.count) + "\n\n" +
                            result.errors.joinToString("\n")
                    )
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_csv_loaded, result.count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (result.needsEncryption) {
                    promptEncryptCsv()
                }
            }
            is MainViewModel.CsvLoadResult.NeedsBiometricDecrypt -> {
                promptDecryptCsv()
            }
            is MainViewModel.CsvLoadResult.Encrypted -> {
                Toast.makeText(this, R.string.toast_csv_encrypted, Toast.LENGTH_SHORT).show()
            }
            is MainViewModel.CsvLoadResult.Error -> {
                if (result.message == "Password required") {
                    showPasswordDialog()
                } else {
                    val details = result.details?.let { "\n\n$it" } ?: ""
                    ErrorDialog.show(
                        this,
                        getString(R.string.error_details_title),
                        result.message + details
                    )
                }
            }
        }
    }

    private fun promptDecryptCsv() {
        val cipher = viewModel.prepareBiometricDecrypt() ?: return
        BiometricPromptHelper(
            this,
            onSuccess = { viewModel.decryptWithBiometric(it) },
            onCancelled = {
                Toast.makeText(this, R.string.biometric_cancelled, Toast.LENGTH_SHORT).show()
            },
            onError = { message ->
                ErrorDialog.show(this, getString(R.string.biometric_failed_title), message)
            }
        ).authenticate(
            getString(R.string.biometric_decrypt_title),
            getString(R.string.biometric_decrypt_subtitle),
            cipher
        )
    }

    private fun promptEncryptCsv() {
        val cipher = viewModel.prepareBiometricEncrypt() ?: return
        BiometricPromptHelper(
            this,
            onSuccess = { viewModel.encryptWithBiometric(it) },
            onCancelled = {
                warnPlaintextCsv()
            },
            onError = { message ->
                ErrorDialog.show(this, getString(R.string.biometric_failed_title), message)
                warnPlaintextCsv()
            }
        ).authenticate(
            getString(R.string.biometric_encrypt_title),
            getString(R.string.biometric_encrypt_subtitle),
            cipher
        )
    }

    private fun warnPlaintextCsv() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plaintext_warning_title)
            .setMessage(R.string.plaintext_warning_message)
            .setPositiveButton(R.string.btn_encrypt_again) { _, _ -> promptEncryptCsv() }
            .setNeutralButton(R.string.btn_delete_file) { _, _ -> viewModel.deleteStoredCsv() }
            .setNegativeButton(R.string.btn_keep_plaintext, null)
            .show()
    }

    private fun showPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.password_input)
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_password_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotEmpty()) {
                    viewModel.retryWithPassword(password)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setupClickListeners() {
        binding.btnSelectCsv.setOnClickListener {
            pickCsvLauncher.launch(
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/vnd.ms-excel",
                    "application/octet-stream"
                )
            )
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.manualScan()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onDestroy() {
        connectingDialog?.dismiss()
        connectingDialog = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear_csv -> {
                viewModel.clearCsv()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage("WireFinder v1.0\n\nDetects known Wi-Fi networks from encrypted CSV files.")
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }
}