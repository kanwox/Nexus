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
@Suppress("unused")
object Bridge {
    private const val TAG = "Bridge"
    private var isInitialized = false

    // Completed once the geo database assets have been extracted to the clash home directory.
    // Extraction is tens of MB on first launch and runs off the main thread; config loading
    // (the first consumer of these files) awaits it via assetsReady().
    // The reference is volatile because a failed init can be retried, which replaces it.
    @Volatile
    private var assetsExtracted = kotlinx.coroutines.CompletableDeferred<Unit>()

    // Guards against starting the extraction thread twice when init() is retried.
    @Volatile
    private var assetsExtractionStarted = false

    /**
     * The APK file descriptor the native core looks up through /proc/self/fd.
     *
     * It MUST stay open for the whole process lifetime: the core resolves the package
     * through that path during nativeInit and keeps reading from it afterwards. Detaching
     * the descriptor and dropping the ParcelFileDescriptor let the finalizer close the fd
     * at an arbitrary GC time, which made the core lose its APK handle. Holding the
     * wrapper in a field keeps the descriptor alive until the process dies.
     */
    @Suppress("unused")
    private var packageFd: ParcelFileDescriptor? = null

    /** Suspends until the geo assets have been extracted (or extraction has failed and given up). */
    suspend fun assetsReady() = assetsExtracted.await()

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        try {
            Global.init(context.applicationContext as android.app.Application)
            // The Go core lives in libclash.so and libbridge.so links against it. If libclash
            // cannot be loaded, every native call below would run against a missing core and
            // fail in obscure ways much later, so treat a missing library as fatal.
            System.loadLibrary("clash")
            System.loadLibrary("bridge")

            // Deliberately keep an open fd to the APK: the native core locates the package through
            // /proc/self/fd during nativeInit. Removing this broke native startup.
            try {
                packageFd = ParcelFileDescriptor.open(File(context.packageCodePath), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Throwable) {
                Log.w(TAG, "open packageCodePath: ${e.message}")
            }

            val homeDir = context.filesDir.resolve("clash").apply { mkdirs() }
            // nativeInit only takes the home path and does not read the geo databases, so the
            // extraction can proceed on a background thread while the (fast) native init stays
            // on the calling thread.
            if (!assetsExtractionStarted) {
                assetsExtractionStarted = true
                val extraction = assetsExtracted
                Thread {
                    runCatching { extractAssets(context, homeDir) }
                        .onFailure {
                            Log.w(TAG, "Asset extraction failed: ${it.message}")
                            // Completing with an exception makes assetsReady() fail loudly instead
                            // of letting the core start with missing geo databases and fail later
                            // with an unrelated error.
                            extraction.completeExceptionally(
                                ClashException("Failed to extract the geo database assets", it)
                            )
                            return@Thread
                        }
                        .onSuccess { failed ->
                            if (failed.isNotEmpty()) {
                                Log.w(TAG, "Could not extract geo assets: $failed")
                                extraction.completeExceptionally(
                                    ClashException(
                                        "Failed to extract the geo database assets: ${failed.joinToString(", ")}"
                                    )
                                )
                                return@Thread
                            }
                        }
                    extraction.complete(Unit)
                }.apply {
                    name = "geo-assets-extract"
                    isDaemon = true
                    start()
                }
            }
            val home = homeDir.absolutePath
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }
            val sdkVersion = Build.VERSION.SDK_INT

            Log.i(TAG, "Initializing native bridge: home=$home, ver=$versionName, sdk=$sdkVersion")
            nativeInit(home, versionName, sdkVersion)
            // Only now is the bridge really usable: every step above can throw, and marking
            // the bridge initialized too early would make a later retry a no-op.
            isInitialized = true
            Log.i(TAG, "Native bridge initialized successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize native bridge", e)
            // Allow a later retry: the caller may be able to recover (e.g. after the
            // emulator was switched to a real device image).
            isInitialized = false
            throw e
        }
    }

    /**
     * Extracts the geo database assets into the clash home directory.
     *
     * A file that cannot be extracted is reported through the return value instead of
     * being swallowed: the caller (the extraction thread) turns that into an exception
     * on [assetsExtracted], so a config load fails with a clear message rather than
     * starting a core without its geo databases.
     *
     * @return the names of the assets that could not be extracted (empty on success).
     */
    private fun extractAssets(context: Context, targetDir: File): List<String> {
        val assetFiles = listOf("geoip.metadb", "Country.mmdb", "geosite.dat")
        val failed = mutableListOf<String>()
        for (fileName in assetFiles) {
            val destFile = targetDir.resolve(fileName)
            if (destFile.exists() && destFile.length() > 0L) continue
            runCatching {
                // Extract to a temp file and rename so a crash mid-copy cannot leave a
                // truncated database that the core would then load as if it were valid.
                val tmpFile = targetDir.resolve("$fileName.tmp")
                context.assets.open(fileName).use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                check(tmpFile.length() > 0L) { "extracted file is empty" }
                check(tmpFile.renameTo(destFile)) { "rename to $fileName failed" }
                Log.i(TAG, "Extracted asset: $fileName (${destFile.length()} bytes)")
            }.onFailure { e ->
                targetDir.resolve("$fileName.tmp").delete()
                Log.w(TAG, "Could not extract asset $fileName: ${e.message}")
                failed += fileName
            }
        }
        return failed
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
