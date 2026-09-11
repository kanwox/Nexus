package com.github.mihomo.android

import android.app.Application
import android.util.Log
import com.github.kr328.clash.common.Global
import com.github.mihomo.android.core.ClashCore

class MihomoApplication : Application() {
    companion object {
        lateinit var instance: MihomoApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Global.init(this)
        Log.i("MihomoApp", "Application initialized with Global context")

        // Try initializing native core safely without blocking or crashing the UI thread
        try {
            ClashCore.init(this)
        } catch (e: Throwable) {
            Log.e("MihomoApp", "ClashCore initial setup deferred: ${e.message}", e)
        }
    }
}
