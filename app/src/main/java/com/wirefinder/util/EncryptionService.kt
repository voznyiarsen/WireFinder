package com.wirefinder.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionService {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wirefinder_csv_key"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100000
    private const val KEY_LENGTH = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
    private const val SALT_LENGTH = 16
    private const val MAGIC_LENGTH = 4
    private const val MAGIC_PASSWORD = "WFC1"
    private const val MAGIC_BIOMETRIC = "WFC2"

    enum class FileFormat { PLAINTEXT, PASSWORD_ENCRYPTED, BIOMETRIC_ENCRYPTED }

    /**
     * Detects the format of a file: plaintext, legacy password-encrypted (WFC1),
     * or biometric/keystore-encrypted (WFC2).
     */
    fun detectFormat(file: File): FileFormat {
        if (!file.exists() || file.length() < MAGIC_LENGTH + GCM_IV_LENGTH) return FileFormat.PLAINTEXT
        val inputStream = FileInputStream(file)
        try {
            val magic = ByteArray(MAGIC_LENGTH)
            if (inputStream.read(magic) != MAGIC_LENGTH) return FileFormat.PLAINTEXT
            return when {
                magic.contentEquals(MAGIC_BIOMETRIC.toByteArray(StandardCharsets.US_ASCII)) ->
                    FileFormat.BIOMETRIC_ENCRYPTED
                magic.contentEquals(MAGIC_PASSWORD.toByteArray(StandardCharsets.US_ASCII)) &&
                    file.length() >= MAGIC_LENGTH + SALT_LENGTH + GCM_IV_LENGTH ->
                    FileFormat.PASSWORD_ENCRYPTED
                else -> FileFormat.PLAINTEXT
            }
        } catch (e: Exception) {
            return FileFormat.PLAINTEXT
        } finally {
            inputStream.close()
        }
    }

    // ---------------------------------------------------------------------
    // Biometric / Keystore key (WFC2)
    // ---------------------------------------------------------------------

    /**
     * Returns the existing keystore key or generates a new one. The key is
     * authorized for use only after the user authenticates with a strong
     * biometric or the device credential (PIN/pattern/password).
     */
    fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_LENGTH)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * Creates an encrypt cipher bound to the keystore key. The cipher must be
     * authenticated via BiometricPrompt (CryptoObject) before use.
     */
    fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    /**
     * Creates a decrypt cipher for a WFC2 file using the IV stored in the file.
     * Must be authenticated via BiometricPrompt (CryptoObject) before use.
     */
    fun createDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH * 8, iv))
        return cipher
    }

    /**
     * Reads the IV from a WFC2 file header: [magic (4 bytes)][iv (12 bytes)].
     */
    fun readBiometricIv(file: File): ByteArray {
        val inputStream = FileInputStream(file)
        try {
            val magic = ByteArray(MAGIC_LENGTH)
            if (inputStream.read(magic) != MAGIC_LENGTH ||
                !magic.contentEquals(MAGIC_BIOMETRIC.toByteArray(StandardCharsets.US_ASCII))
            ) {
                throw IllegalArgumentException("Invalid encrypted file: missing WFC2 magic header")
            }
            val iv = ByteArray(GCM_IV_LENGTH)
            if (inputStream.read(iv) != GCM_IV_LENGTH) {
                throw IllegalArgumentException("Invalid encrypted file: missing IV")
            }
            return iv
        } finally {
            inputStream.close()
        }
    }

    /**
     * Encrypts in-memory content to WFC2 format with the given (authenticated) cipher.
     * Used to migrate a legacy WFC1 file without ever writing plaintext to disk.
     * Output format: [magic "WFC2" (4 bytes)][iv (12 bytes)][ciphertext][tag (16 bytes)]
     */
    fun encryptContentBiometric(content: String, outputFile: File, cipher: Cipher) {
        val iv = cipher.iv
        val outputStream = FileOutputStream(outputFile)
        try {
            outputStream.write(MAGIC_BIOMETRIC.toByteArray(StandardCharsets.US_ASCII))
            outputStream.write(iv)
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val encrypted = cipher.update(bytes)
            if (encrypted != null) outputStream.write(encrypted)
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) outputStream.write(finalBytes)
        } finally {
            outputStream.close()
        }
    }

    /**
     * Encrypts a plaintext file to WFC2 format with the given (authenticated) cipher.
     * Output format: [magic "WFC2" (4 bytes)][iv (12 bytes)][ciphertext][tag (16 bytes)]
     */
    fun encryptFileBiometric(inputFile: File, outputFile: File, cipher: Cipher) {
        val iv = cipher.iv
        val inputStream = FileInputStream(inputFile)
        val outputStream = FileOutputStream(outputFile)
        try {
            outputStream.write(MAGIC_BIOMETRIC.toByteArray(StandardCharsets.US_ASCII))
            outputStream.write(iv)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val encrypted = cipher.update(buffer, 0, bytesRead)
                if (encrypted != null) outputStream.write(encrypted)
            }
            val finalBytes = cipher.doFinal()
            outputStream.write(finalBytes)
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }

    /**
     * Decrypts a WFC2 file (after the header) to a string with the given
     * (authenticated) cipher.
     */
    fun decryptBiometricToString(file: File, cipher: Cipher): String {
        val inputStream = FileInputStream(file)
        try {
            if (inputStream.skip(MAGIC_LENGTH.toLong()) != MAGIC_LENGTH.toLong()) {
                throw IllegalArgumentException("Invalid encrypted file: missing magic header")
            }
            if (inputStream.skip(GCM_IV_LENGTH.toLong()) != GCM_IV_LENGTH.toLong()) {
                throw IllegalArgumentException("Invalid encrypted file: missing IV")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val decrypted = cipher.update(buffer, 0, bytesRead)
                if (decrypted != null) output.write(decrypted)
            }
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) output.write(finalBytes)
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw SecurityException("Decryption failed: corrupted file or key mismatch", e)
        } finally {
            inputStream.close()
        }
    }

    // ---------------------------------------------------------------------
    // Legacy password encryption (WFC1)
    // ---------------------------------------------------------------------

    /**
     * Encrypts a file using AES-GCM with a password-derived key.
     * Output format: [magic (4 bytes)][salt (16 bytes)][iv (12 bytes)][ciphertext][tag (16 bytes)]
     */
    fun encryptFile(inputFile: File, outputFile: File, password: String) {
        val salt = generateSalt()
        val key = deriveKey(password, salt)
        val iv = generateIv()

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, iv))

        val inputStream = FileInputStream(inputFile)
        val outputStream = FileOutputStream(outputFile)

        try {
            // Write magic header, salt and IV first
            outputStream.write(MAGIC_PASSWORD.toByteArray(StandardCharsets.US_ASCII))
            outputStream.write(salt)
            outputStream.write(iv)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val encrypted = cipher.update(buffer, 0, bytesRead)
                if (encrypted != null) {
                    outputStream.write(encrypted)
                }
            }

            // Final block includes authentication tag
            val finalBytes = cipher.doFinal()
            outputStream.write(finalBytes)
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }

    /**
     * Decrypts a file encrypted with encryptFile.
     * Returns a FileInputStream for the decrypted content.
     */
    fun decryptFile(encryptedFile: File, password: String): FileInputStream {
        val inputStream = FileInputStream(encryptedFile)

        // Verify magic header
        val magic = ByteArray(MAGIC_LENGTH)
        if (inputStream.read(magic) != MAGIC_LENGTH ||
            !magic.contentEquals(MAGIC_PASSWORD.toByteArray(StandardCharsets.US_ASCII))
        ) {
            inputStream.close()
            throw IllegalArgumentException("Invalid encrypted file: missing magic header")
        }

        // Read salt (16 bytes)
        val salt = ByteArray(SALT_LENGTH)
        if (inputStream.read(salt) != SALT_LENGTH) {
            inputStream.close()
            throw IllegalArgumentException("Invalid encrypted file: missing salt")
        }

        // Read IV (12 bytes)
        val iv = ByteArray(GCM_IV_LENGTH)
        if (inputStream.read(iv) != GCM_IV_LENGTH) {
            inputStream.close()
            throw IllegalArgumentException("Invalid encrypted file: missing IV")
        }

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, iv))

        // Create a temporary file for decrypted content
        val tempFile = File(encryptedFile.parentFile, "${encryptedFile.name}.decrypted.tmp")
        val outputStream = FileOutputStream(tempFile)

        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val decrypted = cipher.update(buffer, 0, bytesRead)
                if (decrypted != null) {
                    outputStream.write(decrypted)
                }
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) {
                outputStream.write(finalBytes)
            }
        } catch (e: Exception) {
            outputStream.close()
            tempFile.delete()
            inputStream.close()
            throw SecurityException("Decryption failed: wrong password or corrupted file", e)
        } finally {
            inputStream.close()
            outputStream.close()
        }

        // Schedule temp file for deletion on JVM exit (fallback if caller forgets)
        tempFile.deleteOnExit()

        // Returned stream deletes the plaintext temp file when closed
        return object : FileInputStream(tempFile) {
            override fun close() {
                super.close()
                tempFile.delete()
            }
        }
    }

    /**
     * Decrypts file content to a string (for CSV parsing).
     */
    fun decryptToString(encryptedFile: File, password: String): String {
        val inputStream = decryptFile(encryptedFile, password)
        return try {
            inputStream.readBytes().toString(StandardCharsets.UTF_8)
        } finally {
            inputStream.close()
            // Clean up temp file
            File(encryptedFile.parentFile, "${encryptedFile.name}.decrypted.tmp").delete()
        }
    }

    /**
     * Checks if a file appears to be encrypted (has a WireFinder magic header).
     */
    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_LENGTH + SALT_LENGTH + GCM_IV_LENGTH) {
            return false
        }
        val inputStream = FileInputStream(file)
        try {
            val magic = ByteArray(MAGIC_LENGTH)
            if (inputStream.read(magic) != MAGIC_LENGTH) {
                return false
            }
            return magic.contentEquals(MAGIC_PASSWORD.toByteArray(StandardCharsets.US_ASCII)) ||
                magic.contentEquals(MAGIC_BIOMETRIC.toByteArray(StandardCharsets.US_ASCII))
        } catch (e: Exception) {
            return false
        } finally {
            inputStream.close()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, KEY_ALGORITHM)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun generateIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        return iv
    }

    /**
     * Generates a random encryption key (for programmatic use without password).
     */
    fun generateRandomKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM)
        generator.init(KEY_LENGTH)
        return generator.generateKey()
    }

    /**
     * Encrypts a string with a key (for testing).
     */
    fun encryptString(plaintext: String, key: SecretKey): String {
        val iv = generateIv()
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        // Combine IV + encrypted (which includes tag)
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a string with a key (for testing).
     */
    fun decryptString(encryptedBase64: String, key: SecretKey): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
        val encrypted = ByteArray(combined.size - GCM_IV_LENGTH)
        System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH * 8, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, StandardCharsets.UTF_8)
    }
}