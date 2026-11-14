package com.github.mihomo.android.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        return runUpdatePass(defaultDependencies(applicationContext))
    }

    internal enum class FailureKind {
        TRANSIENT,
        PERMANENT,
        CANCELLED
    }

    internal data class UpdateDependencies(
        val loadProfiles: () -> List<ProfileItem>,
        val selectedProfileId: () -> String?,
        val isVpnRunning: () -> Boolean,
        val updateSubscription: suspend (ProfileItem, String) -> kotlin.Result<ProfileItem>,
        val reloadActiveConfig: suspend () -> Boolean,
        val archiveKnownGoodVersion: (String) -> Unit,
        val rollbackSubscriptionUpdate: (String) -> Boolean,
        val log: (level: String, message: String, forcePersist: Boolean) -> Unit = { level, message, forcePersist ->
            LogRepository.addLog(level, message, forcePersist = forcePersist)
        },
        val nowMs: () -> Long = { System.currentTimeMillis() }
    )

    companion object {
        private const val WORK_NAME = "subscription_auto_update"
        private const val CHECK_INTERVAL_MINUTES = 30L
        private const val MAX_CAUSE_DEPTH = 8
        private val HTTP_STATUS_REGEX = Regex("""HTTP(?:\s*错误)?\s*[:：]?\s*(\d{3})""")

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

        internal fun defaultDependencies(context: Context): UpdateDependencies {
            val settings = SettingsManager(context)
            return UpdateDependencies(
                loadProfiles = { ConfigManager.getProfiles(context) },
                selectedProfileId = { settings.selectedProfileId },
                isVpnRunning = { MihomoVpnService.vpnState.value.status == VpnState.Status.RUNNING },
                updateSubscription = { profile, url ->
                    ConfigManager.updateSubscription(context, profile.id, profile.name, url)
                },
                reloadActiveConfig = { ClashCore.reloadActiveConfig(context) },
                archiveKnownGoodVersion = { profileId ->
                    ConfigManager.archiveKnownGoodVersion(context, profileId)
                },
                rollbackSubscriptionUpdate = { profileId ->
                    ConfigManager.rollbackSubscriptionUpdate(context, profileId)
                }
            )
        }

        internal suspend fun runUpdatePass(deps: UpdateDependencies): ListenableWorker.Result {
            currentCoroutineContext().ensureActive()
            val now = deps.nowMs()
            val due = deps.loadProfiles().filter { isProfileDue(it, now) }

            var transientFailures = 0
            var permanentFailures = 0
            for (profile in due) {
                currentCoroutineContext().ensureActive()
                val url = profile.url ?: continue
                val result = updateProfileSafely(deps, profile, url)
                currentCoroutineContext().ensureActive()
                val error = result.exceptionOrNull()
                if (error == null) {
                    handleUpdateSuccess(deps, profile)
                } else {
                    when (classifyFailure(error)) {
                        FailureKind.CANCELLED -> throw error
                        FailureKind.TRANSIENT -> {
                            transientFailures++
                            logUpdateFailure(deps, profile, error)
                        }
                        FailureKind.PERMANENT -> {
                            permanentFailures++
                            logUpdateFailure(deps, profile, error)
                        }
                    }
                }
            }

            return when {
                transientFailures > 0 -> ListenableWorker.Result.retry()
                permanentFailures > 0 -> ListenableWorker.Result.failure()
                else -> ListenableWorker.Result.success()
            }
        }

        private fun isProfileDue(profile: ProfileItem, nowMs: Long): Boolean {
            if (!profile.autoUpdate || profile.url.isNullOrBlank()) return false
            val intervalMs = profile.autoUpdateInterval.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
            return (nowMs - profile.lastUpdated) >= intervalMs
        }

        private suspend fun updateProfileSafely(
            deps: UpdateDependencies,
            profile: ProfileItem,
            url: String
        ): kotlin.Result<ProfileItem> {
            return try {
                deps.updateSubscription(profile, url)
            } catch (e: TimeoutCancellationException) {
                kotlin.Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutException) {
                kotlin.Result.failure(e)
            } catch (e: IOException) {
                kotlin.Result.failure(e)
            }
        }

        private suspend fun handleUpdateSuccess(
            deps: UpdateDependencies,
            profile: ProfileItem
        ) {
            deps.log("info", "自动更新订阅成功: ${profile.name}", true)
            if (profile.id != deps.selectedProfileId() || !deps.isVpnRunning()) {
                return
            }
            currentCoroutineContext().ensureActive()
            val reloaded = deps.reloadActiveConfig()
            currentCoroutineContext().ensureActive()
            if (reloaded) {
                // The NEW payload is proven loadable: archive the replaced one
                // into the versioned history ring for manual recovery.
                deps.archiveKnownGoodVersion(profile.id)
                deps.log("info", "已热重载运行中配置: ${profile.name}", true)
                return
            }
            // Complete the update transaction: the new payload cannot be
            // loaded by the core, so restore the previous one. The tunnel
            // keeps running on the in-memory old config until the rollback
            // reload succeeds — never on a config the core rejected.
            val rolledBack = deps.rollbackSubscriptionUpdate(profile.id)
            if (rolledBack) {
                val rollbackReload = deps.reloadActiveConfig()
                currentCoroutineContext().ensureActive()
                deps.log(
                    if (rollbackReload) "warning" else "error",
                    if (rollbackReload) {
                        "已自动回滚并恢复运行: ${profile.name}"
                    } else {
                        "已自动回滚订阅文件，但恢复运行失败: ${profile.name}"
                    },
                    true
                )
            } else {
                deps.log("error", "热重载失败且无可用回滚版本: ${profile.name}", true)
            }
        }

        private fun logUpdateFailure(
            deps: UpdateDependencies,
            profile: ProfileItem,
            error: Throwable
        ) {
            deps.log(
                "warning",
                "自动更新订阅失败 (${profile.name}): ${error.message ?: "未知错误"}",
                false
            )
        }

        internal fun classifyFailure(throwable: Throwable): FailureKind {
            val chain = collectCauseChain(throwable)
            for (cause in chain) {
                if (cause is CancellationException && cause !is TimeoutCancellationException) {
                    return FailureKind.CANCELLED
                }
            }
            for (cause in chain) {
                if (isSsrfBlock(cause)) {
                    return FailureKind.PERMANENT
                }
            }
            for (cause in chain) {
                val kind = classifyNonSsrfThrowable(cause)
                if (kind != null) return kind
            }
            return FailureKind.PERMANENT
        }

        private fun collectCauseChain(throwable: Throwable): List<Throwable> {
            val result = mutableListOf<Throwable>()
            var current: Throwable? = throwable
            while (current != null && result.size < MAX_CAUSE_DEPTH) {
                if (result.any { it === current }) break
                result.add(current)
                current = current.cause
            }
            return result
        }

        private fun isSsrfBlock(throwable: Throwable): Boolean {
            if (throwable is SsrfBlockedException) return true
            if (throwable is UnknownHostException) {
                val msg = throwable.message.orEmpty()
                return msg.startsWith("不允许的主机名") || msg.startsWith("解析到不允许的 IP 地址")
            }
            return false
        }

        private fun classifyNonSsrfThrowable(throwable: Throwable): FailureKind? {
            if (throwable is TimeoutCancellationException || throwable is TimeoutException) {
                return FailureKind.TRANSIENT
            }
            if (throwable is IOException) {
                return FailureKind.TRANSIENT
            }
            return classifyHttpMessage(throwable.message)
        }

        private fun classifyHttpMessage(message: String?): FailureKind? {
            val match = HTTP_STATUS_REGEX.find(message ?: return null) ?: return null
            val code = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
            return when {
                code == 408 || code == 429 || code in 500..599 -> FailureKind.TRANSIENT
                code in 400..499 -> FailureKind.PERMANENT
                else -> null
            }
        }
    }
}
