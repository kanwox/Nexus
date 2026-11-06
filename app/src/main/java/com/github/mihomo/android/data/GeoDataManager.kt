package com.github.mihomo.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Identifies one geo database file the core loads from its home directory
 * (`filesDir/clash/`, see `Bridge.extractAssets`).
 *
 * The remote name matches the asset published in the MetaCubeX meta-rules-dat release,
 * which is also what [com.github.kr328.clash.core.bridge.Bridge] bundles into the APK.
 */
sealed class GeoFile(val fileName: String, val displayName: String) {
    /** The IP→country database the core actually reads (metadb format). */
    object GeoIpMetadb : GeoFile("geoip.metadb", "GeoIP")

    /** The domain→category database (protobuf format). */
    object GeoSiteDat : GeoFile("geosite.dat", "GeoSite")

    /** Legacy MaxMind database kept for configs that still reference it. */
    object CountryMmdb : GeoFile("Country.mmdb", "Country.mmdb")

    val downloadUrl: String get() = "$RELEASE_BASE$fileName"

    /** Where the core expects the file to live. */
    fun fileIn(context: Context): File = GeoDataManager.clashHome(context).resolve(fileName)

    companion object {
        const val RELEASE_BASE = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/"

        val all: List<GeoFile> = listOf(GeoIpMetadb, GeoSiteDat, CountryMmdb)
    }
}

/** Read-only snapshot of one geo database file on disk. */
data class GeoFileInfo(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

/**
 * Manual, user-triggered updates for the geo databases.
 *
 * There is deliberately NO automatic path: geo updates were previously forbidden entirely
 * because a silently swapped database can change which domains route through the proxy.
 * This manager keeps that guarantee — the only entry point is [updateGeoFile], which the
 * UI must call from an explicit user action — while letting a user fix a stale database
 * without reinstalling the APK.
 */
object GeoDataManager {

    /** A geo database below this size is corrupt (real files are megabytes). */
    private const val MIN_VALID_BYTES = 100L * 1024L

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Fresh clients per port keep proxy settings isolated, mirroring UpdateChecker. */
    private val proxyClients = java.util.concurrent.ConcurrentHashMap<Int, OkHttpClient>()

    /**
     * Lists the known geo databases that currently exist in the core home directory.
     * Pure filesystem reads; safe from any thread.
     */
    fun geoFiles(context: Context): List<GeoFileInfo> {
        val home = clashHome(context)
        return GeoFile.all.mapNotNull { which ->
            val file = home.resolve(which.fileName)
            if (!file.isFile) return@mapNotNull null
            GeoFileInfo(
                fileName = which.fileName,
                absolutePath = file.absolutePath,
                sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                lastModifiedMs = runCatching { file.lastModified() }.getOrDefault(0L)
            )
        }
    }

    /**
     * Downloads [which] from the official MetaCubeX release and atomically replaces the
     * local copy. Direct connection first; when GitHub/CDN is unreachable (the usual
     * reason a user needs this feature) the download retries through the local mixed
     * port, reusing the same fallback strategy as [UpdateChecker].
     *
     * Never call this automatically: it is a user-triggered action only.
     *
     * The downloaded bytes are validated BEFORE the live file is touched, and written to
     * a temp file in the same directory so the swap is an atomic rename — a crash mid-way
     * can only leave the old database or the new one, never a truncated mix.
     */
    suspend fun updateGeoFile(context: Context, which: GeoFile): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appContext = context.applicationContext
                val target = which.fileIn(appContext)
                val staging = File(target.parentFile, "${which.fileName}.update.tmp")
                staging.parentFile?.mkdirs()

                val mixedPort = runCatching { SettingsManager(appContext).mixedPort }.getOrDefault(0)
                val downloaded = tryDownload(directClient, which.downloadUrl, staging)
                    ?: (if (mixedPort > 0) {
                        tryDownload(proxyClient(mixedPort), which.downloadUrl, staging)
                    } else {
                        null
                    })
                    ?: error("download failed via direct connection and local proxy")

                validateDownload(downloaded)
                atomicReplace(downloaded, target)
                staging.delete()
                Unit
            }
        }

    // ------------------------------------------------------------------
    // Internals (kept small and side-effect-light for unit testing)
    // ------------------------------------------------------------------

    /** The core home directory shared with [GeoFile.fileIn]. */
    internal fun clashHome(context: Context): File =
        context.filesDir.resolve("clash").apply { mkdirs() }

    private fun proxyClient(port: Int): OkHttpClient = proxyClients.getOrPut(port) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .build()
    }

    /** Streams the URL into [staging]; null when the network or the HTTP status failed. */
    private fun tryDownload(client: OkHttpClient, url: String, staging: File): File? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Nexus-Android-GeoUpdate")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.byteStream().use { input ->
                    staging.outputStream().use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
                staging
            }
        }.getOrNull()
    }

    /**
     * Rejects empty/undersized payloads and obvious CDN error pages. A content-type check
     * is not enough: some CDN edge cases answer 200 with an HTML/JSON error body, and
     * feeding that to the core would break geo routing until the next config reload.
     */
    private fun validateDownload(file: File) {
        if (!file.isFile) error("staged download is missing")
        val size = file.length()
        if (size <= 0L) error("downloaded file is empty")
        if (size < MIN_VALID_BYTES) error("downloaded file is too small ($size bytes) to be a geo database")
        val head = ByteArray(16)
        file.inputStream().use { input ->
            val read = input.read(head)
            if (read > 0 && looksLikeErrorPage(head)) {
                error("downloaded payload looks like an error page, not a geo database")
            }
        }
    }

    /**
     * True when the payload starts like a text error document (`<` = HTML, `{`/`[` =
     * JSON). Real geo databases start with binary protobuf or SQLite magic bytes, which
     * never begin with a printable brace.
     */
    internal fun looksLikeErrorPage(head: ByteArray): Boolean {
        val first = head.firstOrNull() ?: return false
        return first == '<'.code.toByte() || first == '{'.code.toByte() || first == '['.code.toByte()
    }

    /**
     * Replaces [target] with [staged] atomically. `Files.move` with ATOMIC_MOVE is the
     * primary path; some filesystems (older FAT/emulated storage) refuse atomic moves,
     * so the fallback keeps the update working with a plain rename that is still a
     * single-syscall replace from the core's point of view.
     */
    private fun atomicReplace(staged: File, target: File) {
        val moved = runCatching {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        }.getOrElse {
            runCatching {
                if (target.exists() && !target.delete()) {
                    error("could not remove the old ${target.name} before replacing it")
                }
                staged.renameTo(target)
            }.getOrDefault(false)
        }
        if (!moved || !target.isFile || target.length() == 0L) {
            error("atomic replace of ${target.name} failed")
        }
    }
}
