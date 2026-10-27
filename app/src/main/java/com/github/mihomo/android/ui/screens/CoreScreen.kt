package com.github.mihomo.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.kr328.clash.core.util.trafficString
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.MihomoApi
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.IosOrange
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTypeScale
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ProxyProviderIcon: ImageVector = Icons.Default.Public
private val RuleProviderIcon: ImageVector = Icons.Default.Dns

/**
 * Core status + providers page: live core vitals from the external-controller API, the
 * active-connection traffic ranking, and proxy/rule providers with manual update actions.
 */
@Composable
fun CoreScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = com.github.mihomo.android.i18n.LocalAppLanguage.current
    // Collect the full state once, but derive every value the page actually reads. Reading
    // vpnState directly here would recompose the whole page (providers, ranking, …) on every
    // once-per-second traffic tick; the derived states only invalidate when their value changes.
    val vpnState = MihomoVpnService.vpnState.collectAsStateWithLifecycle()
    val isRunning by remember { derivedStateOf { vpnState.value.status == VpnState.Status.RUNNING } }
    val runningCoreVersion by remember { derivedStateOf { vpnState.value.coreVersion } }
    val startedAtMs by remember { derivedStateOf { vpnState.value.startedAtMs } }
    val totalTraffic = remember {
        derivedStateOf { vpnState.value.totalUp to vpnState.value.totalDown }
    }
    val connectionsCount by RequestRepository.activeCountFlow.collectAsStateWithLifecycle()
    val records by RequestRepository.recordsFlow.collectAsStateWithLifecycle()

    var memoryBytes by remember { mutableStateOf<Long?>(null) }
    var apiVersion by remember { mutableStateOf<String?>(null) }
    var fallbackVersion by remember { mutableStateOf("") }
    var proxyProviders by remember { mutableStateOf<List<MihomoApi.ApiProvider>>(emptyList()) }
    var ruleProviders by remember { mutableStateOf<List<MihomoApi.ApiProvider>>(emptyList()) }
    var updatingNames by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { fallbackVersion = ClashCore.getCoreVersion() }
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    memoryBytes = MihomoApi.memoryInuse()
                    if (apiVersion == null) apiVersion = MihomoApi.version()
                }
                delay(3000L)
            }
        } else {
            memoryBytes = null
        }
    }

    fun refreshProviders() {
        scope.launch {
            val (proxies, rules) = withContext(Dispatchers.IO) {
                MihomoApi.proxyProviders() to MihomoApi.ruleProviders()
            }
            proxyProviders = proxies
            ruleProviders = rules
        }
    }

    LaunchedEffect(Unit) {
        refreshProviders()
    }

    val ranking = remember(records) { RequestRepository.topHostsByTraffic(5) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.current("core_title"),
            showBackButton = true,
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!isRunning) {
                StatusBanner(
                    text = AppStrings.current("core_not_running"),
                    color = LoonWarningText
                )
                Spacer(Modifier.height(16.dp))
            }

            CupertinoGroupedSection(title = AppStrings.current("core_title")) {
                InfoRow(
                    AppStrings.current("core_version"),
                    apiVersion ?: runningCoreVersion.ifBlank { fallbackVersion.ifBlank { "—" } }
                )
                CupertinoIndentedDivider()
                InfoRow(
                    AppStrings.current("core_connections"),
                    if (isRunning) connectionsCount.toString() else "0"
                )
                CupertinoIndentedDivider()
                InfoRow(
                    AppStrings.current("core_memory"),
                    memoryBytes?.takeIf { it > 0L }?.let { trafficString(it) } ?: "—"
                )
                CupertinoIndentedDivider()
                UptimeInfoRow(startedAtMs = startedAtMs, isRunning = isRunning, lang = lang)
                CupertinoIndentedDivider()
                TotalTrafficInfoRow(totalTraffic)
            }

            Spacer(Modifier.height(20.dp))

            CupertinoGroupedSection(title = AppStrings.current("core_traffic_ranking")) {
                if (ranking.isEmpty()) {
                    InfoRow(AppStrings.current("core_ranking_empty"), "")
                } else {
                    ranking.forEachIndexed { index, (host, up, down) ->
                        if (index > 0) CupertinoIndentedDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = host,
                                color = LoonTextPrimary,
                                fontSize = LoonTypeScale.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "↑ ${trafficString(up)}  ↓ ${trafficString(down)}",
                                color = LoonTextSecondary,
                                fontSize = LoonTypeScale.callout
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            CupertinoGroupedSection(title = AppStrings.current("core_proxies_providers")) {
                if (proxyProviders.isEmpty()) {
                    InfoRow(AppStrings.current("core_provider_empty"), "")
                } else {
                    proxyProviders.forEachIndexed { index, provider ->
                        if (index > 0) CupertinoIndentedDivider()
                        ProviderRow(
                            icon = ProxyProviderIcon,
                            iconBg = IosBlue,
                            provider = provider,
                            updating = provider.name in updatingNames,
                            onUpdate = {
                                scope.launch {
                                    val successMessage =
                                        AppStrings.get("core_provider_updated", lang).replace("{name}", provider.name)
                                    val failureMessage =
                                        AppStrings.get("core_provider_update_failed", lang).replace("{name}", provider.name)
                                    updateProvider(
                                        name = provider.name,
                                        isRule = false,
                                        onSuccessMessage = successMessage,
                                        onFailureMessage = failureMessage,
                                        onBusyChanged = { name, busy ->
                                            updatingNames =
                                                if (busy) updatingNames + name else updatingNames - name
                                        },
                                        refresh = { refreshProviders() }
                                    )
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            CupertinoGroupedSection(title = AppStrings.current("core_rules_providers")) {
                if (ruleProviders.isEmpty()) {
                    InfoRow(AppStrings.current("core_provider_empty"), "")
                } else {
                    ruleProviders.forEachIndexed { index, provider ->
                        if (index > 0) CupertinoIndentedDivider()
                        ProviderRow(
                            icon = RuleProviderIcon,
                            iconBg = IosGreen,
                            provider = provider,
                            updating = provider.name in updatingNames,
                            onUpdate = {
                                scope.launch {
                                    val successMessage =
                                        AppStrings.get("core_provider_updated", lang).replace("{name}", provider.name)
                                    val failureMessage =
                                        AppStrings.get("core_provider_update_failed", lang).replace("{name}", provider.name)
                                    updateProvider(
                                        name = provider.name,
                                        isRule = true,
                                        onSuccessMessage = successMessage,
                                        onFailureMessage = failureMessage,
                                        onBusyChanged = { name, busy ->
                                            updatingNames =
                                                if (busy) updatingNames + name else updatingNames - name
                                        },
                                        refresh = { refreshProviders() }
                                    )
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private suspend fun updateProvider(
    name: String,
    isRule: Boolean,
    onSuccessMessage: String,
    onFailureMessage: String,
    onBusyChanged: (String, Boolean) -> Unit,
    refresh: () -> Unit
) {
    onBusyChanged(name, true)
    val success = withContext(Dispatchers.IO) {
        if (isRule) MihomoApi.updateRuleProvider(name) else MihomoApi.updateProxyProvider(name)
    }
    onBusyChanged(name, false)
    if (success) {
        refresh()
        CupertinoToast.show(onSuccessMessage)
    } else {
        CupertinoToast.show(onFailureMessage, isDestructive = true)
    }
}

@Composable
private fun StatusBanner(text: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = LoonCard,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = LoonTypeScale.subhead,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = LoonTextPrimary, fontSize = LoonTypeScale.body)
        Text(text = value, color = LoonTextSecondary, fontSize = LoonTypeScale.subhead)
    }
}

/**
 * Uptime is the only value that changes every second, so it owns its ticker inside a child
 * composable; the surrounding core page is not recomposed on every tick.
 */
@Composable
private fun UptimeInfoRow(startedAtMs: Long, isRunning: Boolean, lang: String) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, startedAtMs) {
        while (isActive && isRunning) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    InfoRow(
        AppStrings.get("core_uptime", lang),
        if (isRunning && startedAtMs > 0L) formatUptime(nowMs - startedAtMs, lang) else "—"
    )
}

/** Reads the traffic strings here so only this row recomposes as bytes accumulate. */
@Composable
private fun TotalTrafficInfoRow(totalTraffic: State<Pair<String, String>>) {
    val (up, down) = totalTraffic.value
    InfoRow(AppStrings.current("core_total_traffic"), "↑ $up   ↓ $down")
}

@Composable
private fun ProviderRow(
    icon: ImageVector,
    iconBg: Color,
    provider: MihomoApi.ApiProvider,
    updating: Boolean,
    onUpdate: () -> Unit
) {
    val lang = com.github.mihomo.android.i18n.LocalAppLanguage.current
    CupertinoCell(
        title = provider.name,
        subtitle = buildString {
            append(
                AppStrings.current("core_provider_count")
                    .replace("{count}", provider.itemCount.toString())
            )
            append(" · ")
            append(provider.vehicleType)
            append(" · ")
            append(
                if (provider.updatedAtMs > 0L) relativeTime(provider.updatedAtMs, lang)
                else AppStrings.current("core_provider_never")
            )
        },
        icon = icon,
        iconBg = iconBg,
        showChevron = false,
        trailing = {
            if (updating) {
                Text(
                    text = AppStrings.current("core_provider_updating"),
                    color = LoonTextSecondary,
                    fontSize = LoonTypeScale.subhead
                )
            } else {
                Text(
                    text = AppStrings.current("core_provider_update"),
                    color = LoonInfoText,
                    fontSize = LoonTypeScale.body,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = onUpdate)
                        // UI-A11Y-01: the label keeps its size, the target is 48dp.
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                )
            }
        }
    )
}

private fun relativeTime(timestampMs: Long, lang: String): String {
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / 60_000L
    return when {
        minutes < 1L -> AppStrings.get("time_just_now", lang)
        minutes < 60L -> AppStrings.get("time_minutes_ago", lang).replace("{n}", minutes.toString())
        minutes < 60L * 24L ->
            AppStrings.get("time_hours_ago", lang).replace("{n}", (minutes / 60L).toString())
        else ->
            AppStrings.get("time_days_ago", lang).replace("{n}", (minutes / 60L / 24L).toString())
    }
}

private fun formatUptime(durationMs: Long, lang: String): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return when {
        hours > 0L -> AppStrings.get("uptime_hours", lang)
            .replace("{h}", hours.toString())
            .replace("{m}", minutes.toString())
        minutes > 0L -> AppStrings.get("uptime_minutes", lang).replace("{m}", minutes.toString())
        else -> AppStrings.get("uptime_seconds", lang).replace("{s}", seconds.toString())
    }
}
