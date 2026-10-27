package com.github.mihomo.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import java.util.concurrent.TimeUnit

/**
 * Periodic pass over profiles with auto-update enabled. Each profile is refreshed only when
 * its own interval has elapsed since its last successful update, so one worker serves every
 * profile regardless of the per-profile interval the user picked.
 */
class SubscriptionUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsManager(context)
        val now = System.currentTimeMillis()
        val due = ConfigManager.getProfiles(context).filter { profile ->
            profile.autoUpdate && !profile.url.isNullOrBlank() &&
                (now - profile.lastUpdated) >= profile.autoUpdateInterval.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
        }
        for (profile in due) {
            val result = ConfigManager.updateSubscription(context, profile.id, profile.name, profile.url!!)
            result.fold(
                onSuccess = {
                    // The level is "warning" ON PURPOSE: LogRepository drops every
                    // non-error/warning line while the screen is off, so an "info" entry
                    // left NO trace at all of a successful background update — only the
                    // failures survived. "warning" is the level that persists, and the
                    // message text is phrased so it does not read like a problem.
                    LogRepository.addLog("warning", "自动更新订阅成功: ${profile.name}")
                    if (profile.id == settings.selectedProfileId &&
                        MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING
                    ) {
                        val reloaded = ClashCore.reloadActiveConfig(context)
                        if (reloaded) {
                            LogRepository.addLog("warning", "已热重载运行中配置: ${profile.name}")
                        } else {
                            LogRepository.addLog("warning", "热重载运行中配置失败: ${profile.name}")
                        }
                    }
                },
                onFailure = {
                    LogRepository.addLog(
                        "warning",
                        "自动更新订阅失败 (${profile.name}): ${it.message ?: "未知错误"}"
                    )
                }
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "subscription_auto_update"
        private const val CHECK_INTERVAL_MINUTES = 30L

        /** Floor for user-entered auto-update intervals; shared with the profile editor UI. */
        const val MIN_INTERVAL_MINUTES = 15

        /** Idempotent; called from Application.onCreate so scheduling survives reboots. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
