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
 * Production builds exclusively encrypt using a hardware/TEE-backed key inside
 * `AndroidKeyStore` (cached process-wide so normal settings reads never incur
 * repeated keystore Binder IPC). Legacy `sw1:` ciphertexts written by older
 * builds can still be decrypted once for transparent migration to `ks1:`, after
 * which the legacy software key preference is erased.
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
        internal const val FALLBACK_KEY_PREF = "allow_lan_password_k"
        internal const val PREFIX_KEYSTORE = "ks1"
        internal const val PREFIX_SOFTWARE = "sw1"

        private val keystoreLock = Any()

        @Volatile
        private var cachedKeystoreKey: SecretKey? = null

        fun defaultFor(prefs: SharedPreferences): LanPasswordCipher =
            KeystoreFirstCipher(prefs)

        internal fun softwareForTest(
            secretKeyBytes: ByteArray = ByteArray(AES_KEY_BYTES) { (it + 1).toByte() }
        ): LanPasswordCipher {
            return AesGcmCipher(
                prefix = PREFIX_SOFTWARE,
                secretKey = SecretKeySpec(secretKeyBytes.copyOf(AES_KEY_BYTES), "AES"),
                generateIvManually = true
            )
        }

        private fun obtainKeystoreKeyOrNull(): SecretKey? {
            cachedKeystoreKey?.let { return it }
            return synchronized(keystoreLock) {
                cachedKeystoreKey?.let { return@synchronized it }
                val key = try {
                    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                    val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    if (existing != null) {
                        existing
                    } else {
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
                    }
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                if (key != null) {
                    cachedKeystoreKey = key
                }
                key
            }
        }

        private fun readLegacyFallbackKeyOrNull(prefs: SharedPreferences): SecretKey? {
            val existingB64 = prefs.getString(FALLBACK_KEY_PREF, null) ?: return null
            val existingBytes = try {
                Base64.getDecoder().decode(existingB64)
            } catch (_: IllegalArgumentException) {
                null
            }
            return if (existingBytes != null && existingBytes.size == AES_KEY_BYTES) {
                SecretKeySpec(existingBytes, "AES")
            } else {
                null
            }
        }
    }

    private class KeystoreFirstCipher(
        private val prefs: SharedPreferences
    ) : LanPasswordCipher {
        override fun encrypt(plaintext: String): String {
            val keystoreKey = obtainKeystoreKeyOrNull()
                ?: error("AndroidKeyStore is unavailable; refusing to store plaintext key")
            return AesGcmCipher(PREFIX_KEYSTORE, keystoreKey, generateIvManually = false).encrypt(plaintext)
        }

        override fun decrypt(encoded: String): String? {
            val prefix = encoded.substringBefore(':', "")
            return when (prefix) {
                PREFIX_KEYSTORE -> {
                    val key = obtainKeystoreKeyOrNull() ?: return null
                    AesGcmCipher(PREFIX_KEYSTORE, key, generateIvManually = false).decrypt(encoded)
                }
                PREFIX_SOFTWARE -> {
                    val legacyKey = readLegacyFallbackKeyOrNull(prefs) ?: return null
                    val decrypted = AesGcmCipher(PREFIX_SOFTWARE, legacyKey, generateIvManually = true)
                        .decrypt(encoded) ?: return null
                    // Transparently migrate legacy sw1 ciphertext to ks1 when keystore is available.
                    val ksKey = obtainKeystoreKeyOrNull()
                    if (ksKey != null) {
                        runCatching {
                            val reEncrypted = AesGcmCipher(PREFIX_KEYSTORE, ksKey, generateIvManually = false)
                                .encrypt(decrypted)
                            prefs.edit {
                                putString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, reEncrypted)
                                remove(FALLBACK_KEY_PREF)
                            }
                        }
                    }
                    decrypted
                }
                else -> null
            }
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
