package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DiagnosticsExporter.redact] — every rule needs at least one HIT and
 * one MISS case.
 *
 * Note on expectations: the public-IPv4 rule rewrites every non-loopback dotted quad to
 * `<ip>`, so a MISS for another rule is only asserted on inputs that contain no dotted
 * quad at all — otherwise the IP rule would legitimately change the string and mask the
 * rule under test.
 */
class DiagnosticsRedactTest {

    // ---- Rule 1 & 2: URL userinfo & Proxy URI payloads -----------------------

    @Test
    fun `userinfo credentials in a vmess uri are replaced`() {
        assertEquals(
            "vmess://***<ip>:443?***",
            DiagnosticsExporter.redact("vmess://user:pass@1.2.3.4:443?x=1")
        )
    }

    @Test
    fun `userinfo without password is also replaced`() {
        assertEquals("https://***host/path", DiagnosticsExporter.redact("https://user@host/path"))
    }

    @Test
    fun `url without userinfo is untouched`() {
        assertEquals("https://example.com/path", DiagnosticsExporter.redact("https://example.com/path"))
    }

    @Test
    fun `opaque and authority proxy uris across schemes are redacted while preserving host structure`() {
        // Opaque base64 vmess / ss / ssr payloads without '@'
        assertEquals(
            "vmess://***",
            DiagnosticsExporter.redact("vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzLCJpZCI6InNlY3JldCJ9")
        )
        assertEquals(
            "ss://***",
            DiagnosticsExporter.redact("ss://YWVzLTI1Ni1nY206cGFzcw==#HK")
        )
        assertEquals(
            "ssr://***",
            DiagnosticsExporter.redact("ssr://MTI3LjAuMC4xOjg4ODg6b3JpZ2luOmFlcy0yNTYtY2ZiOnBsYWluOmJhc2U2NF9wYXNz")
        )

        // Authority-form trojan / vless / hysteria2 / tuic / ss preserve host:port structure
        assertEquals(
            "trojan://***example.com:443?***",
            DiagnosticsExporter.redact("trojan://p@ss!word@example.com:443?sni=example.com#Node")
        )
        assertEquals(
            "vless://***example.com:443?***",
            DiagnosticsExporter.redact("vless://550e8400-e29b-41d4-a716-446655440000@example.com:443?security=tls")
        )
        assertEquals(
            "hysteria2://***example.com:443/?***",
            DiagnosticsExporter.redact("hysteria2://my!secret@example.com:443/?insecure=1&sni=example.com")
        )
        assertEquals(
            "tuic://***example.com:443/?***",
            DiagnosticsExporter.redact("tuic://uuid:p@ss!@example.com:443/?congestion_control=bbr")
        )
        assertEquals(
            "ss://***example.com:8388#HK",
            DiagnosticsExporter.redact("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:8388#HK")
        )
    }

    // ---- Rule 3: subscription path tokens ------------------------------------

    @Test
    fun `subscription path tokens and subscribe query tokens are redacted`() {
        assertEquals(
            "https://sub.example.com/link/***",
            DiagnosticsExporter.redact("https://sub.example.com/link/AbCd1234EfGh5678")
        )
        assertEquals(
            "https://sub.example.com/sub/***/clash",
            DiagnosticsExporter.redact("https://sub.example.com/sub/9f8e7d6c5b4a3210/clash")
        )
        assertEquals(
            "https://sub.example.com/api/v1/client/subscribe?***",
            DiagnosticsExporter.redact("https://sub.example.com/api/v1/client/subscribe?token=secret123&flag=clash")
        )
        assertEquals(
            "https://panel.example.com/api/v1/***",
            DiagnosticsExporter.redact("https://panel.example.com/api/v1/AbCd1234EfGh5678")
        )
        assertEquals(
            "https://panel.example.com/***/***",
            DiagnosticsExporter.redact("https://panel.example.com/user1234567890ab/tok9876543210xyz")
        )
        // Release asset URLs must remain readable
        assertEquals(
            "https://github.com/MetaCubeX/mihomo/releases/download/v1.18.0/mihomo-android.apk",
            DiagnosticsExporter.redact("https://github.com/MetaCubeX/mihomo/releases/download/v1.18.0/mihomo-android.apk")
        )
    }

