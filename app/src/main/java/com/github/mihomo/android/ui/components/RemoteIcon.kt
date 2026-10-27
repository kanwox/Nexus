package com.github.mihomo.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Group icons come from subscription-controlled URLs, i.e. untrusted input. The loader caps
 * the payload and downsamples before decoding so a hostile/huge image cannot OOM the process.
 */
object ImageCache {
    private const val MAX_ICON_BYTES = 512 * 1024
    private const val MAX_ICON_DIMEN_PX = 128
    private const val MAX_DISK_ENTRIES = 200

    private val memoryCache = LruCache<String, ImageBitmap>(120)
    private var diskCacheDir: File? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun initDiskCache(cacheDir: File) {
        if (diskCacheDir == null) {
            diskCacheDir = File(cacheDir, "icon_cache").apply { mkdirs() }
        }
    }

    private fun getDiskFile(url: String): File? {
        val dir = diskCacheDir ?: return null
        val hash = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.bin")
    }

    fun getMemory(url: String): ImageBitmap? = memoryCache.get(url)

    fun get(url: String): ImageBitmap? {
        memoryCache.get(url)?.let { return it }
        val diskFile = getDiskFile(url)
        if (diskFile != null && diskFile.exists()) {
            if (diskFile.length() > MAX_ICON_BYTES) {
                diskFile.delete()
                return null
            }
            runCatching {
                val bytes = diskFile.readBytes()
                val bitmap = decodeIcon(bytes)
                if (bitmap != null) {
                    val imageBitmap = bitmap.asImageBitmap()
                    memoryCache.put(url, imageBitmap)
                    return imageBitmap
                }
            }
        }
        return null
    }

    suspend fun load(url: String, context: Context? = null): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        if (diskCacheDir == null && context != null) {
            initDiskCache(context.cacheDir)
        }
        get(url)?.let { return@withContext it }

        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                // okhttp 5 always has a body on a response.
                val body = resp.body
                if (body.contentLength() > MAX_ICON_BYTES) return@withContext null
                val bytes = body.byteStream().use { readCapped(it) } ?: return@withContext null

                val bitmap = decodeIcon(bytes) ?: return@withContext null
                val diskFile = getDiskFile(url)
                runCatching {
                    diskFile?.writeBytes(bytes)
                    pruneDiskCache()
                }
                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(url, imageBitmap)
                imageBitmap
            }
        }.getOrNull()
    }

    /** Reads at most [MAX_ICON_BYTES]; returns null for an oversized payload instead of OOMing. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
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
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { runCatching { it.delete() } }
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

    // Memory-only: a disk read plus BitmapFactory decode in the composition initializer would
    // block the frame. The LaunchedEffect below performs the (async) load on a miss.
    var imageBitmap by remember(url) { mutableStateOf(ImageCache.getMemory(url)) }

    LaunchedEffect(url) {
        ImageCache.initDiskCache(context.cacheDir)
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
