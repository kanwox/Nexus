@file:Suppress("TooManyFunctions")

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
    @Volatile
    private var isInitialized = false

    // Completed once the geo database assets have been extracted to the clash home directory.
    // Extraction runs off the main thread; config loading (the first consumer of these files)
    // awaits it via assetsReady(). The reference is volatile because a failed extraction can be
    // retried on a subsequent init(), which replaces it with a fresh deferred.
    @Volatile
    private var assetsExtracted = CompletableDeferred<Unit>()

    // Guards against starting the extraction thread twice and allows retrying after failure.
    private val extractionLock = Any()
    private var assetsExtractionStarted = false

    @Volatile
    private var appContext: Context? = null

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

    private fun resolveAppContext(): Context? =
        appContext ?: runCatching { Global.application }.getOrNull()

    /** Suspends until the geo assets have been extracted (retrying if a prior attempt failed). */
    suspend fun assetsReady() {
        val extraction = resolveAppContext()?.let(::startAssetsExtractionIfNeeded)
            ?: synchronized(extractionLock) { assetsExtracted }
        extraction.await()
    }

    @Synchronized
    fun init(context: Context) {
        val app = (context.applicationContext as? android.app.Application)
            ?: (context as? android.app.Application)
            ?: error("Bridge.init requires an Application context")
        appContext = app
        Global.init(app)
        startAssetsExtractionIfNeeded(app)
        if (isInitialized) return
        try {
            // The Go core lives in libclash.so and libbridge.so links against it. If libclash
            // cannot be loaded, every native call below would run against a missing core and
            // fail in obscure ways much later, so treat a missing library as fatal.
            System.loadLibrary("clash")
            System.loadLibrary("bridge")

            // Deliberately keep an open fd to the APK: the native core locates the package through
            // /proc/self/fd during nativeInit. Close any previous descriptor on retry.
            try {
                val newFd = ParcelFileDescriptor.open(
                    File(context.packageCodePath),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val oldFd = packageFd
                packageFd = newFd
                runCatching { oldFd?.close() }
            } catch (e: Throwable) {
                Log.w(TAG, "open packageCodePath: ${e.message}", e)
            }

            val homeDir = context.filesDir.resolve("clash").apply { mkdirs() }
            val home = homeDir.absolutePath
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
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
            runCatching { packageFd?.close() }
            packageFd = null
            // Allow a later retry: the caller may be able to recover (e.g. after the
            // emulator was switched to a real device image).
            isInitialized = false
            throw e
        }
    }

    private fun startAssetsExtractionIfNeeded(context: Context): CompletableDeferred<Unit> {
        val extraction: CompletableDeferred<Unit>
        synchronized(extractionLock) {
            if (assetsExtractionStarted) return assetsExtracted
            if (assetsExtracted.isCompleted) {
                assetsExtracted = CompletableDeferred()
            }
            assetsExtractionStarted = true
            extraction = assetsExtracted
        }
        val homeDir = context.filesDir.resolve("clash").apply { mkdirs() }
        // nativeInit only takes the home path and does not read the geo databases, so the
        // extraction can proceed on a background thread while the (fast) native init stays
        // on the calling thread.
        Thread {
            runCatching { extractAssets(context, homeDir) }
                .onFailure {
                    Log.w(TAG, "Asset extraction failed: ${it.message}")
                    failExtraction(
                        extraction,
                        ClashException("Failed to extract the geo database assets", it)
                    )
                    return@Thread
                }
                .onSuccess { failed ->
                    if (failed.isNotEmpty()) {
                        Log.w(TAG, "Could not extract geo assets: $failed")
                        failExtraction(
                            extraction,
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
        return extraction
    }

    private fun failExtraction(extraction: CompletableDeferred<Unit>, error: ClashException) {
        synchronized(extractionLock) {
            // Complete before clearing assetsExtractionStarted so a concurrent retry always
            // observes extraction.isCompleted == true and allocates a fresh CompletableDeferred.
            extraction.completeExceptionally(error)
            if (assetsExtracted === extraction) {
                assetsExtractionStarted = false
            }
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
        val assetFiles = listOf("geoip.metadb", "geosite.dat")
        val failed = mutableListOf<String>()
        val versionMarker = targetDir.resolve(".bundled_geo_version")
        val currentApkVersion = runCatching {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
        }.getOrDefault(0L)
        val recordedVersion = runCatching { versionMarker.readText().trim().toLong() }.getOrDefault(-1L)
        val apkInstallTime = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val versionChanged = currentApkVersion > 0L && currentApkVersion != recordedVersion

        for (fileName in assetFiles) {
            val destFile = targetDir.resolve(fileName)
            val hasValidDest = destFile.exists() && destFile.length() > 0L
            // Preserve files manually updated by the user after the APK was installed/updated.
            val userUpdatedAfterApk = hasValidDest && apkInstallTime > 0L && destFile.lastModified() > apkInstallTime
            if (hasValidDest && (!versionChanged || userUpdatedAfterApk)) continue
            if (!extractSingleAsset(context, targetDir, fileName, destFile)) {
                failed += fileName
            }
        }
        // Clean up legacy Country.mmdb once geoip.metadb is present so the Go core's
        // directory scan cannot accidentally pick up an outdated MMDB alphabetically.
        val geoIpFile = targetDir.resolve("geoip.metadb")
        val legacyMmdb = targetDir.resolve("Country.mmdb")
        if (geoIpFile.exists() && geoIpFile.length() > 0L && legacyMmdb.exists()) {
            runCatching {
                if (legacyMmdb.delete()) {
                    Log.i(TAG, "Removed legacy Country.mmdb in favor of geoip.metadb")
                }
            }
        }
        if (failed.isEmpty() && currentApkVersion > 0L) {
            runCatching { versionMarker.writeText(currentApkVersion.toString()) }
        }
        return failed
    }

    private fun extractSingleAsset(
        context: Context,
        targetDir: File,
        fileName: String,
        destFile: File
    ): Boolean {
        val tmpFile = targetDir.resolve("$fileName.tmp")
        return runCatching {
            context.assets.open(fileName).use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65_536)
                }
            }
            check(tmpFile.length() > 0L) { "extracted file is empty" }
            val moved = runCatching {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                true
            }.getOrElse {
                tmpFile.renameTo(destFile)
            }
            check(moved) { "rename to $fileName failed" }
            Log.i(TAG, "Extracted asset: $fileName (${destFile.length()} bytes)")
            true
        }.getOrElse { e ->
            tmpFile.delete()
            Log.w(TAG, "Could not extract asset $fileName: ${e.message}", e)
            false
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
