package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the backup hardening: encryption reachability, untrusted-input validation
 * and the LAN-sharing rule.
 *
 * The restore decisions are exposed as pure helpers ([BackupManager.sanitizeRestoredUrl]
 * and [BackupManager.decideLanRestore]) so they can be verified without an Android
 * `Context` (the unit tests run on a plain JVM).
 */
class BackupManagerSecurityTest {

    // ------------------------------------------------------------------
    // 1. Encrypted backup round trip
    // ------------------------------------------------------------------

    @Test
    fun `encrypted payload starts with the magic header and hides the token`() {
        val plain = """{"version":1,"profiles":[{"name":"HK","url":"https://example.com/sub?token=SECRET-TOKEN","content":"proxies: []"}]}"""
        val encrypted = BackupManager.encrypt(plain.toByteArray(Charsets.UTF_8), "pw")

        assertTrue(BackupManager.isEncrypted(encrypted))
        // The magic header is "NEXUS_ENC_V1\n".
        val magic = "NEXUS_ENC_V1\n"
        assertEquals(magic, String(encrypted.copyOfRange(0, magic.length), Charsets.UTF_8))
        // The secret must not be readable in the encrypted file.
        val asText = String(encrypted, Charsets.UTF_8)
        assertFalse("the token leaked into the encrypted backup", asText.contains("SECRET-TOKEN"))
        assertFalse(asText.contains("example.com"))
    }

