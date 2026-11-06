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
                    LogRepository.addLog("info", "自动更新订阅成功: ${profile.name}", forcePersist = true)
                    if (profile.id == settings.selectedProfileId &&
                        MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING
                    ) {
                        val reloaded = ClashCore.reloadActiveConfig(context)
                        if (reloaded) {
                            // The NEW payload is proven loadable: archive the replaced one
                            // into the versioned history ring for manual recovery.
                            ConfigManager.archiveKnownGoodVersion(context, profile.id)
                            LogRepository.addLog("info", "已热重载运行中配置: ${profile.name}", forcePersist = true)
                        } else {
                            // Complete the update transaction: the new payload cannot be
                            // loaded by the core, so restore the previous one. The tunnel
                            // keeps running on the in-memory old config until the rollback
                            // reload succeeds — never on a config the core rejected.
                            val rolledBack = ConfigManager.rollbackSubscriptionUpdate(context, profile.id)
                            if (rolledBack) {
                                val rollbackReload = ClashCore.reloadActiveConfig(context)
                                LogRepository.addLog(
                                    if (rollbackReload) "warning" else "error",
                                    if (rollbackReload) "已自动回滚并恢复运行: ${profile.name}"
                                    else "已自动回滚订阅文件，但恢复运行失败: ${profile.name}",
                                    forcePersist = true
                                )
                            } else {
                                LogRepository.addLog(
                                    "error",
                                    "热重载失败且无可用回滚版本: ${profile.name}",
                                    forcePersist = true
                                )
                            }
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
