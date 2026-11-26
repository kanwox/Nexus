package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isVersionNewer follows semver rules including prerelease precedence`() {
        assertTrue(UpdateChecker.isVersionNewer("1.2.0", "1.2.0-rc.1"))
        assertFalse(UpdateChecker.isVersionNewer("1.2.0-rc.1", "1.2.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0-rc.1", "1.2.0-beta.2"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0-beta.10", "1.2.0-beta.2"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0-rc10", "1.2.0-rc2"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0-alpha", "1.0.0-1"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0-alpha.1", "1.2.0-alpha"))
        assertTrue(UpdateChecker.isVersionNewer("v1.3.0", "1.2.9"))
        assertFalse(UpdateChecker.isVersionNewer("v1.2.0+build.42", "1.2.0+build.99"))
        assertFalse(UpdateChecker.isVersionNewer("1.2.0", "unknown"))
    }

    @Test
    fun `parseUpdateInfo selects ABI apk and sums asset and detects newer tag`() {
        val longNotes = "a".repeat(2500)
        val json = """
            {
              "tag_name": "v1.2.0",
              "name": "Nexus 1.2.0",
              "body": "$longNotes",
              "html_url": "https://github.com/kanwox/Nexus/releases/tag/v1.2.0",
              "assets": [
                {"name": "Nexus-x86_64-release.apk", "browser_download_url": "https://github.com/kanwox/Nexus/releases/download/v1.2.0/x86_64.apk"},
                {"name": "Nexus-arm64-v8a-release.apk", "browser_download_url": "https://github.com/kanwox/Nexus/releases/download/v1.2.0/arm64.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/kanwox/Nexus/releases/download/v1.2.0/SHA256SUMS.txt"}
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parseUpdateInfo(
            body = json,
            currentVersion = "1.2.0-rc.1",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        assertTrue(info.isNewer)
        assertEquals("v1.2.0", info.tagName)
        assertEquals("Nexus 1.2.0", info.releaseName)
        assertEquals(2000, info.releaseNotes.length)
        assertEquals("Nexus-arm64-v8a-release.apk", info.apkName)
        assertEquals(
            "https://github.com/kanwox/Nexus/releases/download/v1.2.0/arm64.apk",
            info.apkAsset?.browserDownloadUrl
        )
        assertEquals("SHA256SUMS.txt", info.sumsAsset?.name)
    }

    @Test
    fun `parseUpdateInfo rejects untrusted or non-https asset URLs`() {
        val json = """
            {
              "tag_name": "v2.0.0",
              "name": "Nexus 2.0.0",
              "body": "notes",
              "html_url": "http://evil.example.com/release",
              "assets": [
                {"name": "Nexus-arm64-v8a-release.apk", "browser_download_url": "https://evil.example.com/arm64.apk"},
                {"name": "SHA256SUMS", "browser_download_url": "http://github.com/kanwox/Nexus/SHA256SUMS"}
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parseUpdateInfo(body = json, currentVersion = "1.0.0")
        assertEquals("", info.htmlUrl)
        assertEquals(null, info.apkAsset)
        assertEquals(null, info.sumsAsset)
    }

    @Test
    fun `localProxyClient validates port bounds and evicts stale ports`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChecker.localProxyClient(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChecker.localProxyClient(65536)
        }

        val first = UpdateChecker.localProxyClient(18001)
        assertEquals(setOf(18001), UpdateChecker.activeProxyClientPortsForTest())
        assertSame(first, UpdateChecker.localProxyClient(18001))

        UpdateChecker.localProxyClient(18002)
        assertEquals(setOf(18002), UpdateChecker.activeProxyClientPortsForTest())
    }

    @Test
    fun `candidateClients prioritizes local proxy only while VPN is running`() {
        val runningClients = UpdateChecker.candidateClients(mixedPort = 18003, vpnRunning = true)
        assertEquals(2, runningClients.size)
        assertSame(UpdateChecker.httpClient, runningClients[1])

        val stoppedClients = UpdateChecker.candidateClients(mixedPort = 18003, vpnRunning = false)
        assertEquals(listOf(UpdateChecker.httpClient), stoppedClients)

        val invalidPortClients = UpdateChecker.candidateClients(mixedPort = 0, vpnRunning = true)
        assertEquals(listOf(UpdateChecker.httpClient), invalidPortClients)
    }
}
