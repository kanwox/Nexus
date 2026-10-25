package com.github.mihomo.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        val now = System.currentTimeMillis()
        val due = ConfigManager.getProfiles(context).filter { profile ->
            profile.autoUpdate && !profile.url.isNullOrBlank() &&
                (now - profile.lastUpdated) >= profile.autoUpdateInterval.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
        }
        for (profile in due) {
            val result = ConfigManager.updateSubscription(context, profile.id, profile.name, profile.url!!)
            result.fold(
                onSuccess = {
                    LogRepository.addLog("info", "自动更新订阅成功: ${profile.name}")
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
        private const val MIN_INTERVAL_MINUTES = 15

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
