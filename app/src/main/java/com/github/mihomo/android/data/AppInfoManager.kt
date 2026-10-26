package com.github.mihomo.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: Drawable? = null
)

object AppInfoManager {
    // Caches up to 150 scaled (72x72) bitmaps to keep memory footprint under 4 MB.
    private val iconCache = object : LruCache<String, ImageBitmap>(150) {}

    suspend fun getInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        apps.mapNotNull { appInfo ->
            if (appInfo.packageName == context.packageName) return@mapNotNull null

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            // Do not preload all Drawables synchronously; load on-demand in the UI list to save memory.
            InstalledApp(appInfo.packageName, label, isSystem, null)
        }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    suspend fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(iconCache) {
            iconCache.get(packageName)?.let { return@withContext it }
        }
        val bitmap = runCatching {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            drawable.toBitmap(width = 72, height = 72, config = Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()

        if (bitmap != null) {
            synchronized(iconCache) {
                iconCache.put(packageName, bitmap)
            }
        }
        bitmap
    }
}
