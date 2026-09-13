package com.github.mihomo.android.ui.components

import android.content.Context
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ImageCache {
    private val memoryCache = LruCache<String, ImageBitmap>(120)
    private var diskCacheDir: File? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
            runCatching {
                val bytes = diskFile.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
                val bytes = resp.body?.bytes() ?: return@withContext null

                // Write to disk cache
                val diskFile = getDiskFile(url)
                diskFile?.writeBytes(bytes)

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(url, imageBitmap)
                imageBitmap
            }
        }.getOrNull()
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
