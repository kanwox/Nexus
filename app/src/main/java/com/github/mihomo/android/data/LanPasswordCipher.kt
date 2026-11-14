package com.github.mihomo.android.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts the LAN proxy password at rest using AES-256-GCM.
 *
 * On Android devices, the AES key is hardware/TEE-backed inside `AndroidKeyStore`.
 * In pure JVM unit tests (or devices where `AndroidKeyStore` fails to initialize), it falls
 * back to a per-preferences random 256-bit AES-GCM key so encryption, authentication tags,
 * and legacy plaintext migration remain testable without an emulator.
 */
internal interface LanPasswordCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encoded: String): String?

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "nexus_lan_password_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val AES_KEY_BYTES = 32
        private const val FALLBACK_KEY_PREF = "allow_lan_password_k"
        private const val PREFIX_KEYSTORE = "ks1"
        private const val PREFIX_SOFTWARE = "sw1"

        fun defaultFor(prefs: SharedPreferences): LanPasswordCipher {
            val keystoreKey = obtainKeystoreKeyOrNull()
            return if (keystoreKey != null) {
                AesGcmCipher(PREFIX_KEYSTORE, keystoreKey, generateIvManually = false)
            } else {
                AesGcmCipher(PREFIX_SOFTWARE, obtainOrCreateFallbackKey(prefs), generateIvManually = true)
            }
        }

        internal fun softwareForTest(secretKeyBytes: ByteArray = ByteArray(AES_KEY_BYTES) { (it + 1).toByte() }): LanPasswordCipher {
            return AesGcmCipher(
                prefix = PREFIX_SOFTWARE,
                secretKey = SecretKeySpec(secretKeyBytes.copyOf(AES_KEY_BYTES), "AES"),
                generateIvManually = true
            )
        }

        private fun obtainKeystoreKeyOrNull(): SecretKey? {
            return try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                if (existing != null) return existing
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BYTES * 8)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
        }

        private fun obtainOrCreateFallbackKey(prefs: SharedPreferences): SecretKey {
            val encoder = Base64.getEncoder()
            val decoder = Base64.getDecoder()
            val existingB64 = prefs.getString(FALLBACK_KEY_PREF, null)
            val existingBytes = existingB64?.let {
                try {
                    decoder.decode(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (existingBytes != null && existingBytes.size == AES_KEY_BYTES) {
                return SecretKeySpec(existingBytes, "AES")
            }
            val generated = ByteArray(AES_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            try {
                prefs.edit { putString(FALLBACK_KEY_PREF, encoder.encodeToString(generated)) }
            } catch (_: Exception) {
                // Read-only preferences in sandboxed tests still work in-memory for the call.
            }
            return SecretKeySpec(generated, "AES")
        }
    }

    private class AesGcmCipher(
        private val prefix: String,
        private val secretKey: SecretKey,
        private val generateIvManually: Boolean
    ) : LanPasswordCipher {
        private val random = SecureRandom()

        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            if (generateIvManually) {
                val iv = ByteArray(GCM_IV_BYTES).also { random.nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val enc = Base64.getEncoder()
                return "$prefix:${enc.encodeToString(iv)}:${enc.encodeToString(cipherBytes)}"
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val enc = Base64.getEncoder()
                return "$prefix:${enc.encodeToString(iv)}:${enc.encodeToString(cipherBytes)}"
            }
        }

        override fun decrypt(encoded: String): String? {
            val parts = encoded.split(':')
            if (parts.size != 3 || parts[0] != prefix) return null
            return try {
                val dec = Base64.getDecoder()
                val iv = dec.decode(parts[1])
                val cipherBytes = dec.decode(parts[2])
                if (iv.isEmpty() || cipherBytes.isEmpty()) return null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}
