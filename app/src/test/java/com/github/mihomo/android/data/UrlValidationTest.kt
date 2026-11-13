package com.github.mihomo.android.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    @Test
    fun `non-standard numeric IP literals are always rejected`() {
        val obfuscated = listOf(
            "http://0177.0.0.1/sub",
            "http://017.0.0.1/sub",
            "http://0x7f000001/sub",
            "http://0x7f.0.0.1/sub",
            "http://2130706433/sub",
            "http://127.1/sub",
            "http://0xa9fea9fe/latest/meta-data"
        )
        for (url in obfuscated) {
            assertThrows(SsrfBlockedException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = true)
            }
            assertThrows(SsrfBlockedException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = false)
            }
        }
    }

    @Test
    fun `embedded private IPv4 inside IPv6 transition literals is rejected`() {
        val mapped = listOf(
            "http://[::ffff:127.0.0.1]/sub",
            "http://[::ffff:7f00:1]/sub",
            "http://[::ffff:169.254.169.254]/latest/meta-data",
            "http://[::ffff:192.168.1.10]/sub",
            "http://[::127.0.0.1]/sub",
            "http://[::169.254.169.254]/latest/meta-data",
            "http://[64:ff9b::169.254.169.254]/sub",
            "http://[2002:7f00:0001::]/sub"
        )
        for (url in mapped) {
            assertThrows(SsrfBlockedException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = true)
            }
        }
    }

    @Test
    fun `unparseable and backslash-confused URLs fail closed`() {
        val malformed = listOf(
            "http://example.com\\@169.254.169.254/sub",
            "http://example.com:99999/sub",
            "http://[::1/sub",
            "http:///sub",
            "http:///127.0.0.1/sub",
            "http://:8080/sub",
            "http://user@/sub",
            "http://",
            "http://example.com\n127.0.0.1/sub"
        )
        for (url in malformed) {
            assertThrows("expected failure for $url", SsrfBlockedException::class.java) {
                requireSecureRemoteUrl(url, allowPrivateHosts = true)
            }
        }
        assertThrows(SsrfBlockedException::class.java) {
            requireSecureRemoteUrl("http://127.0.0.1:80#@evil.com", allowPrivateHosts = false)
        }
    }

    @Test
    fun `redactUrl never leaks credentials even for malformed URLs`() {
        val redacted = redactUrl("http://user:SUPER_SECRET@127.0.0.1:badport/api/sub?token=LEAK")
        assertFalse(redacted.contains("SUPER_SECRET"))
        assertFalse(redacted.contains("LEAK"))
    }

    @Test
    fun `validateResolvedAddresses blocks forbidden IPs and honors allowPrivateHosts`() {
        assertThrows(UnknownHostException::class.java) {
            validateResolvedAddresses(
                "rebind.example.com",
                listOf(InetAddress.getByName("169.254.169.254")),
                allowPrivateHosts = true
            )
        }
        assertThrows(UnknownHostException::class.java) {
            validateResolvedAddresses(
                "rebind.example.com",
                listOf(InetAddress.getByName("0.1.2.3")),
                allowPrivateHosts = true
            )
        }
        assertThrows(UnknownHostException::class.java) {
            validateResolvedAddresses(
                "rebind.example.com",
                listOf(InetAddress.getByName("127.0.0.1")),
                allowPrivateHosts = false
            )
        }
        assertThrows(UnknownHostException::class.java) {
            validateResolvedAddresses(
                "rebind.example.com",
                listOf(InetAddress.getByName("100.64.0.1")),
                allowPrivateHosts = false
            )
        }
        // Public resolution passes cleanly
        validateResolvedAddresses(
            "example.com",
            listOf(InetAddress.getByName("93.184.216.34")),
            allowPrivateHosts = false
        )
    }

    @Test
    fun `withSsrfProtection blocks HTTP 302 redirect to link-local metadata endpoint`() {
        val redirectResponse =
            "HTTP/1.1 302 Found\r\n" +
                "Location: http://169.254.169.254/latest/meta-data\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        withHttpServer(listOf(redirectResponse)) { port ->
            val client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .withSsrfProtection(allowPrivateSupplier = { true }, validateDns = true)
                .build()
            assertThrows(SsrfBlockedException::class.java) {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/start").build())
                    .execute()
                    .close()
            }
        }
    }

    @Test
    fun `withSsrfProtection on proxy client blocks 302 redirect to loopback when private hosts disabled`() {
        val redirectResponse =
            "HTTP/1.1 302 Found\r\n" +
                "Location: http://127.0.0.1:9090/configs\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        withHttpServer(listOf(redirectResponse)) { port ->
            val client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .withSsrfProtection(allowPrivateSupplier = { false }, validateDns = false)
                .build()
            assertThrows(SsrfBlockedException::class.java) {
                client.newCall(Request.Builder().url("http://example.com/script.js").build())
                    .execute()
                    .close()
            }
        }
    }

    @Test
    fun `withSsrfProtection still follows valid same-origin redirects`() {
        val hop1 =
            "HTTP/1.1 302 Found\r\n" +
                "Location: /final\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        val hop2 =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 2\r\n" +
                "Connection: close\r\n\r\nok"
        withHttpServer(listOf(hop1, hop2)) { port ->
            val client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .withSsrfProtection(allowPrivateSupplier = { true }, validateDns = true)
                .build()
            client.newCall(Request.Builder().url("http://127.0.0.1:$port/start").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("ok", resp.body.string())
            }
        }
    }

    private fun withHttpServer(responses: List<String>, block: (port: Int) -> Unit) {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val worker = thread(isDaemon = true) {
                for (reply in responses) {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                line = reader.readLine()
                            }
                            socket.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                        }
                    }
                }
            }
            try {
                block(server.localPort)
            } finally {
                worker.join(5_000)
            }
        }
    }
}
