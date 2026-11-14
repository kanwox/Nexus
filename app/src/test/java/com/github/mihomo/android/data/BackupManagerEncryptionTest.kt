package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

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

        // Decrypting plain json without password or with blank password returns the original text
        assertEquals(sampleJson, BackupManager.decrypt(bytes, null))
        assertEquals(sampleJson, BackupManager.decrypt(bytes, ""))
        assertEquals(sampleJson, BackupManager.decrypt(bytes, "   "))
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

        val magicLen = BackupManager.MAGIC_HEADER.size
        assertEquals(
            "NEXUS_ENC_V2\n",
            String(encryptedBytes.copyOfRange(0, magicLen), Charsets.UTF_8)
        )
        val recordedIterations = ByteBuffer.wrap(encryptedBytes, magicLen, 4).int
        assertEquals(210_000, recordedIterations)
        assertEquals(BackupManager.PBKDF2_ITERATIONS, recordedIterations)

        // Decrypt with correct password
        val restoredText = BackupManager.decrypt(encryptedBytes, password)
        assertEquals(originalText, restoredText)
    }

    @Test
    fun `test legacy v1 65536 iteration backup without aad remains decryptable`() {
        val originalText = """{"version":1,"profiles":[{"name":"LegacyNode","content":"proxies: []"}]}"""
        val password = "LegacyPassword#65536"
        val v1Encrypted = BackupManager.encryptLegacyV1(
            originalText.toByteArray(Charsets.UTF_8),
            password
        )

        assertTrue(BackupManager.isEncrypted(v1Encrypted))
        val v1Magic = "NEXUS_ENC_V1\n"
        assertEquals(v1Magic, String(v1Encrypted.copyOfRange(0, v1Magic.length), Charsets.UTF_8))

        // New decryptor must seamlessly decrypt legacy V1 format
        assertEquals(originalText, BackupManager.decrypt(v1Encrypted, password))

        // Wrong password on legacy V1 must still fail
        val wrongPwError = runCatching {
            BackupManager.decrypt(v1Encrypted, "WrongPassword")
        }.exceptionOrNull()
        assertNotNull(wrongPwError)
    }

    @Test
    fun `test aad tampering in v2 header fails decryption`() {
        val originalText = """{"version":1,"profiles":[{"name":"Tokyo","content":"proxies: []"}]}"""
        val password = "CorrectPassword!2026"
        val encryptedBytes = BackupManager.encrypt(
            originalText.toByteArray(Charsets.UTF_8),
            password
        )

        // 1. Tamper with the version byte in the magic header (part of AAD)
        val tamperedVersion = encryptedBytes.clone()
        tamperedVersion[11] = '3'.code.toByte()
        assertTrue(BackupManager.isEncrypted(tamperedVersion))
        val versionErr = runCatching {
            BackupManager.decrypt(tamperedVersion, password)
        }.exceptionOrNull()
        assertNotNull("Tampering with version header byte must fail GCM AAD check", versionErr)

        // 2. Supplying a mismatched AAD must fail GCM tag verification even with identical key/IV
        val mismatchedAadErr = runCatching {
            BackupManager.decrypt(
                encryptedBytes,
                password,
                aad = "TAMPERED_AAD_HEADER".toByteArray(Charsets.UTF_8)
            )
        }.exceptionOrNull()
        assertNotNull("Mismatched AAD must fail AES-GCM authentication", mismatchedAadErr)

        // 3. Downgrading magic header from V2 to V1 must fail
        val downgraded = encryptedBytes.clone()
        System.arraycopy(
            BackupManager.LEGACY_MAGIC_HEADER,
            0,
            downgraded,
            0,
            BackupManager.LEGACY_MAGIC_HEADER.size
        )
        val downgradeErr = runCatching {
            BackupManager.decrypt(downgraded, password)
        }.exceptionOrNull()
        assertNotNull("Downgrading V2 header to V1 must fail decryption", downgradeErr)

        // 4. Tampering with header salt/iv bytes fails decryption
        val tamperedSalt = encryptedBytes.clone()
        tamperedSalt[BackupManager.MAGIC_HEADER.size + 4] =
            (tamperedSalt[BackupManager.MAGIC_HEADER.size + 4].toInt() xor 0xFF).toByte()
        assertNotNull(runCatching { BackupManager.decrypt(tamperedSalt, password) }.exceptionOrNull())
    }

    @Test
    fun `test out of bounds pbkdf2 iteration count in v2 header is rejected`() {
        val encryptedBytes = BackupManager.encrypt(
            "payload".toByteArray(Charsets.UTF_8),
            "password123"
        )
        val magicLen = BackupManager.MAGIC_HEADER.size

        val zeroIter = encryptedBytes.clone()
        ByteBuffer.wrap(zeroIter, magicLen, 4).putInt(0)
        assertNotNull(runCatching { BackupManager.decrypt(zeroIter, "password123") }.exceptionOrNull())

        val excessiveIter = encryptedBytes.clone()
        ByteBuffer.wrap(excessiveIter, magicLen, 4).putInt(50_000_000)
        assertNotNull(runCatching { BackupManager.decrypt(excessiveIter, "password123") }.exceptionOrNull())
    }

    @Test
    fun `test multi chunk streaming encryption and decryption roundtrip`() {
        // Build a payload (> 64 KB) that spans multiple 16 KB streaming chunks
        val largeJson = buildString {
            append("""{"version":1,"profiles":[""")
            repeat(800) { idx ->
                if (idx > 0) append(',')
                append("""{"name":"Node-$idx","content":"proxies: [{name: n$idx, type: ss, server: example.com, port: 443}]"}""")
            }
            append("""]}""")
        }
        assertTrue(largeJson.length > 64 * 1024)
        val encrypted = BackupManager.encrypt(largeJson.toByteArray(Charsets.UTF_8), "stream-pw")
        assertEquals(largeJson, BackupManager.decrypt(encrypted, "stream-pw"))
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
