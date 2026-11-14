@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

private class BoundedLruCache<K : Any, V : Any>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun evictAll() {
        map.clear()
    }
}

/**
 * Group icons come from subscription-controlled URLs, i.e. untrusted input. The loader caps
 * payload bytes, rejects SVG/text documents fast, downsamples bitmaps before decoding, and
 * records short-lived negative cache entries so failing URLs do not storm the network on scroll.
 */
object ImageCache {
    private const val MAX_ICON_BYTES = 512 * 1024
    private const val MAX_ICON_DIMEN_PX = 128
    private const val MAX_DISK_ENTRIES = 200
    private const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L
    private val ASCII_WHITESPACE = byteArrayOf(
        ' '.code.toByte(),
        '\n'.code.toByte(),
        '\r'.code.toByte(),
        '\t'.code.toByte()
    )

    private val memoryCache = BoundedLruCache<String, ImageBitmap>(120)
    private val negativeCache = BoundedLruCache<String, Long>(256)
    private val diskLock = Any()

    @Volatile
    private var diskCacheDir: File? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun initDiskCache(cacheDir: File) {
        if (diskCacheDir != null) return
        synchronized(diskLock) {
            if (diskCacheDir == null) {
                diskCacheDir = File(cacheDir, "icon_cache").apply { mkdirs() }
            }
        }
    }

    private fun getDiskFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
        return File(dir, "$hash.bin")
    }

    fun getMemory(url: String): ImageBitmap? = memoryCache.get(url)

    internal fun isNegativelyCached(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val failedAt = negativeCache.get(url) ?: return false
        if (nowMs - failedAt < NEGATIVE_CACHE_TTL_MS) {
            return true
        }
        negativeCache.remove(url)
        return false
    }

    internal fun markFailed(url: String, nowMs: Long = System.currentTimeMillis()) {
        negativeCache.put(url, nowMs)
    }

    internal fun clearNegativeCache() {
        negativeCache.evictAll()
    }

    fun get(url: String): ImageBitmap? {
        memoryCache.get(url)?.let { cached -> return cached }
        val diskFile = getDiskFile(url) ?: return null
        synchronized(diskLock) {
            if (!diskFile.exists()) return null
            if (diskFile.length() > MAX_ICON_BYTES) {
                runCatching { diskFile.delete() }
                return null
            }
            val loaded = runCatching {
                val bytes = diskFile.readBytes()
                decodeIcon(bytes)?.asImageBitmap()
            }.getOrNull() ?: return null
            memoryCache.put(url, loaded)
            return loaded
        }
    }

    suspend fun load(url: String, context: Context? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        if (diskCacheDir == null && context != null) {
            initDiskCache(context.cacheDir)
        }
        get(url)?.let { cached -> return@withContext cached }
        if (isNegativelyCached(url)) return@withContext null

        try {
            fetchAndDecodeIcon(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markFailed(url)
            null
        }
    }

    private fun fetchAndDecodeIcon(url: String): ImageBitmap? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                markFailed(url)
                return null
            }
            val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            if (contentType.contains("svg") || contentType.startsWith("text/")) {
                markFailed(url)
                return null
            }
            val body = resp.body
            if (body.contentLength() > MAX_ICON_BYTES) {
                markFailed(url)
                return null
            }
            val bytes = body.byteStream().use { stream -> readCapped(stream) }
            if (bytes == null || looksLikeNonBitmapText(bytes)) {
                markFailed(url)
                return null
            }
            val bitmap = decodeIcon(bytes)
            if (bitmap == null) {
                markFailed(url)
                return null
            }
            writeDiskAndPrune(url, bytes)
            val imageBitmap = bitmap.asImageBitmap()
            memoryCache.put(url, imageBitmap)
            return imageBitmap
        }
    }

    private fun writeDiskAndPrune(url: String, bytes: ByteArray) {
        val diskFile = getDiskFile(url) ?: return
        synchronized(diskLock) {
            runCatching {
                diskFile.writeBytes(bytes)
                pruneDiskCache()
            }
        }
    }

    internal fun looksLikeNonBitmapText(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 64)
        var i = 0
        while (i < limit && bytes[i] in ASCII_WHITESPACE) {
            i++
        }
        if (i >= limit) return true
        val prefix = bytes.copyOfRange(i, limit).toString(Charsets.UTF_8).lowercase(Locale.ROOT)
        return prefix.startsWith("<svg") ||
            prefix.startsWith("<?xml") ||
            prefix.startsWith("<!doctype") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("{") ||
            prefix.startsWith("[")
    }

    /** Reads at most [MAX_ICON_BYTES]; returns null for an oversized payload instead of OOMing. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ICON_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_ICON_DIMEN_PX &&
            bounds.outHeight / (sampleSize * 2) >= MAX_ICON_DIMEN_PX
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Keeps the icon cache bounded; oldest files fall out once the entry count is exceeded. */
    private fun pruneDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.filter { file -> file.isFile } ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { file -> file.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { file -> runCatching { file.delete() } }
    }
}

@Composable
fun RemoteIcon(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current

    // Memory-only: disk I/O and BitmapFactory decode run inside LaunchedEffect on Dispatchers.IO.
    var imageBitmap by remember(url) { mutableStateOf(ImageCache.getMemory(url)) }

    LaunchedEffect(url) {
        if (imageBitmap == null) {
            val loaded = ImageCache.load(url, context)
            if (loaded != null) {
                imageBitmap = loaded
            }
        }
    }

    imageBitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}
