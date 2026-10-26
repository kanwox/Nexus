package com.github.mihomo.android.data

import org.junit.Assert.*
import org.junit.Test

class BackupManagerEncryptionTest {

    @Test
    fun `test plain json is not marked as encrypted`() {
        val sampleJson = """
            {
                "version": 1,
                "profiles": [],
                "scripts": [],
                "settings": {"app_font": "SYSTEM"}
            }
        """.trimIndent()

        val bytes = sampleJson.toByteArray(Charsets.UTF_8)
        assertFalse(BackupManager.isEncrypted(bytes))

        // Decrypting plain json without password returns the original text
        val decrypted = BackupManager.decrypt(bytes, null)
        assertEquals(sampleJson, decrypted)
    }

    @Test
    fun `test encryption roundtrip with valid password`() {
        val originalText = """
            {
                "version": 1,
                "profiles": [{"name": "HongKong", "content": "proxies: []"}],
                "scripts": [{"name": "rewrite.js", "code": "function main(c) { return c; }"}],
                "settings": {"tunnel_mode": "rule", "mixed_port": "7890"}
            }
        """.trimIndent()

        val password = "StrongSecretPassword#2026"
        val plainBytes = originalText.toByteArray(Charsets.UTF_8)

        val encryptedBytes = BackupManager.encrypt(plainBytes, password)
        assertTrue(BackupManager.isEncrypted(encryptedBytes))
        assertNotEquals(originalText, String(encryptedBytes, Charsets.UTF_8))

        // Decrypt with correct password
        val restoredText = BackupManager.decrypt(encryptedBytes, password)
        assertEquals(originalText, restoredText)
    }

    @Test
    fun `test decryption with wrong password fails`() {
        val originalText = "sensitive subscription tokens and lan passwords"
        val password = "CorrectPassword123"
        val encryptedBytes = BackupManager.encrypt(originalText.toByteArray(Charsets.UTF_8), password)

        try {
            BackupManager.decrypt(encryptedBytes, "WrongPassword999")
            fail("Expected exception when decrypting with incorrect password")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("password") == true || e.message?.contains("corrupted") == true)
        }
    }

    @Test
    fun `test decryption without password fails on encrypted data`() {
        val originalText = "sensitive subscription data"
        val password = "CorrectPassword123"
        val encryptedBytes = BackupManager.encrypt(originalText.toByteArray(Charsets.UTF_8), password)

        try {
            BackupManager.decrypt(encryptedBytes, null)
            fail("Expected exception when decrypting without password")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("password is required") == true)
        }
    }
}
