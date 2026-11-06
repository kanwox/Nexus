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

    // ---- Rule 1: URL userinfo ------------------------------------------------

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

    // ---- Rule 2: sensitive query params --------------------------------------

    @Test
    fun `sensitive query param value is masked and name kept`() {
        assertEquals("?token=***&host=x", DiagnosticsExporter.redact("?token=abc123&host=x"))
    }

    @Test
    fun `signature is fully matched and not truncated to sign`() {
        assertEquals("?signature=***", DiagnosticsExporter.redact("?signature=deadbeef"))
    }

    @Test
    fun `harmless query params are untouched`() {
        assertEquals("?host=example.com&path=/a", DiagnosticsExporter.redact("?host=example.com&path=/a"))
    }

    // ---- Rule 3: entire query of any scheme URL -------------------------------

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

    // ---- Rule 4: percent-encoded URLs -----------------------------------------

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

    // ---- Rule 5: Bearer --------------------------------------------------------

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

    // ---- Rule 6: JSON credential fields ----------------------------------------

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
    fun `non sensitive json fields stay`() {
        val json = """{"name": "nexus", "level": "info"}"""
        assertEquals(json, DiagnosticsExporter.redact(json))
    }

    // ---- Rule 7: bare credential assignments ------------------------------------

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

    // ---- Rule 8: sk- style keys -------------------------------------------------

    @Test
    fun `sk style api keys are masked`() {
        assertEquals("key ***", DiagnosticsExporter.redact("key sk-a1B2c3D4e5F6g7H8"))
    }

    @Test
    fun `short sk prefix and embedded sk stay`() {
        assertEquals("sk-short", DiagnosticsExporter.redact("sk-short"))
        assertEquals("task-identifier", DiagnosticsExporter.redact("task-identifier"))
    }

    // ---- Rule 9: UUIDs -----------------------------------------------------------

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

    // ---- Rule 10: base64-style secrets --------------------------------------------

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

    // ---- Rules 11 + 12: IP addresses ------------------------------------------------

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
            vmess://user:pass@10.0.0.5:443?x=1
            {"secret": "0123456789abcdef0123456789abcdef"}
            Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123
            sk-a1B2c3D4e5F6g7H8 550e8400-e29b-41d4-a716-446655440000
            secret=abcdef1234567890abcdef1234567890
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