    // ---- Rule 4: sensitive query params --------------------------------------

    @Test
    fun `sensitive query param value is masked and name kept`() {
        assertEquals("?token=***&host=x", DiagnosticsExporter.redact("?token=abc123&host=x"))
        assertEquals(
            "?api_key=***&apikey=***&access_token=***&refresh_token=***&psk=***&secret=***&password=***",
            DiagnosticsExporter.redact(
                "?api_key=k1&apikey=k2&access_token=t1&refresh_token=t2&psk=p1&secret=s1&password=p!@2"
            )
        )
    }

    @Test
    fun `signature is fully matched and not truncated to sign`() {
        assertEquals("?signature=***", DiagnosticsExporter.redact("?signature=deadbeef"))
    }

    @Test
    fun `harmless query params are untouched`() {
        assertEquals("?host=example.com&path=/a", DiagnosticsExporter.redact("?host=example.com&path=/a"))
    }

    // ---- Rule 5: entire query of any scheme URL -------------------------------

    @Test
    fun `whole query of a subscription url is wiped and host path kept`() {
        assertEquals(
            "https://sub.example.com/api?***",
            DiagnosticsExporter.redact("https://sub.example.com/api?token=abc&sign=xy")
        )
    }

    @Test
    fun `url without query is untouched by the wipe rule`() {
        assertEquals(
            "https://sub.example.com/api",
            DiagnosticsExporter.redact("https://sub.example.com/api")
        )
    }

    // ---- Rule 6: percent-encoded URLs -----------------------------------------

    @Test
    fun `percent encoded subscription url is wiped`() {
        assertEquals(
            "redirect=***",
            DiagnosticsExporter.redact("redirect=https%3A%2F%2Fsub.example.com%2Fapi%3Ftoken%3Dabc123")
        )
    }

    @Test
    fun `percent encoded wipe stops at ampersand`() {
        assertEquals(
            "***&next=1",
            DiagnosticsExporter.redact("https%3A%2F%2Fa.com%2Fx%3Ftoken%3D1&next=1")
        )
    }

    @Test
    fun `percent encoded non http text is untouched`() {
        assertEquals("a%3A%2F%2Fb", DiagnosticsExporter.redact("a%3A%2F%2Fb"))
    }

    // ---- Rule 7, 8, 9: Bearer, Basic, and short JWT ---------------------------

    @Test
    fun `bearer token is masked`() {
        assertEquals(
            "Authorization: Bearer ***",
            DiagnosticsExporter.redact("Authorization: Bearer abc123def456")
        )
    }

    @Test
    fun `prose with the word bearer stays`() {
        assertEquals("Bearer token required", DiagnosticsExporter.redact("Bearer token required"))
    }

    @Test
    fun `bearer value boundary is exactly eight chars`() {
        assertEquals("Bearer ***", DiagnosticsExporter.redact("Bearer abcdef12"))
        assertEquals("Bearer abcdef1", DiagnosticsExporter.redact("Bearer abcdef1"))
    }

    @Test
    fun `basic base64 credentials are masked while prose stays`() {
        assertEquals(
            "Authorization: Basic ***",
            DiagnosticsExporter.redact("Authorization: Basic dXNlcjpwYXNz")
        )
        assertEquals(
            "Proxy-Authorization: Basic ***",
            DiagnosticsExporter.redact("Proxy-Authorization: Basic YWRtaW46MTIzNDU2==")
        )
        assertEquals("Basic ***", DiagnosticsExporter.redact("Basic YTpi"))
        assertEquals("Basic auth required", DiagnosticsExporter.redact("Basic auth required"))
        assertEquals("Basic Auth", DiagnosticsExporter.redact("Basic Auth"))
        assertEquals("Basic HTTP", DiagnosticsExporter.redact("Basic HTTP"))
    }

    @Test
    fun `short three segment jwt without bearer prefix is masked`() {
        assertEquals(
            "cookie=***",
            DiagnosticsExporter.redact("cookie=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl")
        )
        assertEquals(
            "***",
            DiagnosticsExporter.redact("eyJhbGci.eyJzdWI.c2ln")
        )
    }

