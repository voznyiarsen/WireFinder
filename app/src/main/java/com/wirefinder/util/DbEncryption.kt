package com.wirefinder.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object DbEncryption {

    private const val KEY_ALIAS = "wirefinder_db_key_v2"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val FIXED_BLOCK = "WireFinderDBKey4"
    private const val FIXED_IV_STRING = "0123456789ABCDEF"
    private val FIXED_IV = FIXED_IV_STRING.toByteArray(Charsets.US_ASCII)

    fun passphrase(): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(FIXED_IV))
        return cipher.doFinal(FIXED_BLOCK.toByteArray(Charsets.US_ASCII))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
