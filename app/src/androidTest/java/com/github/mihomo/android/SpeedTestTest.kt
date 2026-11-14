package com.github.mihomo.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.GeoFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedTestTest {

    @Test
    fun coreLoadsAndExtractsGeoAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.github.mihomo.android", context.packageName)

        val loaded = ClashCore.ensureCoreLoaded(context)
        assertTrue("ClashCore native library and config should load", loaded)

        val coreVersion = ClashCore.getCoreVersion()
        assertTrue("Core version should be populated, got: $coreVersion", coreVersion.isNotBlank() && !coreVersion.startsWith("Unknown"))

        GeoFile.all.forEach { geoFile ->
            val target = geoFile.fileIn(context)
            assertTrue("${geoFile.fileName} should be extracted to ${target.absolutePath}", target.exists() && target.length() > 0L)
        }
    }

    @Test
    fun queryAndHealthCheckMatchGroupContract() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loaded = ClashCore.ensureCoreLoaded(context)
        assertTrue("ClashCore should load before querying proxy groups", loaded)

        val tunnelState = ClashCore.queryTunnelState()
        assertTrue("Tunnel mode should not be blank", tunnelState.mode.isNotBlank())
        assertTrue("Current traffic counter must be non-negative", ClashCore.queryTrafficNow() >= 0L)
        assertTrue("Total traffic counter must be non-negative", ClashCore.queryTrafficTotal() >= 0L)

        val groups = ClashCore.queryGroupNames(excludeNotSelectable = false)
        Log.i("SpeedTestTest", "Groups: $groups")
        if (groups.isNotEmpty()) {
            val groupName = groups.first()
            val group = ClashCore.queryGroup(groupName)
            assertNotNull("Group '$groupName' should resolve after queryGroupNames", group)
            assertTrue("Group '$groupName' should expose at least one proxy", group!!.proxies.isNotEmpty())

            val delayMap = ClashCore.healthCheck(groupName)
            Log.i("SpeedTestTest", "Delay map for $groupName: $delayMap")
            val proxyNames = group.proxies.mapTo(mutableSetOf()) { it.name }
            assertTrue(
                "Health check keys must match proxies in '$groupName'",
                delayMap.keys.all { it in proxyNames }
            )
            assertTrue(
                "Reported delays should be valid milliseconds or timeout sentinel",
                delayMap.values.all { it >= -2 && it <= 65_535 }
            )
        }
    }
}
