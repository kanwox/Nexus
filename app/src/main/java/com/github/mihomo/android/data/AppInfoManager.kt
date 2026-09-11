package com.github.mihomo.android.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: Drawable? = null
)

object AppInfoManager {
    suspend fun getInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        apps.mapNotNull { appInfo ->
            if (appInfo.packageName == context.packageName) return@mapNotNull null

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()

            InstalledApp(appInfo.packageName, label, isSystem, icon)
        }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }
}
