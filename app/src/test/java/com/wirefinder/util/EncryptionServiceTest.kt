package com.wirefinder.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(MockitoJUnitRunner::class)
class EncryptionServiceTest {

    @Test
    fun encryptDecrypt_roundTrip() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val inputFile = File(tempDir, "test_input_${System.currentTimeMillis()}.txt")
        val encryptedFile = File(tempDir, "test_encrypted_${System.currentTimeMillis()}.enc")
        val password = "test_password_123"

        try {
            val testContent = "00:11:22:33:44:55,TestNetwork,password123\naa:bb:cc:dd:ee:ff,AnotherAP,secret456"
            inputFile.writeText(testContent, StandardCharsets.UTF_8)

            // Encrypt
            EncryptionService.encryptFile(inputFile, encryptedFile, password)
            assertTrue(encryptedFile.exists())
            assertTrue(EncryptionService.isEncrypted(encryptedFile))

            // Decrypt to string
            val decryptedContent = EncryptionService.decryptToString(encryptedFile, password)
            assertEquals(testContent, decryptedContent)
        } finally {
            inputFile.delete()
            encryptedFile.delete()
            File(tempDir, "${encryptedFile.name}.decrypted.tmp").delete()
        }
    }

    @Test
    fun encryptDecrypt_wrongPassword_throwsException() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val inputFile = File(tempDir, "test_input_${System.currentTimeMillis()}.txt")
        val encryptedFile = File(tempDir, "test_encrypted_${System.currentTimeMillis()}.enc")
        val password = "correct_password"
        val wrongPassword = "wrong_password"

        try {
            val testContent = "00:11:22:33:44:55,TestNetwork,password123"
            inputFile.writeText(testContent, StandardCharsets.UTF_8)

            EncryptionService.encryptFile(inputFile, encryptedFile, password)

            try {
                EncryptionService.decryptToString(encryptedFile, wrongPassword)
                fail("Expected SecurityException for wrong password")
            } catch (e: SecurityException) {
                // Expected
            }
        } finally {
            inputFile.delete()
            encryptedFile.delete()
            File(tempDir, "${encryptedFile.name}.decrypted.tmp").delete()
        }
    }

    @Test
    fun isEncrypted_returnsTrueForEncryptedFile() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val inputFile = File(tempDir, "test_input_${System.currentTimeMillis()}.txt")
        val encryptedFile = File(tempDir, "test_encrypted_${System.currentTimeMillis()}.enc")
        val password = "test_password"

        try {
            inputFile.writeText("test content", StandardCharsets.UTF_8)
            assertFalse(EncryptionService.isEncrypted(inputFile))
            
            EncryptionService.encryptFile(inputFile, encryptedFile, password)
            assertTrue(EncryptionService.isEncrypted(encryptedFile))
        } finally {
            inputFile.delete()
            encryptedFile.delete()
            File(tempDir, "${encryptedFile.name}.decrypted.tmp").delete()
        }
    }

    @Test
    fun isEncrypted_returnsFalseForNonExistentFile() {
        val nonExistent = File(System.getProperty("java.io.tmpdir"), "nonexistent_${System.currentTimeMillis()}.enc")
        assertFalse(EncryptionService.isEncrypted(nonExistent))
    }

    @Test
    fun isEncrypted_returnsFalseForEmptyFile() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val emptyFile = File(tempDir, "empty_${System.currentTimeMillis()}.enc")
        try {
            emptyFile.createNewFile()
            assertFalse(EncryptionService.isEncrypted(emptyFile))
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun generateRandomKey_createsValidKey() {
        val key = EncryptionService.generateRandomKey()
        assertNotNull(key)
        assertEquals("AES", key.algorithm)
        assertEquals(256, key.encoded.size * 8)
    }

    @Test
    fun encryptDecryptString_roundTrip() {
        val key = EncryptionService.generateRandomKey()
        val plaintext = "Hello, World! This is a test string with special chars: äöüß 中文 🎉"
        
        val encrypted = EncryptionService.encryptString(plaintext, key)
        val decrypted = EncryptionService.decryptString(encrypted, key)
        
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encryptString_differentOutputEachTime() {
        val key = EncryptionService.generateRandomKey()
        val plaintext = "Test"
        
        val encrypted1 = EncryptionService.encryptString(plaintext, key)
        val encrypted2 = EncryptionService.encryptString(plaintext, key)
        
        // Should be different due to random IV
        assertNotEquals(encrypted1, encrypted2)
        
        // But both should decrypt to same plaintext
        assertEquals(plaintext, EncryptionService.decryptString(encrypted1, key))
        assertEquals(plaintext, EncryptionService.decryptString(encrypted2, key))
    }
}