package com.github.mihomo.android.ui

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.mihomo.android.data.ParsedProfile
import com.github.mihomo.android.data.SpeedTestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxiesViewModelHelpersTest {

    @Test
    fun `resolveMainProxyGroupName honors pinned group when still present`() {
        val parsed = ParsedProfile(
            groupNames = listOf("Proxy", "Auto", "Streaming"),
            groups = mapOf(
                "Proxy" to ProxyGroup(type = "Selector"),
                "Auto" to ProxyGroup(type = "URLTest"),
                "Streaming" to ProxyGroup(type = "Selector")
            )
        )

        assertEquals(
            "Streaming",
            resolveMainProxyGroupName("Streaming", parsed.groupNames, parsed)
        )
        assertEquals(
            "Proxy",
            resolveMainProxyGroupName("DeletedGroup", parsed.groupNames, parsed)
        )
    }

    @Test
    fun `filterActiveDelaySnapshot drops entries from other profile scopes`() {
        val store = mapOf(
            "p:profileA|Proxy:HK 01" to SpeedTestState.Success(42),
            "p:profileA|HK 01" to SpeedTestState.Success(42),
            "p:profileB|Proxy:HK 01" to SpeedTestState.Timeout,
            "p:profileB|US 01" to SpeedTestState.Success(190)
        )

        val published = filterActiveDelaySnapshot(store, "profileA")

        assertEquals(
            mapOf(
                "Proxy:HK 01" to SpeedTestState.Success(42),
                "HK 01" to SpeedTestState.Success(42)
            ),
            published
        )
        assertFalse(published.containsKey("US 01"))
    }

    @Test
    fun `resolveEffectiveGroupNode rejects saved node missing from current group`() {
        val group = ProxyGroup(
            type = "Selector",
            proxies = listOf(Proxy(name = "JP 01"), Proxy(name = "SG 01")),
            now = "JP 01"
        )

        assertEquals("SG 01", resolveEffectiveGroupNode(group, "SG 01"))
        assertEquals("JP 01", resolveEffectiveGroupNode(group, "RemovedNode"))
        assertEquals("JP 01", resolveEffectiveGroupNode(group, null))
    }
}
