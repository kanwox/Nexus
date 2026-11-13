package com.github.mihomo.android.ui.screens

import com.github.kr328.clash.core.model.Proxy
import com.github.mihomo.android.data.SpeedTestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProxiesScreenKeyTest {

    @Test
    fun `duplicate node names receive unique stable keys`() {
        val raw = listOf(
            Proxy(name = "HK 01", delay = 80),
            Proxy(name = "HK 01", delay = 40),
            Proxy(name = "JP 01", delay = 60),
            Proxy(name = "HK 01", delay = 120)
        )

        val indexed = indexProxiesWithOccurrences(raw)
        val keys = indexed.map { it.stableKey }

        assertEquals(listOf("0:HK 01", "1:HK 01", "0:JP 01", "2:HK 01"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `grid row keys stay unique across duplicate nodes and all column counts`() {
        val raw = listOf(
            Proxy(name = "HK 01", delay = 80),
            Proxy(name = "HK 01", delay = 40),
            Proxy(name = "HK 01", delay = 120),
            Proxy(name = "HK 01", delay = 20),
            Proxy(name = "", delay = -1),
            Proxy(name = "", delay = -1)
        )

        val sortedModes = listOf("DEFAULT", "NAME", "DELAY")
        for (mode in sortedModes) {
            val sorted = sortIndexedProxies(raw, sortMode = mode, groupName = "Proxy")
            assertEquals(raw.size, sorted.map { it.stableKey }.toSet().size)

            for (cols in 1..3) {
                val rowKeys = sorted.chunked(cols).map { row ->
                    proxyGridRowKey("Proxy", cols, row.first())
                }
                assertEquals(
                    "Row keys collided for mode=$mode cols=$cols: $rowKeys",
                    rowKeys.size,
                    rowKeys.toSet().size
                )
            }
        }
    }

    @Test
    fun `grid row keys cannot collide across group names with underscores or colons`() {
        val itemA = IndexedProxy(Proxy(name = "2_0:Node"), occurrenceIndex = 0)
        val itemB = IndexedProxy(Proxy(name = "Node"), occurrenceIndex = 0)

        val keyA = proxyGridRowKey("a", cols = 2, firstItem = itemA)
        val keyB = proxyGridRowKey("a_2_0", cols = 2, firstItem = itemB)

        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `delay sorting moves nodes with their occurrence identity intact`() {
        val raw = listOf(
            Proxy(name = "Slow", delay = -1),
            Proxy(name = "Fast", delay = -1),
            Proxy(name = "Fast", delay = -1)
        )
        val delays = mapOf(
            "Proxy:Slow" to SpeedTestState.Success(300),
            "Proxy:Fast" to SpeedTestState.Success(25)
        )

        val sorted = sortIndexedProxies(
            proxies = raw,
            sortMode = "DELAY",
            delayCache = delays,
            groupName = "Proxy"
        )

        assertEquals(listOf("0:Fast", "1:Fast", "0:Slow"), sorted.map { it.stableKey })
    }
}
