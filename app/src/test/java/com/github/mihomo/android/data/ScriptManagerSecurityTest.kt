package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptManagerSecurityTest {

    @Test
    fun `extractGithubRawPath only matches exact raw_githubusercontent_com host`() {
        assertEquals(
            "owner/repo/main/rewrite.js",
            ScriptManager.extractGithubRawPath("https://raw.githubusercontent.com/owner/repo/main/rewrite.js")
        )
        assertEquals(
            "owner/repo/main/rewrite.js?v=1",
            ScriptManager.extractGithubRawPath("https://RAW.GITHUBUSERCONTENT.COM/owner/repo/main/rewrite.js?v=1")
        )

        val spoofed = listOf(
            "https://evil.com/script.js?mirror=raw.githubusercontent.com",
            "https://raw.githubusercontent.com.evil.com/owner/repo/main/rewrite.js",
            "https://user@evil.com/raw.githubusercontent.com/a.js",
            "file:///raw.githubusercontent.com/a.js"
        )
        for (url in spoofed) {
            assertNull("expected host mismatch rejection for $url", ScriptManager.extractGithubRawPath(url))
        }
    }

    @Test
    fun `mirror download stays disabled without pinned hash while direct download is trusted`() {
        val code = "function main(config) { return config; }"

        val mirrorTrust = ScriptManager.resolveRemoteScriptDownloadTrust(code, fromMirror = true)
        assertFalse(mirrorTrust.enabled)
        assertEquals("", mirrorTrust.contentHash)

        val directTrust = ScriptManager.resolveRemoteScriptDownloadTrust(code, fromMirror = false)
        assertTrue(directTrust.enabled)
        assertEquals(ScriptManager.sha256(code), directTrust.contentHash)
    }

    @Test
    fun `canAcceptRemoteUpdate blocks mirror payloads without an exact baseline hash match`() {
        val originalCode = "function main(config) { return config; }"
        val tamperedCode = "function main(config) { config.mode = 'global'; return config; }"
        val originalHash = ScriptManager.sha256(originalCode)
        val tamperedHash = ScriptManager.sha256(tamperedCode)

        // Blank baseline (unverified script) must not accept mirror update
        assertFalse(ScriptManager.canAcceptRemoteUpdate("", originalHash, fromMirror = true))
        // Tampered mirror payload must not overwrite an existing pinned script
        assertFalse(ScriptManager.canAcceptRemoteUpdate(originalHash, tamperedHash, fromMirror = true))
        // Identical mirror payload matching pinned upstream hash succeeds
        assertTrue(ScriptManager.canAcceptRemoteUpdate(originalHash, originalHash, fromMirror = true))
        // Direct upstream update is allowed to evolve the script and update its hash
        assertTrue(ScriptManager.canAcceptRemoteUpdate(originalHash, tamperedHash, fromMirror = false))
    }

    @Test
    fun `verifyScriptIntegrityCode fails closed on blank hash or altered code`() {
        val code = "function main(config) { return config; }"
        val expectedHash = ScriptManager.sha256(code)

        val unhashed = ScriptItem(id = "1", name = "unhashed", contentHash = "")
        assertFalse(ScriptManager.verifyScriptIntegrityCode(unhashed, code))

        val blankHash = ScriptItem(id = "1", name = "blank", contentHash = "   ")
        assertFalse(ScriptManager.verifyScriptIntegrityCode(blankHash, code))

        val pinned = ScriptItem(id = "1", name = "pinned", contentHash = expectedHash)
        assertTrue(ScriptManager.verifyScriptIntegrityCode(pinned, code))
        assertFalse(ScriptManager.verifyScriptIntegrityCode(pinned, "$code // tampered"))
        assertFalse(ScriptManager.verifyScriptIntegrityCode(pinned, ""))
    }
}
