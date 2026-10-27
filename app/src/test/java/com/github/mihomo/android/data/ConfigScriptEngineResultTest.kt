package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the script-chain outcome reporting.
 *
 * A failed script must not leave a half-rewritten config behind: the caller has to be
 * able to tell that the chain did not complete, and the engine returns the ORIGINAL yaml
 * in that case.
 */
class ConfigScriptEngineResultTest {

    @Test
    fun `the result reports the applied and failed counts`() {
        val result = ScriptExecutionResult(yaml = "x", applied = 2, failed = listOf("a", "b"))
        assertTrue(result.hasFailures)
        assertEquals(4, result.total)
        assertEquals(2, result.applied)
        assertEquals(listOf("a", "b"), result.failed)
    }

    @Test
    fun `a result without failures is not a failure`() {
        val result = ScriptExecutionResult(yaml = "x", applied = 3, failed = emptyList())
        assertFalse(result.hasFailures)
        assertEquals(3, result.total)
    }
}
