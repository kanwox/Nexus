package com.github.mihomo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mihomo.android.core.ClashCore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

@RunWith(AndroidJUnit4::class)
class SpeedTestTest {
    @Test
    fun testDelay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loaded = ClashCore.ensureCoreLoaded(context)
        Log.i("SpeedTestTest", "Core loaded: $loaded")
        
        if (loaded) {
            val groups = ClashCore.queryGroupNames(false)
            Log.i("SpeedTestTest", "Groups: $groups")
            if (groups.isNotEmpty()) {
                val groupName = groups.first()
                val group = ClashCore.queryGroup(groupName)
                Log.i("SpeedTestTest", "Group $groupName proxies: " + group?.proxies?.map { it.name })
                
                val delayMap = ClashCore.healthCheck(groupName)
                Log.i("SpeedTestTest", "Delay map for $groupName: $delayMap")
            }
        }
    }
}
