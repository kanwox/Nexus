@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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

    val downloadUrl: String get() = "$RELEASE_BASE$fileName"

    /** Official GitHub release SHA-256 checksum URL for verifying CDN payloads. */
    val checksumUrl: String get() = "$CHECKSUM_BASE$fileName.sha256sum"

    /** Where the core expects the file to live. */
    fun fileIn(context: Context): File = GeoDataManager.clashHome(context).resolve(fileName)

    companion object {
        const val RELEASE_BASE = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/"
        const val CHECKSUM_BASE = "https://github.com/MetaCubeX/meta-rules-dat/releases/latest/download/"

        // Lazy initialization avoids the JVM sealed-class/object <clinit> cycle where
        // accessing GeoIpMetadb first used to capture a null INSTANCE inside Companion.all.
        val all: List<GeoFile> by lazy { listOf(GeoIpMetadb, GeoSiteDat) }
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
    internal const val MIN_VALID_BYTES = 100L * 1024L

    /** Upper bound on a geo database stream (50 MiB) so a rogue response cannot exhaust storage/RAM. */
    internal const val MAX_GEO_FILE_BYTES = 50L * 1024L * 1024L

    private const val HEADER_INSPECT_BYTES = 512
    private const val MAXMIND_TAIL_SEARCH_BYTES = 128 * 1024
    private const val PROTOBUF_OUTER_TAG = 0x0A
    private const val PROTOBUF_INNER_TAG_COUNTRY = 0x0A
    private const val PROTOBUF_INNER_TAG_DOMAIN = 0x12
    private const val MAX_VARINT_BYTES = 5

    /** `\xAB\xCD\xEFMaxMind.com` metadata delimiter required near the end of every MaxMind DB. */
    internal val MAXMIND_METADATA_MARKER = byteArrayOf(
        0xAB.toByte(),
        0xCD.toByte(),
        0xEF.toByte(),
        'M'.code.toByte(),
        'a'.code.toByte(),
        'x'.code.toByte(),
        'M'.code.toByte(),
        'i'.code.toByte(),
        'n'.code.toByte(),
        'd'.code.toByte(),
        '.'.code.toByte(),
        'c'.code.toByte(),
        'o'.code.toByte(),
        'm'.code.toByte()
    )

    private val updateLock = Mutex()

    internal val directClient: OkHttpClient by lazy {
        NetworkClients.directClient(
            connectTimeoutSec = 15,
            readTimeoutSec = 60,
            callTimeoutSec = 180,
            allowPrivateSupplier = { false }
        )
    }

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
     * local copy, returning the updated file size in bytes.
     *
     * Direct connection first; when GitHub/CDN is unreachable (or returns a captive-portal
     * error page) the download retries through the local mixed port, sharing the recyclable
     * proxy client pool with [UpdateChecker].
     */
    suspend fun updateGeoFile(context: Context, which: GeoFile): Result<Long> =
        withContext(Dispatchers.IO) {
            updateLock.withLock {
                try {
                    Result.success(performUpdateGeoFile(context.applicationContext, which))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ignored: Exception) {
                    Result.failure(ignored)
                }
            }
        }

    // ------------------------------------------------------------------
    // Internals (kept small and side-effect-light for unit testing)
    // ------------------------------------------------------------------

    private fun performUpdateGeoFile(appContext: Context, which: GeoFile): Long {
        val target = which.fileIn(appContext)
        val staging = File(target.parentFile, "${which.fileName}.update.tmp")
        staging.parentFile?.mkdirs()
        try {
            val mixedPort = runCatching { SettingsManager(appContext).mixedPort }.getOrDefault(0)
            val vpnRunning = runCatching {
                com.github.mihomo.android.service.MihomoVpnService.vpnState.value.status ==
                    com.github.mihomo.android.service.VpnState.Status.RUNNING
            }.getOrDefault(false)
            val clients = if (vpnRunning && mixedPort in 1..65535) {
                listOf(proxyClient(mixedPort), directClient)
            } else if (mixedPort in 1..65535) {
                listOf(directClient, proxyClient(mixedPort))
            } else {
                listOf(directClient)
            }
            val expectedSha256 = fetchExpectedSha256(clients, which)
                ?: error("failed to fetch official SHA-256 checksum for ${which.fileName}")
            val downloaded = clients.firstNotNullOfOrNull { client ->
                tryDownloadAndValidate(client, which, staging, expectedSha256)
            } ?: error("download or SHA-256 verification failed via direct connection and local proxy")

            atomicReplace(downloaded, target)
            return target.length()
        } finally {
            staging.delete()
        }
    }

    private fun fetchExpectedSha256(clients: List<OkHttpClient>, which: GeoFile): String? {
        for (client in clients) {
            val request = Request.Builder()
                .url(which.checksumUrl)
                .header("User-Agent", "Nexus-Android-GeoUpdate")
                .build()
            val hash = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val text = ConfigManager.readBodyCapped(response, 4096L, "Empty checksum")
                    parseSha256Sum(text)
                }
            }.getOrNull()
            if (hash != null) return hash
        }
        return null
    }

    internal fun parseSha256Sum(raw: String): String? {
        val token = raw.trim().substringBefore(' ').substringBefore('\t').trim()
        return token.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
            ?.lowercase(java.util.Locale.ROOT)
    }

    private fun computeFileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    /** The core home directory shared with [GeoFile.fileIn]. */
    internal fun clashHome(context: Context): File =
        context.filesDir.resolve("clash").apply { mkdirs() }

    /** Shares the single-active-port proxy client cache with [UpdateChecker]. */
    internal fun proxyClient(port: Int): OkHttpClient = UpdateChecker.localProxyClient(port)

    private fun tryDownloadAndValidate(
        client: OkHttpClient,
        which: GeoFile,
        staging: File,
        expectedSha256: String? = null
    ): File? {
        val downloaded = tryDownload(client, which.downloadUrl, staging) ?: return null
        return runCatching {
            validateDownload(downloaded, which)
            if (expectedSha256 != null) {
                val actual = computeFileSha256(downloaded)
                check(actual.equals(expectedSha256, ignoreCase = true)) {
                    "SHA-256 mismatch for ${which.fileName}"
                }
            }
            downloaded
        }.onFailure {
            staging.delete()
        }.getOrNull()
    }

    /** Streams the URL into [staging] with a hard byte cap; null when network/HTTP/cap fails. */
    private fun tryDownload(client: OkHttpClient, url: String, staging: File): File? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Nexus-Android-GeoUpdate")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val declared = response.body.contentLength()
                if (declared > MAX_GEO_FILE_BYTES) {
                    error("geo download exceeds maximum size ($declared > $MAX_GEO_FILE_BYTES)")
                }
                response.body.byteStream().use { input ->
                    staging.outputStream().use { output ->
                        copyStreamCapped(input, output, MAX_GEO_FILE_BYTES)
                    }
                }
                staging
            }
        }.onFailure {
            staging.delete()
        }.getOrNull()
    }

    internal fun copyStreamCapped(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_GEO_FILE_BYTES
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                error("downloaded stream exceeds maximum size of $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    /**
     * Rejects empty/undersized/oversized payloads, CDN error pages (even with leading BOM or
     * whitespace), and files that do not match the expected binary format of [which].
     */
    internal fun validateDownload(file: File, which: GeoFile = GeoFile.GeoSiteDat) {
        if (!file.isFile) error("staged download is missing")
        val size = file.length()
        if (size <= 0L) error("downloaded file is empty")
        if (size < MIN_VALID_BYTES) {
            error("downloaded file is too small ($size bytes) to be a geo database")
        }
        if (size > MAX_GEO_FILE_BYTES) {
            error("downloaded file exceeds maximum allowed size ($size bytes)")
        }
        val head = readFileHead(file, HEADER_INSPECT_BYTES)
        if (looksLikeErrorPage(head)) {
            error("downloaded payload looks like an error page, not a geo database")
        }
        when (which) {
            GeoFile.GeoSiteDat -> if (!hasValidGeoSiteProtobufHeader(head)) {
                error("downloaded geosite.dat does not have a valid protobuf header")
            }
            GeoFile.GeoIpMetadb -> if (!hasMaxMindMetadataMarker(file)) {
                error("downloaded geoip.metadb is missing the MaxMind DB metadata marker")
            }
        }
    }

    private fun readFileHead(file: File, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        val bytesRead = file.inputStream().use { input ->
            var offset = 0
            while (offset < maxBytes) {
                val read = input.read(buffer, offset, maxBytes - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        }
        return if (bytesRead <= 0) ByteArray(0) else buffer.copyOf(bytesRead)
    }

    /**
     * True when the payload starts like a text error document (`<` = HTML/XML, `{`/`[` = JSON)
     * after stripping any leading UTF-8/UTF-16 BOM and ASCII whitespace.
     */
    internal fun looksLikeErrorPage(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (hasValidGeoSiteProtobufHeader(head)) return false
        val index = skipBomAndWhitespace(head)
        if (index >= head.size) return true
        val first = head[index]
        return first == '<'.code.toByte() ||
            first == '{'.code.toByte() ||
            first == '['.code.toByte()
    }

    private fun skipBomAndWhitespace(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size) {
            val bomLen = bomLengthAt(bytes, i)
            if (bomLen > 0) {
                i += bomLen
            } else if (isAsciiWhitespace(bytes[i])) {
                i++
            } else {
                break
            }
        }
        return i
    }

    private fun bomLengthAt(bytes: ByteArray, offset: Int): Int {
        val remaining = bytes.size - offset
        if (hasUtf8Bom(bytes, offset, remaining)) return 3
        if (hasUtf16Bom(bytes, offset, remaining)) return 2
        return 0
    }

    private fun hasUtf8Bom(bytes: ByteArray, offset: Int, remaining: Int): Boolean {
        if (remaining < 3) return false
        return bytes[offset] == 0xEF.toByte() &&
            bytes[offset + 1] == 0xBB.toByte() &&
            bytes[offset + 2] == 0xBF.toByte()
    }

    private fun hasUtf16Bom(bytes: ByteArray, offset: Int, remaining: Int): Boolean {
        if (remaining < 2) return false
        val b0 = bytes[offset]
        val b1 = bytes[offset + 1]
        val isBe = b0 == 0xFE.toByte() && b1 == 0xFF.toByte()
        val isLe = b0 == 0xFF.toByte() && b1 == 0xFE.toByte()
        return isBe || isLe
    }

    private fun isAsciiWhitespace(b: Byte): Boolean =
        when (b) {
            ' '.code.toByte(),
            '\t'.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte() -> true
            else -> false
        }

    /**
     * Validates that [head] begins with a `GeoSiteList` protobuf wire frame:
     * field 1 length-delimited (`0x0A`), a positive base-128 varint length, and a valid inner
     * `GeoSite` field tag (`0x0A` for `country_code` or `0x12` for `domain`).
     */
    internal fun hasValidGeoSiteProtobufHeader(head: ByteArray): Boolean {
        if (head.size < 4 || (head[0].toInt() and 0xFF) != PROTOBUF_OUTER_TAG) return false
        var shift = 0
        var lengthValue = 0L
        var pos = 1
        var terminated = false
        while (pos < head.size && pos <= MAX_VARINT_BYTES) {
            val byteVal = head[pos].toInt() and 0xFF
            lengthValue = lengthValue or ((byteVal and 0x7F).toLong() shl shift)
            pos++
            if ((byteVal and 0x80) == 0) {
                terminated = true
                break
            }
            shift += 7
        }
        if (!terminated || lengthValue <= 0L || pos >= head.size) return false
        val innerTag = head[pos].toInt() and 0xFF
        return innerTag == PROTOBUF_INNER_TAG_COUNTRY || innerTag == PROTOBUF_INNER_TAG_DOMAIN
    }

    /**
     * Validates that [file] contains the MaxMind DB metadata marker (`\xAB\xCD\xEFMaxMind.com`)
     * within its trailing metadata window, followed by at least one metadata byte.
     */
    internal fun hasMaxMindMetadataMarker(file: File): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= MAXMIND_METADATA_MARKER.size) return false
        val tailSize = minOf(length, MAXMIND_TAIL_SEARCH_BYTES.toLong()).toInt()
        val tail = ByteArray(tailSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - tailSize)
            raf.readFully(tail)
        }
        val markerIdx = lastIndexOfSubArray(tail, MAXMIND_METADATA_MARKER)
        return markerIdx >= 0 && (markerIdx + MAXMIND_METADATA_MARKER.size) < tail.size
    }

    private fun lastIndexOfSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in (haystack.size - needle.size) downTo 0) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Replaces [target] with [staged] atomically. `Files.move` with ATOMIC_MOVE is the
     * primary path; some filesystems (older FAT/emulated storage) refuse atomic moves,
     * so the fallback keeps the update working with a plain replace.
     */
    internal fun atomicReplace(staged: File, target: File) {
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
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
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
        }
        if (!moved || !target.isFile || target.length() == 0L) {
            error("atomic replace of ${target.name} failed")
        }
    }
}
