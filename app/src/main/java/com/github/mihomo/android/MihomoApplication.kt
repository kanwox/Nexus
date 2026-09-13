package com.github.mihomo.android

import android.app.Application
import android.util.Log
import com.github.kr328.clash.common.Global
import com.github.mihomo.android.core.ClashCore
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

        // Native init (loadLibrary + nativeInit) must stay on the main thread: moving it to a
        // background dispatcher is not safe for the JNI bridge and caused a launch crash.
        try {
            ClashCore.init(this)
        } catch (e: Throwable) {
            Log.e("MihomoApp", "ClashCore initial setup deferred: ${e.message}", e)
        }
    }

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