    // ---- Rule 10, 11, 12: JSON & YAML/log credential fields -------------------

    @Test
    fun `json secret and uuid field values are masked`() {
        assertEquals(
            """{"secret": "***", "uuid": "***"}""",
            DiagnosticsExporter.redact(
                """{"secret": "abcdef123456", "uuid": "550e8400-e29b-41d4-a716-446655440000"}"""
            )
        )
    }

    @Test
    fun `json and yaml sensitive keys and passwords with special characters are masked`() {
        val jsonInput = """{"api_key": "k12345678", "apikey": "k2", "access_token": "at_123", "refresh_token": "rt_456", "psk": "psk_789", "password": "p@ss!\"word"}"""
        val jsonExpected = """{"api_key": "***", "apikey": "***", "access_token": "***", "refresh_token": "***", "psk": "***", "password": "***"}"""
        assertEquals(jsonExpected, DiagnosticsExporter.redact(jsonInput))

        // YAML quoted & unquoted with !, @, "
        assertEquals(
            """password: "***"""",
            DiagnosticsExporter.redact("""password: "p@ss!\"word"""")
        )
        assertEquals(
            "psk: '***'",
            DiagnosticsExporter.redact("psk: 'p@ss!word'")
        )
        assertEquals(
            "password: ***",
            DiagnosticsExporter.redact("password: p@ss!123")
        )
        assertEquals(
            "password=***",
            DiagnosticsExporter.redact("password=a!b\"c")
        )
        assertEquals(
            """{"msg": "password=***"}""",
            DiagnosticsExporter.redact("""{"msg": "password=a!b\"c"}""")
        )
        assertEquals(
            "api_key: ***\naccess_token: ***\nrefresh_token: ***\npsk: ***",
            DiagnosticsExporter.redact(
                "api_key: secret_key_123\naccess_token: access_12345\nrefresh_token: refresh_12345\npsk: shared_psk_123"
            )
        )
    }

    @Test
    fun `non sensitive json fields stay`() {
        val json = """{"name": "nexus", "level": "info"}"""
        assertEquals(json, DiagnosticsExporter.redact(json))
    }

    @Test
    fun `bare secret and token assignments are masked`() {
        assertEquals(
            "secret=***",
            DiagnosticsExporter.redact("secret=abcdef1234567890abcdef1234567890")
        )
        assertEquals("token: ***", DiagnosticsExporter.redact("token: abcd1234efgh5678"))
    }

    @Test
    fun `short values after credential words stay`() {
        val text = "password: none and token=abc"
        assertEquals(text, DiagnosticsExporter.redact(text))
    }

    // ---- Rule 13: sk- style keys ------------------------------------------------

    @Test
    fun `sk style api keys are masked`() {
        assertEquals("key ***", DiagnosticsExporter.redact("key sk-a1B2c3D4e5F6g7H8"))
    }

    @Test
    fun `short sk prefix and embedded sk stay`() {
        assertEquals("sk-short", DiagnosticsExporter.redact("sk-short"))
        assertEquals("task-identifier", DiagnosticsExporter.redact("task-identifier"))
    }

    // ---- Rule 14: UUIDs ----------------------------------------------------------

    @Test
    fun `uuid is masked`() {
        assertEquals(
            "conn *** end",
            DiagnosticsExporter.redact("conn 550e8400-e29b-41d4-a716-446655440000 end")
        )
    }

    @Test
    fun `uuid shaped runs without the exact group layout stay`() {
        val digits = "123456789012345678901234567890123456"
        assertEquals(digits, DiagnosticsExporter.redact(digits))
        val truncated = "550e8400-e29b-41d4-a716-44665544000"
        assertEquals(truncated, DiagnosticsExporter.redact(truncated))
    }

    // ---- Rule 15: base64-style secrets -------------------------------------------

    @Test
    fun `mixed case long token is masked`() {
        assertEquals(
            "auth *** done",
            DiagnosticsExporter.redact("auth AbCdEf123456AbCdEf123456AbCdEf123456 done")
        )
    }

    @Test
    fun `lowercase hex checksum stays readable`() {
        val line = "sha256 abcdef1234567890abcdef1234567890abcdef"
        assertEquals(line, DiagnosticsExporter.redact(line))
    }

    // ---- Rules 16, 17, 18: IPv6 and IPv4 addresses -------------------------------

    @Test
    fun `ipv6 addresses are masked while loopback and timestamps stay intact`() {
        assertEquals(
            "<ipv6>",
            DiagnosticsExporter.redact("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
        )
        assertEquals(
            "[<ipv6>]:443",
            DiagnosticsExporter.redact("[2606:4700:4700::1111]:443")
        )
        assertEquals(
            "fe80 -> <ipv6>",
            DiagnosticsExporter.redact("fe80 -> fe80::1ff:fe23:4567:890a%wlan0")
        )
        assertEquals(
            "<ipv6>",
            DiagnosticsExporter.redact("::ffff:192.0.2.128")
        )
        // Loopback ::1 and ISO timestamps must remain untouched
        assertEquals(
            "http://[::1]:9090/version at 2026-11-13T22:15:00+08:00",
            DiagnosticsExporter.redact("http://[::1]:9090/version at 2026-11-13T22:15:00+08:00")
        )
    }

    @Test
    fun `private ranges become lan ip with ports kept`() {
        assertEquals("<lan-ip>:8080", DiagnosticsExporter.redact("10.0.0.5:8080"))
        assertEquals("<lan-ip>", DiagnosticsExporter.redact("192.168.1.100"))
        assertEquals("<lan-ip>", DiagnosticsExporter.redact("172.16.0.1"))
        assertEquals("<lan-ip>", DiagnosticsExporter.redact("172.31.255.255"))
    }

    @Test
    fun `loopback stays intact`() {
        assertEquals(
            "ws://127.0.0.1:9090/connections",
            DiagnosticsExporter.redact("ws://127.0.0.1:9090/connections")
        )
    }

    @Test
    fun `public and boundary ips become ip`() {
        assertEquals("<ip>", DiagnosticsExporter.redact("8.8.8.8"))
        assertEquals("<ip>", DiagnosticsExporter.redact("110.0.0.1"))
        assertEquals("<ip>", DiagnosticsExporter.redact("172.32.0.1"))
        assertEquals("<ip>", DiagnosticsExporter.redact("172.15.0.1"))
    }

    // ---- Idempotency -----------------------------------------------------------------

    @Test
    fun `redact is idempotent`() {
        val rich = """
            订阅 https://sub.example.com/link?token=abc123def&flag=1
            路径 https://sub.example.com/link/AbCd1234EfGh5678
            vmess://user:pass@10.0.0.5:443?x=1
            ss://YWVzLTI1Ni1nY206cGFzcw==#HK
            {"secret": "0123456789abcdef0123456789abcdef", "password": "p@ss!\"word"}
            Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123
            Basic dXNlcjpwYXNz
            jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln
            sk-a1B2c3D4e5F6g7H8 550e8400-e29b-41d4-a716-446655440000
            secret=abcdef1234567890abcdef1234567890 psk: my!p@ss"word
            [2606:4700:4700::1111]:443
            ?token=abc&host=x
        """.trimIndent()
        val once = DiagnosticsExporter.redact(rich)
        assertEquals(once, DiagnosticsExporter.redact(once))
    }

    // ---- Combination -------------------------------------------------------------------

    @Test
    fun `mixed diagnostics text is fully redacted`() {
        val input = """
            订阅 https://sub.example.com/link?token=abc123def&flag=1
            vmess://user:pass@10.0.0.5:443?x=1
            {"secret": "0123456789abcdef0123456789abcdef"}
            Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123
            sk-a1B2c3D4e5F6g7H8 550e8400-e29b-41d4-a716-446655440000
            ws://127.0.0.1:9090/connections
        """.trimIndent()
        val expected = """
            订阅 https://sub.example.com/link?***
            vmess://***<lan-ip>:443?***
            {"secret": "***"}
            Bearer ***
            *** ***
            ws://127.0.0.1:9090/connections
        """.trimIndent()
        assertEquals(expected, DiagnosticsExporter.redact(input))
    }
}
