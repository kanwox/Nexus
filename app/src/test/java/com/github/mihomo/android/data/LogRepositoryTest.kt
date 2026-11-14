package com.github.mihomo.android.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogRepositoryTest {

    @Before
    fun setUp() {
        ThrottledPublisher.isThrottlingEnabled = false
        LogRepository.setDiagnosticFileForTest(null)
        LogRepository.clear()
    }

    @After
    fun tearDown() {
        LogRepository.setDiagnosticFileForTest(null)
        LogRepository.clear()
    }

    @Test
    fun `addLog appends and formats log entries correctly`() {
        LogRepository.addLog("info", "Service started successfully")
        LogRepository.addLog("warning", "High memory usage detected")
        LogRepository.addLog("error", "Failed to connect to proxy")

        val logs = LogRepository.logsFlow.value
        assertEquals(3, logs.size)
        assertEquals("info", logs[0].level)
        assertEquals("Service started successfully", logs[0].message)
        assertEquals("warning", logs[1].level)
        assertEquals("error", logs[2].level)
    }

    @Test
    fun `addRawLogcat parses json format payload`() {
        val payload = """{"type":"warning","payload":"TCP connection timed out to 1.1.1.1:443"}"""
        LogRepository.addRawLogcat(payload)

        val logs = LogRepository.logsFlow.value
        assertEquals(1, logs.size)
        assertEquals("warning", logs[0].level)
        assertEquals("TCP connection timed out to 1.1.1.1:443", logs[0].message)
    }

    @Test
    fun `addRawLogcat falls back to raw string on invalid json`() {
        val rawMessage = "Plain text log from native bridge"
        LogRepository.addRawLogcat(rawMessage)

        val logs = LogRepository.logsFlow.value
        assertEquals(1, logs.size)
        assertEquals("info", logs[0].level)
        assertEquals(rawMessage, logs[0].message)
    }

    @Test
    fun `logs list is capped at max logs limit`() {
        for (i in 1..1050) {
            LogRepository.addLog("debug", "Log entry #$i")
        }
        val logs = LogRepository.logsFlow.value
        assertEquals(1000, logs.size)
        // First 50 items should have been evicted; earliest log should be #51
        assertEquals("Log entry #51", logs.first().message)
        assertEquals("Log entry #1050", logs.last().message)
    }

    @Test
    fun `clear removes all logs`() {
        LogRepository.addLog("info", "Test log")
        assertEquals(1, LogRepository.logsFlow.value.size)
        LogRepository.clear()
        assertTrue(LogRepository.logsFlow.value.isEmpty())
    }

    @Test
    fun `repeated identical messages collapse into one entry with a count`() {
        repeat(5) { LogRepository.addLog("error", "node failed to respond") }
        LogRepository.addLog("info", "unrelated message")
        // Interleaved repeat still merges into the earlier identical entry.
        LogRepository.addLog("error", "node failed to respond")

        val logs = LogRepository.logsFlow.value
        assertEquals(2, logs.size)
        assertEquals(6, logs[0].count)
        assertEquals("node failed to respond", logs[0].message)
        assertEquals(1, logs[1].count)
    }

    @Test
    fun `screen off keeps warnings and errors but drops info`() {
        val original = LogRepository.isScreenOff
        try {
            LogRepository.isScreenOff = true
            LogRepository.addLog("info", "dropped info")
            LogRepository.addLog("info", "persisted background info", forcePersist = true)
            LogRepository.addLog("warning", "kept warning")
            LogRepository.addLog("error", "kept error")

            val logs = LogRepository.logsFlow.value
            assertEquals(listOf("info", "warning", "error"), logs.map { it.level })
            assertEquals("persisted background info", logs.first().message)
        } finally {
            LogRepository.isScreenOff = original
        }
    }

    @Test
    fun `screen off raw logcat gate lets warning payloads through`() {
        val original = LogRepository.isScreenOff
        try {
            LogRepository.isScreenOff = true
            LogRepository.addRawLogcat("""{"level":"warning","message":"script ignored"}""")
            LogRepository.addRawLogcat("""{"level":"info","message":"noise"}""")

            val logs = LogRepository.logsFlow.value
            assertEquals(1, logs.size)
            assertEquals("warning", logs[0].level)
            assertEquals("script ignored", logs[0].message)
        } finally {
            LogRepository.isScreenOff = original
        }
    }

    @Test
    fun `diagnostic log persists asynchronously with redacted credentials and formatted timestamp`() {
        val tempFile = Files.createTempFile("diagnostic-test", ".log").toFile()
        try {
            LogRepository.setDiagnosticFileForTest(tempFile)
            val rawMessage =
                "GET https://admin:superSecretPass@sub.example.com/api?token=tok_123456789&api_key=key_abcdef " +
                    "Authorization: Bearer bearerSecretToken987654 Basic dXNlcjpwYXNz"
            LogRepository.addLog("error", rawMessage)
            LogRepository.flushDiagnosticsForTest()

            // In-memory UI logs keep the live message
            assertEquals(rawMessage, LogRepository.logsFlow.value.single().message)

            // On-disk diagnostic.log must be redacted and formatted with yyyy-MM-dd HH:mm:ss.SSS
            val persisted = tempFile.readText(Charsets.UTF_8)
            assertTrue(
                "Timestamp header should match [yyyy-MM-dd HH:mm:ss.SSS] [error]",
                Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}] \[error] """).containsMatchIn(persisted)
            )
            assertFalse("Password in URL userinfo must be redacted", persisted.contains("superSecretPass"))
            assertFalse("Token query parameter must be redacted", persisted.contains("tok_123456789"))
            assertFalse("API key query parameter must be redacted", persisted.contains("key_abcdef"))
            assertFalse("Bearer token must be redacted", persisted.contains("bearerSecretToken987654"))
            assertFalse("Basic credential must be redacted", persisted.contains("dXNlcjpwYXNz"))
            assertTrue("Host and path should remain for diagnosis", persisted.contains("sub.example.com/api"))
        } finally {
            LogRepository.setDiagnosticFileForTest(null)
            tempFile.delete()
        }
    }

    @Test
    fun `diagnostic log trims to newer half when exceeding max bytes on trim interval`() {
        val tempFile = Files.createTempFile("diagnostic-trim", ".log").toFile()
        try {
            // Seed file with > 256 KB of old lines
            val oldChunk = "[2026-01-01 00:00:00.000] [warning] old-line-" + "x".repeat(180) + "\n"
            val builder = StringBuilder()
            while (builder.length <= LogRepository.DIAGNOSTIC_MAX_BYTES + 32 * 1024) {
                builder.append(oldChunk)
            }
            tempFile.writeText(builder.toString(), Charsets.UTF_8)
            assertTrue(tempFile.length() > LogRepository.DIAGNOSTIC_MAX_BYTES)

            LogRepository.setDiagnosticFileForTest(tempFile)
            for (i in 1..LogRepository.TRIM_CHECK_INTERVAL) {
                LogRepository.addLog("warning", "fresh-warning-entry-$i")
            }
            LogRepository.flushDiagnosticsForTest()

            assertTrue(
                "Diagnostic file should be trimmed below DIAGNOSTIC_MAX_BYTES (${tempFile.length()})",
                tempFile.length() <= LogRepository.DIAGNOSTIC_MAX_BYTES
            )
            val trimmedText = tempFile.readText(Charsets.UTF_8)
            assertTrue("Trimmed file should start on a line boundary", trimmedText.startsWith("["))
            assertTrue(
                "Newest warning entry must survive trimming",
                trimmedText.contains("fresh-warning-entry-${LogRepository.TRIM_CHECK_INTERVAL}")
            )
        } finally {
            LogRepository.setDiagnosticFileForTest(null)
            tempFile.delete()
        }
    }

    @Test
    fun `clear does not block while diagnostic writer thread is busy`() {
        val tempFile = Files.createTempFile("diagnostic-nonblock", ".log").toFile()
        val workerEntered = CountDownLatch(1)
        val allowWorkerFinish = CountDownLatch(1)
        try {
            LogRepository.setDiagnosticFileForTest(tempFile)
            val blockerFuture = LogRepository.submitDiagnosticTaskForTest(
                Runnable {
                    workerEntered.countDown()
                    allowWorkerFinish.await(5, TimeUnit.SECONDS)
                }
            )
            assertTrue("Diagnostic worker should start running", workerEntered.await(5, TimeUnit.SECONDS))

            val startNs = System.nanoTime()
            LogRepository.addLog("info", "queued while writer is stalled")
            LogRepository.clear()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

            assertTrue(
                "addLog + clear must remain non-blocking while disk I/O is busy (took ${elapsedMs}ms)",
                elapsedMs < 500L
            )
            assertTrue("In-memory log buffer should be cleared immediately", LogRepository.logsFlow.value.isEmpty())

            allowWorkerFinish.countDown()
            blockerFuture.get(5, TimeUnit.SECONDS)
            LogRepository.flushDiagnosticsForTest()
        } finally {
            allowWorkerFinish.countDown()
            LogRepository.setDiagnosticFileForTest(null)
            tempFile.delete()
        }
    }
}
