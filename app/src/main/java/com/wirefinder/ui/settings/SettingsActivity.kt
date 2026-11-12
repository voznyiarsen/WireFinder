package com.wirefinder.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wirefinder.R
import com.wirefinder.databinding.ActivitySettingsBinding
import com.wirefinder.util.ErrorDialog
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.onCsvSelected(it) }
    }

    private val pickKeePassLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.onKeePassSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_settings)

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUI(state)
                    }
                }
                launch {
                    viewModel.loadResult.collect { result ->
                        result?.let { handleLoadResult(it) }
                    }
                }
                launch {
                    viewModel.encryptPending.collect { pending ->
                        if (pending) promptEncryptCsv()
                    }
                }
            }
        }
    }

    private fun updateUI(state: SettingsViewModel.UiState) {
        binding.apply {
            // CSV Section
            state.csvPath?.let { path ->
                tvCurrentCsv.text = getString(R.string.pref_current_csv, File(path).name)
                tvCurrentCsv.visibility = android.view.View.VISIBLE
            } ?: run {
                tvCurrentCsv.text = "No CSV file selected"
                tvCurrentCsv.visibility = android.view.View.VISIBLE
            }

            val intervalText = state.scanIntervalMinutes.toString()
            if (!etScanInterval.hasFocus() && etScanInterval.text.toString() != intervalText) {
                etScanInterval.setText(intervalText)
            }
            if (swEnableScanning.isChecked != state.scanningEnabled) {
                swEnableScanning.isChecked = state.scanningEnabled
            }

            if (swFlagSecure.isChecked != state.flagSecureEnabled) {
                swFlagSecure.isChecked = state.flagSecureEnabled
            }

            progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun handleLoadResult(result: SettingsViewModel.LoadResult) {
        when (result) {
            is SettingsViewModel.LoadResult.Success -> {
                Toast.makeText(this, getString(R.string.toast_csv_loaded, result.count), Toast.LENGTH_SHORT).show()
                if (result.errors.isNotEmpty()) {
                    ErrorDialog.show(
                        this,
                        getString(R.string.csv_load_issues_title),
                        getString(R.string.toast_csv_loaded, result.count) + "\n\n" +
                            result.errors.joinToString("\n")
                    )
                }
            }
            is SettingsViewModel.LoadResult.Error -> {
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
            is SettingsViewModel.LoadResult.NeedsBiometricDecrypt -> {
                promptDecryptCsv()
            }
            is SettingsViewModel.LoadResult.Encrypted -> {
                Toast.makeText(this, R.string.toast_csv_encrypted, Toast.LENGTH_SHORT).show()
            }
            is SettingsViewModel.LoadResult.DataCleared -> {
                Toast.makeText(this, R.string.toast_csv_cleared, Toast.LENGTH_SHORT).show()
            }
            is SettingsViewModel.LoadResult.NeedsPassword -> {
                showPasswordDialog(forKeePass = true)
            }
        }
        viewModel.dismissLoadResult()
    }

    private fun promptDecryptCsv() {
        val cipher = viewModel.prepareBiometricDecrypt() ?: return
        com.wirefinder.util.BiometricPromptHelper(
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
        com.wirefinder.util.BiometricPromptHelper(
            this,
            onSuccess = { viewModel.encryptWithBiometric(it) },
            onCancelled = {
                viewModel.clearEncryptPending()
                warnPlaintextCsv()
            },
            onError = { message ->
                ErrorDialog.show(this, getString(R.string.biometric_failed_title), message)
                viewModel.clearEncryptPending()
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

    private fun showPasswordDialog(forKeePass: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.password_input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_password_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotEmpty()) {
                    if (forKeePass) {
                        viewModel.importKeePass(password)
                    } else {
                        viewModel.retryWithPassword(password)
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setupClickListeners() {
        binding.btnChangeCsv.setOnClickListener {
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

        binding.btnImportKeePass.setOnClickListener {
            pickKeePassLauncher.launch(
                arrayOf(
                    "application/x-keepass2",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

        binding.btnClearData.setOnClickListener {
            showClearDataConfirmation()
        }

        binding.etScanInterval.doOnTextChanged { text, _, _, _ ->
            text.toString().toIntOrNull()?.let { viewModel.setScanInterval(it) }
        }

        binding.etScanInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.etScanInterval.text.toString().toIntOrNull()
                    ?.coerceIn(
                        PreferencesManager.MIN_SCAN_INTERVAL,
                        PreferencesManager.MAX_SCAN_INTERVAL
                    )
                    ?: PreferencesManager.DEFAULT_SCAN_INTERVAL
                binding.etScanInterval.setText(value.toString())
                viewModel.setScanInterval(value)
            }
        }

        binding.swEnableScanning.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setScanningEnabled(isChecked)
        }

        binding.swFlagSecure.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFlagSecure(isChecked)
        }
    }

    private fun showClearDataConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Data")
            .setMessage("This will remove the CSV file, all network data, and reset settings. This action cannot be undone.")
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                viewModel.clearAllData()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}