package com.github.mihomo.android.data

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadBodyCappedTest {
    private fun chunkedResponse(content: String): Response {
        // Simulates a chunked server response: no Content-Length header.
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = Buffer().writeUtf8(content)
        }
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/sub").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    @Test
    fun `chunked response without content length is read correctly`() {
        val yaml = "proxies:\n  - name: n1\n    type: ss\n"
        val text = ConfigManager.readBodyCapped(chunkedResponse(yaml), 20L * 1024 * 1024, "响应内容为空")
        assertEquals(yaml, text)
    }
}
