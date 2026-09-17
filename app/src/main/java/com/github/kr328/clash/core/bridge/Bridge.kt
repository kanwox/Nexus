package com.github.kr328.clash.core.bridge

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import kotlinx.coroutines.CompletableDeferred
import java.io.File

@Keep
object Bridge {
    private const val TAG = "Bridge"
    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        try {
            Global.init(context.applicationContext as android.app.Application)
            runCatching {
                android.system.Os.setenv("GODEBUG", "cpu.all=off", true)
            }
            try {
                System.loadLibrary("clash")
            } catch (e: Throwable) {
                Log.w(TAG, "libclash load: ${e.message}")
            }
            System.loadLibrary("bridge")

            // Deliberately keep an open fd to the APK: the native core locates the package through
            // /proc/self/fd during nativeInit. Removing this broke native startup.
            try {
                ParcelFileDescriptor.open(File(context.packageCodePath), ParcelFileDescriptor.MODE_READ_ONLY)
                    .detachFd()
            } catch (e: Throwable) {
                Log.w(TAG, "open packageCodePath: ${e.message}")
            }

            val homeDir = context.filesDir.resolve("clash").apply { mkdirs() }
            extractAssets(context, homeDir)
            val home = homeDir.absolutePath
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }
            val sdkVersion = Build.VERSION.SDK_INT

            Log.i(TAG, "Initializing native bridge: home=$home, ver=$versionName, sdk=$sdkVersion")
            nativeInit(home, versionName, sdkVersion)
            isInitialized = true
            Log.i(TAG, "Native bridge initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize native bridge", e)
            throw e
        }
    }

    private fun extractAssets(context: Context, targetDir: File) {
        val assetFiles = listOf("geoip.metadb", "Country.mmdb", "geosite.dat")
        for (fileName in assetFiles) {
            val destFile = targetDir.resolve(fileName)
            if (!destFile.exists() || destFile.length() == 0L) {
                runCatching {
                    context.assets.open(fileName).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Extracted asset: $fileName (${destFile.length()} bytes)")
                }.onFailure { e ->
                    Log.w(TAG, "Could not extract asset $fileName: ${e.message}")
                }
            }
        }
    }

    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeSuspend(suspend: Boolean)
    external fun nativeQueryTunnelState(): String
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long
    external fun nativeNotifyDnsChanged(dnsList: String)
    external fun nativeNotifyTimeZoneChanged(name: String, offset: Int)
    external fun nativeNotifyInstalledAppChanged(uidList: String)
    external fun nativeStartTun(fd: Int, stack: String, gateway: String, portal: String, dns: String, cb: TunInterface)
    external fun nativeStopTun()
    external fun nativeStartHttp(listenAt: String): String?
    external fun nativeStopHttp()
    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativeHealthCheck(completable: CompletableDeferred<Unit>, name: String)
    external fun nativeHealthCheckAll()
    external fun nativePatchSelector(selector: String, name: String): Boolean
    external fun nativeFetchAndValid(
        completable: FetchCallback,
        path: String,
        url: String,
        force: Boolean
    )
    external fun nativeLoad(completable: CompletableDeferred<Unit>, path: String)
    external fun nativeQueryProviders(): String
    external fun nativeUpdateProvider(
        completable: CompletableDeferred<Unit>,
        type: String,
        name: String
    )
    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, content: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeSubscribeLogcat(callback: LogcatInterface)
    external fun nativeCoreVersion(): String

    external fun nativeSetAgeSecretKey(key: String?)
    external fun nativeGenX25519KeyPair(): String?
    external fun nativeGenHybridKeyPair(): String?
    external fun nativeVeritySecretKeys(secretKeys: String): Boolean
    external fun nativeToPublicKeys(secretKeys: String): String?
    external fun nativeVerityPublicKeys(publicKeys: String): Boolean

    private external fun nativeInit(home: String, versionName: String, sdkVersion: Int)
}
