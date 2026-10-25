package com.github.mihomo.android

import android.app.Application
import android.os.Build
import android.util.Log
import com.github.kr328.clash.common.Global
import com.github.mihomo.android.core.ClashCore
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MihomoApplication : Application() {
    companion object {
        lateinit var instance: MihomoApplication
            private set
        const val CRASH_FILE_NAME = "last_crash.txt"
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        instance = this
        Global.init(this)
        Log.i("MihomoApp", "Application initialized with Global context")

        // Background subscription auto-update checks (WorkManager persists across reboots).
        runCatching { com.github.mihomo.android.data.SubscriptionUpdateWorker.schedule(this) }
            .onFailure { Log.w("MihomoApp", "Failed to schedule subscription auto-update", it) }
        migrateLegacyScriptScopes()

        // Native init (loadLibrary + nativeInit) must stay on the main thread: moving it to a
        // background dispatcher is not safe for the JNI bridge and caused a launch crash.
        // Debuggable builds on an emulator skip it entirely: the Go core cannot run under
        // ARM translation (SIGILL in libclash), and skipping lets the UI/config/script
        // pipeline be exercised on the emulator. Release builds and real devices always
        // initialize.
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && isEmulator()) {
            Log.w("MihomoApp", "Emulator detected; skipping native core init (debug-only)")
        } else {
            try {
                ClashCore.init(this)
            } catch (e: Throwable) {
                Log.e("MihomoApp", "ClashCore initial setup deferred: ${e.message}", e)
            }
        }
    }

    /**
     * Profiles created before explicit script bindings existed silently applied every enabled
     * script. The scope semantics changed to "empty = none", so such profiles are backfilled
     * with the currently enabled script ids exactly once, preserving their old behavior.
     */
    private fun migrateLegacyScriptScopes() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(this@MihomoApplication)
                val enabledIds = com.github.mihomo.android.data.ScriptManager.getScripts(this@MihomoApplication)
                    .filter { it.enabled }.map { it.id }
                if (enabledIds.isEmpty()) return@launch
                for (profile in profiles) {
                    if (profile.scriptEnabled && profile.scriptIds.isEmpty()) {
                        com.github.mihomo.android.data.ConfigManager.updateProfile(
                            this@MihomoApplication,
                            profile.copy(scriptIds = enabledIds)
                        )
                    }
                }
            }.onFailure { Log.w("MihomoApp", "Script scope migration failed: ${it.message}") }
        }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    /** Writes the last uncaught exception to last_crash.txt so a future crash can be diagnosed. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "time: $stamp\nthread: ${thread.name}\n\n" + Log.getStackTraceString(throwable)
                listOfNotNull(getExternalFilesDir(null), filesDir).forEach { dir ->
                    runCatching { File(dir, CRASH_FILE_NAME).writeText(text) }
                }
                Log.e("MihomoApp", "Uncaught exception", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