    @Test
    fun `plain payload is not detected as encrypted`() {
        val plain = """{"version":1,"profiles":[]}"""
        assertFalse(BackupManager.isEncrypted(plain.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decrypt with the right password restores the original document`() {
        val plain = """{"version":1,"profiles":[{"name":"HK","content":"proxies: []"}]}"""
        val encrypted = BackupManager.encrypt(plain.toByteArray(Charsets.UTF_8), "correct horse")

        assertEquals(plain, BackupManager.decrypt(encrypted, "correct horse"))
    }

    @Test
    fun `decrypt with the wrong password fails`() {
        val encrypted = BackupManager.encrypt("secret payload".toByteArray(Charsets.UTF_8), "right")

        val error = runCatching { BackupManager.decrypt(encrypted, "wrong") }.exceptionOrNull()
        assertNotNull("decrypting with the wrong password must fail", error)
    }

    @Test
    fun `decrypt without a password on an encrypted payload fails`() {
        val encrypted = BackupManager.encrypt("secret payload".toByteArray(Charsets.UTF_8), "pw")

        val error = runCatching { BackupManager.decrypt(encrypted, null) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message?.contains("password is required") == true)
    }

    // ------------------------------------------------------------------
    // 2. Untrusted URL validation on restore
    // ------------------------------------------------------------------

    @Test
    fun `a public https url is restored unchanged`() {
        val url = "https://example.com/sub?token=abc"
        assertEquals(url, BackupManager.sanitizeRestoredUrl(url, allowPrivateHosts = false))
    }

    @Test
    fun `a private host url is rejected when private hosts are not allowed`() {
        assertNull(BackupManager.sanitizeRestoredUrl("http://192.168.1.10/sub", allowPrivateHosts = false))
        assertNull(BackupManager.sanitizeRestoredUrl("http://10.0.0.5/sub", allowPrivateHosts = false))
    }

    @Test
    fun `a link-local url is always rejected`() {
        // The cloud metadata endpoint must never be restored, no matter the switch.
        assertNull(
            BackupManager.sanitizeRestoredUrl(
                "http://169.254.169.254/latest/meta-data",
                allowPrivateHosts = true
            )
        )
    }

    @Test
    fun `a non http scheme is rejected`() {
        assertNull(BackupManager.sanitizeRestoredUrl("file:///etc/passwd", allowPrivateHosts = true))
        assertNull(BackupManager.sanitizeRestoredUrl("ftp://example.com/sub", allowPrivateHosts = true))
    }

    @Test
    fun `an empty url yields null`() {
        assertNull(BackupManager.sanitizeRestoredUrl(null, allowPrivateHosts = true))
        assertNull(BackupManager.sanitizeRestoredUrl("   ", allowPrivateHosts = true))
    }

    // ------------------------------------------------------------------
    // 3. LAN sharing must never end up unauthenticated
    // ------------------------------------------------------------------

    @Test
    fun `lan sharing with a password is restored`() {
        val decision = BackupManager.decideLanRestore(
            wantsLan = true,
            backupLanPassword = "s3cret",
            currentPassword = ""
        )
        assertTrue(decision.allowLan)
        assertEquals("s3cret", decision.password)
        assertFalse(decision.rejected)
    }

    @Test
    fun `lan sharing without any password is refused`() {
        val decision = BackupManager.decideLanRestore(
            wantsLan = true,
            backupLanPassword = "",
            currentPassword = ""
        )
        assertFalse("LAN sharing must stay off without a password", decision.allowLan)
        assertTrue(decision.rejected)
        assertNull(decision.password)
    }

    @Test
    fun `lan sharing with a blank backup password falls back to the configured one`() {
        val decision = BackupManager.decideLanRestore(
            wantsLan = true,
            backupLanPassword = "   ",
            currentPassword = "already-set"
        )
        assertTrue(decision.allowLan)
        assertEquals("already-set", decision.password)
    }

    @Test
    fun `lan sharing stays off when the backup does not ask for it`() {
        val decision = BackupManager.decideLanRestore(
            wantsLan = false,
            backupLanPassword = "s3cret",
            currentPassword = ""
        )
        assertFalse(decision.allowLan)
    }

    @Test
    fun `lan restore rejects passwords containing newlines or control characters`() {
        val enabledMalicious = BackupManager.decideLanRestore(
            wantsLan = true,
            backupLanPassword = "pw\nallow-lan: false\nsecret: evil",
            currentPassword = ""
        )
        assertFalse(enabledMalicious.allowLan)
        assertTrue(enabledMalicious.rejected)
        assertNull(enabledMalicious.password)

        val disabledMalicious = BackupManager.decideLanRestore(
            wantsLan = false,
            backupLanPassword = "pw\r\nallow-lan: true",
            currentPassword = ""
        )
        assertFalse(disabledMalicious.allowLan)
        assertNull(disabledMalicious.password)
    }

    @Test
    fun `blank password plaintext backup remains readable`() {
        val plain = """{"version":2,"profiles":[]}"""
        val raw = plain.toByteArray(Charsets.UTF_8)
        assertFalse(BackupManager.isEncrypted(raw))
        assertEquals(plain, BackupManager.decrypt(raw, null))
        assertEquals(plain, BackupManager.decrypt(raw, ""))
    }

    @Test
    fun `sanitizeRestoredOverrides filters multiline rules and injected dns lines`() {
        val cleaned = BackupManager.sanitizeRestoredOverrides(
            prependRules = listOf("DOMAIN-SUFFIX,example.com,DIRECT", "MATCH,DIRECT\nallow-lan: true"),
            hostsEntries = listOf("router.local: 192.168.1.1", "evil.local: 1.2.3.4\r\nsecret: x"),
            dnsServersOverride = listOf(
                "https://223.5.5.5/dns-query",
                "8.8.8.8\nauthentication: []",
                "external-controller: 0.0.0.0:9090"
            )
        )
        assertTrue(cleaned.hadDroppedEntries)
        assertEquals(listOf("DOMAIN-SUFFIX,example.com,DIRECT"), cleaned.prependRules)
        assertEquals(listOf("router.local: 192.168.1.1"), cleaned.hostsEntries)
        assertEquals(listOf("https://223.5.5.5/dns-query"), cleaned.dnsServersOverride)

        val sanitizedSetting = ConfigSanitizer.sanitizeDnsServerListSetting(
            "223.5.5.5\nallow-lan: true\nexternal-controller: 0.0.0.0:9090\nhttps://1.1.1.1/dns-query"
        )
        assertEquals("223.5.5.5\nhttps://1.1.1.1/dns-query", sanitizedSetting)
    }
}
