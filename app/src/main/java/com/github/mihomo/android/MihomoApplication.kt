package com.github.mihomo.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.github.kr328.clash.common.Global
import com.github.mihomo.android.core.ClashCore
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MihomoApplication : Application() {
    companion object {
        lateinit var instance: MihomoApplication
            private set
        const val CRASH_FILE_NAME = "last_crash.txt"

        /**
         * True when this build runs on an emulator.
         *
         * The Go core cannot run under ARM translation (SIGILL inside libclash), so a
         * debuggable build on an emulator must never load the native libraries. The check
         * lives here (rather than being private to onCreate) because the VPN service has to
         * honour it too: the service re-entered the native init path even though the
         * application had deliberately skipped it.
         */
        fun isEmulator(): Boolean =
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true)

        /** True when the installed build is marked debuggable. */
        fun isDebuggable(): Boolean {
            // The application instance is not assigned until onCreate() runs, so the
            // lateinit check must not be a plain null check (it would throw instead).
            if (!::instance.isInitialized) return false
            return (instance.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
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
        val prefs = getSharedPreferences("mihomo_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_script_scopes_migrated", false)) return

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(this@MihomoApplication)
                val enabledIds = com.github.mihomo.android.data.ScriptManager.getScripts(this@MihomoApplication)
                    .filter { it.enabled }.map { it.id }
                if (enabledIds.isNotEmpty()) {
                    for (profile in profiles) {
                        if (profile.scriptEnabled && profile.scriptIds.isEmpty()) {
                            com.github.mihomo.android.data.ConfigManager.updateProfile(
                                this@MihomoApplication,
                                profile.copy(scriptIds = enabledIds)
                            )
                        }
                    }
                }
                prefs.edit { putBoolean("legacy_script_scopes_migrated", true) }
            }.onFailure { Log.w("MihomoApp", "Script scope migration failed: ${it.message}") }
        }
    }

    private fun isEmulator(): Boolean =
        MihomoApplication.isEmulator()

    /** Writes the last uncaught exception to last_crash.txt so a future crash can be diagnosed. */    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "time: $stamp\nthread: ${thread.name}\n\n" + Log.getStackTraceString(throwable)
                // Only internal storage: the crash report can contain URLs and file paths,
                // and external app-scoped storage is world-readable to other apps on older
                // releases and survives an uninstall as a leftover.
                runCatching { File(filesDir, CRASH_FILE_NAME).writeText(text) }
                Log.e("MihomoApp", "Uncaught exception", throwable)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Without a previous handler the process would keep running in an undefined
                // state (the crash happened on an arbitrary thread). Terminate explicitly
                // instead of pretending the app survived.
                Log.e("MihomoApp", "No previous uncaught exception handler; terminating")
                exitProcess(2)
            }
        }
    }
}
