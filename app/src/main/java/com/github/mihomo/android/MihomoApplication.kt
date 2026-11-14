package com.github.mihomo.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.github.kr328.clash.common.Global
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.DiagnosticsExporter
import com.github.mihomo.android.data.SubscriptionUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MihomoApplication : Application() {
    companion object {
        private const val TAG = "MihomoApp"

        lateinit var instance: MihomoApplication
            private set
        const val CRASH_FILE_NAME = "last_crash.txt"

        private val FALLBACK_URL_QUERY_REGEX =
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://[^\s?]*)\?[^\s"']*""")
        private val FALLBACK_USERINFO_REGEX =
            Regex("""(?i)\b([a-z][a-z0-9+.-]{1,15}://)(?:[^\s/@]+@)+""")

        /**
         * True when this build runs on an emulator.
         *
         * The Go core cannot run under ARM translation (SIGILL inside libclash), so a
         * debuggable build on an emulator must never load the native libraries.
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
            if (!::instance.isInitialized) return false
            return (instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }

        /** Redacts URLs, queries, and credentials before persisting a crash report to disk. */
        internal fun redactCrashReport(raw: String): String {
            return runCatching {
                DiagnosticsExporter.redact(raw)
            }.getOrElse {
                raw.replace(FALLBACK_USERINFO_REGEX, "\$1***")
                    .replace(FALLBACK_URL_QUERY_REGEX, "\$1?***")
            }
        }
    }

    @Suppress("InjectDispatcher")
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        instance = this
        Global.init(this)
        Log.i(TAG, "Application initialized with Global context")

        // Background subscription auto-update checks (WorkManager persists across reboots).
        runCatching { SubscriptionUpdateWorker.schedule(this) }
            .onFailure { Log.w(TAG, "Failed to schedule subscription auto-update", it) }

        migrateLegacyScriptScopesAsync()
        initNativeCoreAsync()
    }

    private fun migrateLegacyScriptScopesAsync() {
        applicationScope.launch {
            runLegacyScriptMigration()
        }
    }

    private fun runLegacyScriptMigration() {
        runCatching {
            ConfigManager.migrateLegacyScriptScopes(this@MihomoApplication)
        }.onFailure { e ->
            Log.w(TAG, "Script scope migration failed", e)
        }
    }

    private fun initNativeCoreAsync() {
        val debuggableBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggableBuild && isEmulator()) {
            Log.w(TAG, "Emulator detected; skipping native core init (debug-only)")
            return
        }
        // Load libclash.so and initialize the native bridge off the main thread so cold
        // startup and BroadcastReceiver wakeups (e.g. BootReceiver) cannot stall the UI/main looper.
        // ClashCore.init is @Synchronized, so any concurrent caller waits safely until ready.
        applicationScope.launch {
            try {
                ClashCore.init(this@MihomoApplication)
            } catch (e: Throwable) {
                Log.e(TAG, "ClashCore initial setup deferred: ${e.message}", e)
            }
        }
    }

    /** Writes the last uncaught exception to last_crash.txt (redacted) so crashes can be diagnosed. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val rawText = "time: $stamp\nthread: ${thread.name}\n\n" + Log.getStackTraceString(throwable)
                val redactedText = redactCrashReport(rawText)
                runCatching { File(filesDir, CRASH_FILE_NAME).writeText(redactedText) }
                Log.e(TAG, "Uncaught exception", throwable)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Log.e(TAG, "No previous uncaught exception handler; terminating")
                exitProcess(2)
            }
        }
    }
}
