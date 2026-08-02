package com.wirefinder.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

class BiometricPromptHelper(
    private val activity: FragmentActivity,
    private val onSuccess: (Cipher) -> Unit,
    private val onCancelled: () -> Unit,
    private val onError: (String) -> Unit
) {

    fun authenticate(title: String, subtitle: String, cipher: Cipher) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let { onSuccess(it) }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancelled()
                    } else {
                        onError(errString.toString())
                    }
                }
            }
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
