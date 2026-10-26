package com.github.mihomo.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidationTest {

    @Test
    fun `https URLs are allowed`() {
        requireSecureRemoteUrl("https://example.com/api/v1/client/subscribe?token=xyz")
        requireSecureRemoteUrl("HTTPS://EXAMPLE.COM/config.yaml")
    }

    @Test
    fun `loopback http URLs are allowed for local tooling`() {
        requireSecureRemoteUrl("http://127.0.0.1:8080/sub.yaml")
        requireSecureRemoteUrl("http://localhost:3000/profiles")
        requireSecureRemoteUrl("http://localhost/config")
    }

    @Test
    fun `http URLs are allowed`() {
        requireSecureRemoteUrl("http://example.com/sub.yaml")
        requireSecureRemoteUrl("HTTP://EXAMPLE.COM/api/sub")
    }

    @Test
    fun `unsupported protocols are rejected`() {
        val dangerousUrls = listOf(
            "file:///etc/passwd",
            "ftp://ftp.example.com/sub.yaml",
            "content://com.android.providers.media/external/file",
            "javascript:alert(1)"
        )
        for (url in dangerousUrls) {
            val error = assertThrows(IllegalStateException::class.java) {
                requireSecureRemoteUrl(url)
            }
            assertTrue(error.message?.contains("不支持的地址协议") == true)
        }
    }

    @Test
    fun `private literals are rejected when the private-host switch is off`() {
        val urls = listOf(
            "http://10.0.0.1/sub",
            "http://172.16.5.4/sub",
            "http://192.168.1.1/sub",
            "http://127.0.0.1:8080/sub",
            "http://[::1]/sub",
            "http://[fd00::1]/sub",
            "http://localhost/sub"
        )
        for (url in urls) {
            assertThrows(IllegalStateException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = false)
            }
        }
    }

    @Test
    fun `public hostnames that merely start with fc or fd are not private`() {
        assertFalse(isPrivateNetworkHost("fc2.com"))
        assertFalse(isPrivateNetworkHost("fdroid.org"))
        // A prefix match used to reject these when the private-host switch was off.
        requireSecureRemoteUrl("https://fc2.com/sub", allowPrivateHosts = false)
        requireSecureRemoteUrl("https://fdroid.org/sub", allowPrivateHosts = false)
    }

    @Test
    fun `link-local and unspecified targets are always rejected`() {
        for (url in listOf(
            "http://169.254.169.254/latest/meta-data",
            "http://[fe80::1]/sub",
            "http://0.0.0.0/sub"
        )) {
            assertThrows(IllegalStateException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = true)
            }
        }
    }

    @Test
    fun `public addresses stay allowed`() {
        requireSecureRemoteUrl("https://8.8.8.8/sub", allowPrivateHosts = false)
        requireSecureRemoteUrl("https://[2001:4860:4860::8888]/sub", allowPrivateHosts = false)
        assertFalse(isPrivateNetworkHost("8.8.8.8"))
        assertFalse(isPrivateNetworkHost("172.32.0.1"))
    }

    @Test
    fun `isPrivateNetworkHost covers the LAN and loopback ranges`() {
        for (host in listOf(
            "localhost",
            "app.local",
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "169.254.1.1",
            "::1",
            "fd00::1",
            "fe80::1"
        )) {
            assertTrue("expected private: $host", isPrivateNetworkHost(host))
        }
        assertFalse(isPrivateNetworkHost("example.com"))
        assertFalse(isPrivateNetworkHost("172.15.0.1"))
        assertFalse(isPrivateNetworkHost("192.169.0.1"))
    }
}
