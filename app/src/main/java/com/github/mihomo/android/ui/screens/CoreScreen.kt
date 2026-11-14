@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.kr328.clash.core.util.trafficString
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.DiagnosticsExporter
import com.github.mihomo.android.data.GeoDataManager
import com.github.mihomo.android.data.GeoFile
import com.github.mihomo.android.data.GeoFileInfo
import com.github.mihomo.android.data.MihomoApi
import com.github.mihomo.android.data.RequestRepository
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.service.MihomoVpnService
import com.github.mihomo.android.service.VpnState
import com.github.mihomo.android.ui.components.CupertinoActivityIndicator
import com.github.mihomo.android.ui.components.CupertinoCell
import com.github.mihomo.android.ui.components.CupertinoDivider
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoIndentedDivider
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.components.iosFocusRing
import com.github.mihomo.android.ui.theme.IosBlue
import com.github.mihomo.android.ui.theme.IosGreen
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.LoonWarningText
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ProxyProviderIcon: ImageVector = Icons.Default.Public
private val RuleProviderIcon: ImageVector = Icons.Default.Dns

@Composable
private fun CorePollingEffect(
    isRunning: Boolean,
    apiVersion: String?,
    refreshProviders: () -> Unit,
    onMemoryUpdated: (Long?) -> Unit,
    onApiVersionUpdated: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isRunning, lifecycleOwner) {
        if (!isRunning) {
            onMemoryUpdated(null)
            return@LaunchedEffect
        }
        refreshProviders()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val (mem, ver) = withContext(Dispatchers.IO) {
                    val fetchedMem = MihomoApi.memoryInuse()
                    val fetchedVer = if (apiVersion == null) MihomoApi.version() else apiVersion
                    fetchedMem to fetchedVer
                }
                onMemoryUpdated(mem)
                if (ver != null) onApiVersionUpdated(ver)
                delay(3000L)
            }
        }
    }
}

private fun launchGeoFileUpdate(
    scope: CoroutineScope,
    context: Context,
    info: GeoFileInfo,
    lang: String,
    setUpdatingGeo: (String?) -> Unit,
    refreshGeoFiles: () -> Unit
) {
    val which = GeoFile.all.firstOrNull { candidate -> candidate.fileName == info.fileName } ?: return
    scope.launch {
        setUpdatingGeo(info.fileName)
        val result = withContext(Dispatchers.IO) { GeoDataManager.updateGeoFile(context, which) }
        setUpdatingGeo(null)
        result.fold(
            onSuccess = { newSizeBytes ->
                refreshGeoFiles()
                CupertinoToast.show(
                    AppStrings.get("geo_update_success", lang)
                        .replace("{name}", which.displayName)
                        .replace("{size}", trafficString(newSizeBytes))
                )
            },
            onFailure = { err ->
                CupertinoToast.show(
                    AppStrings.get("geo_update_failed", lang).replace("{msg}", err.message ?: ""),
                    isDestructive = true
                )
            }
        )
    }
}

private data class CoreScreenUiData(
    val lang: String,
    val isRunning: Boolean,
    val versionDisplay: String,
    val connectionsCount: Int,
    val memoryBytes: Long?,
    val startedAtMs: Long,
    val totalTraffic: State<Pair<String, String>>,
    val geoFiles: List<GeoFileInfo>,
    val updatingGeo: String?,
    val exportingDiagnostics: Boolean,
    val ranking: List<Triple<String, Long, Long>>,
    val proxyProviders: List<MihomoApi.ApiProvider>,
    val ruleProviders: List<MihomoApi.ApiProvider>,
    val updatingNames: List<String>
)

private data class CoreScreenCallbacks(
    val onUpdateGeo: (GeoFileInfo) -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onUpdateProvider: (String, Boolean) -> Unit
)

private data class CoreProviderSectionConfig(
    val title: String,
    val icon: ImageVector,
    val iconBg: Color,
    val isRule: Boolean
)

private class CoreScreenState(
    private val scope: CoroutineScope,
    private val context: Context,
    private val lang: String
) {
    var memoryBytes by mutableStateOf<Long?>(null)
    var apiVersion by mutableStateOf<String?>(null)
    var fallbackVersion by mutableStateOf("")
    var proxyProviders by mutableStateOf<List<MihomoApi.ApiProvider>>(emptyList())
    var ruleProviders by mutableStateOf<List<MihomoApi.ApiProvider>>(emptyList())
    val updatingNames = mutableStateListOf<String>()
    var geoFiles by mutableStateOf<List<GeoFileInfo>>(emptyList())
    var updatingGeo by mutableStateOf<String?>(null)
    var exportingDiagnostics by mutableStateOf(false)

    fun refreshGeoFiles() {
        scope.launch { geoFiles = withContext(Dispatchers.IO) { GeoDataManager.geoFiles(context) } }
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

    fun updateProviderItem(name: String, isRule: Boolean) {
        scope.launch {
            updateProvider(
                name = name,
                isRule = isRule,
                onSuccessMessage = AppStrings.get("core_provider_updated", lang).replace("{name}", name),
                onFailureMessage = AppStrings.get("core_provider_update_failed", lang).replace("{name}", name),
                onBusyChanged = { target, busy ->
                    if (busy) updatingNames.add(target) else updatingNames.remove(target)
                },
                refresh = ::refreshProviders
            )
        }
    }
}

@Composable
fun CoreScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current
    val context = LocalContext.current

    val vpnState = MihomoVpnService.vpnState.collectAsStateWithLifecycle()
    val isRunning by remember { derivedStateOf { vpnState.value.status == VpnState.Status.RUNNING } }
    val runningCoreVersion by remember { derivedStateOf { vpnState.value.coreVersion } }
    val startedAtMs by remember { derivedStateOf { vpnState.value.startedAtMs } }
    val totalTraffic = remember { derivedStateOf { vpnState.value.totalUp to vpnState.value.totalDown } }
    val connectionsCount by RequestRepository.activeCountFlow.collectAsStateWithLifecycle()
    val records by RequestRepository.recordsFlow.collectAsStateWithLifecycle()
    val state = remember(scope, context, lang) { CoreScreenState(scope, context, lang) }

    LaunchedEffect(state) {
        state.fallbackVersion = withContext(Dispatchers.IO) { ClashCore.getCoreVersion() }
        state.refreshGeoFiles()
    }
    CorePollingEffect(
        isRunning = isRunning,
        apiVersion = state.apiVersion,
        refreshProviders = state::refreshProviders,
        onMemoryUpdated = { mem -> state.memoryBytes = mem },
        onApiVersionUpdated = { ver -> state.apiVersion = ver }
    )

    val ranking = remember(records) { RequestRepository.topHostsByTraffic(5) }
    val versionDisplay = state.apiVersion ?: runningCoreVersion.ifBlank { state.fallbackVersion.ifBlank { "\u2014" } }
    Column(modifier = Modifier.fillMaxSize().background(LoonBg).statusBarsPadding()) {
        CupertinoNavBar(title = AppStrings.get("core_title", lang), showBackButton = true, onBack = onBack)
        LoonResponsiveContent {
            CoreScreenList(
                data = CoreScreenUiData(
                    lang = lang,
                    isRunning = isRunning,
                    versionDisplay = versionDisplay,
                    connectionsCount = connectionsCount,
                    memoryBytes = state.memoryBytes,
                    startedAtMs = startedAtMs,
                    totalTraffic = totalTraffic,
                    geoFiles = state.geoFiles,
                    updatingGeo = state.updatingGeo,
                    exportingDiagnostics = state.exportingDiagnostics,
                    ranking = ranking,
                    proxyProviders = state.proxyProviders,
                    ruleProviders = state.ruleProviders,
                    updatingNames = state.updatingNames
                ),
                callbacks = CoreScreenCallbacks(
                    onUpdateGeo = { info ->
                        launchGeoFileUpdate(scope, context, info, lang, { next -> state.updatingGeo = next }, state::refreshGeoFiles)
                    },
                    onExportDiagnostics = {
                        if (!state.exportingDiagnostics) {
                            launchDiagnosticsExport(scope, context, lang) { busy -> state.exportingDiagnostics = busy }
                        }
                    },
                    onUpdateProvider = state::updateProviderItem
                )
            )
        }
    }
}

private fun launchDiagnosticsExport(
    scope: CoroutineScope,
    context: Context,
    lang: String,
    onBusyChanged: (Boolean) -> Unit
) {
    scope.launch {
        onBusyChanged(true)
        val result = withContext(Dispatchers.IO) {
            DiagnosticsExporter.exportDiagnostics(context)
        }
        onBusyChanged(false)
        when (result) {
            is DiagnosticsExporter.DiagnosticsExportResult.Success ->
                CupertinoToast.show(
                    AppStrings.get("diagnostics_export_success", lang)
                        .replace("{path}", result.file.name)
                )
            is DiagnosticsExporter.DiagnosticsExportResult.Failure ->
                CupertinoToast.show(
                    AppStrings.get("diagnostics_export_failed", lang)
                        .replace("{msg}", result.message),
                    isDestructive = true
                )
        }
    }
}

@Composable
private fun CoreScreenList(
    data: CoreScreenUiData,
    callbacks: CoreScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .bounceOverscroll(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!data.isRunning) {
            item {
                StatusBanner(
                    text = AppStrings.get("core_not_running", data.lang),
                    color = LoonWarningText
                )
            }
        }
        item {
            CoreVitalsSection(
                lang = data.lang,
                versionDisplay = data.versionDisplay,
                isRunning = data.isRunning,
                connectionsCount = data.connectionsCount,
                memoryBytes = data.memoryBytes,
                startedAtMs = data.startedAtMs,
                totalTraffic = data.totalTraffic
            )
        }
        item {
            GeoDatabaseSection(
                lang = data.lang,
                geoFiles = data.geoFiles,
                updatingGeo = data.updatingGeo,
                exportingDiagnostics = data.exportingDiagnostics,
                onUpdateGeo = callbacks.onUpdateGeo,
                onExportDiagnostics = callbacks.onExportDiagnostics
            )
        }
        item {
            CoreRankingSection(lang = data.lang, ranking = data.ranking)
        }
        item {
            CoreProvidersSection(
                config = CoreProviderSectionConfig(
                    title = AppStrings.get("core_proxies_providers", data.lang),
                    icon = ProxyProviderIcon,
                    iconBg = IosBlue,
                    isRule = false
                ),
                lang = data.lang,
                providers = data.proxyProviders,
                updatingNames = data.updatingNames,
                onUpdateProvider = callbacks.onUpdateProvider
            )
        }
        item {
            CoreProvidersSection(
                config = CoreProviderSectionConfig(
                    title = AppStrings.get("core_rules_providers", data.lang),
                    icon = RuleProviderIcon,
                    iconBg = IosGreen,
                    isRule = true
                ),
                lang = data.lang,
                providers = data.ruleProviders,
                updatingNames = data.updatingNames,
                onUpdateProvider = callbacks.onUpdateProvider
            )
        }
    }
}

@Composable
private fun CoreVitalsSection(
    lang: String,
    versionDisplay: String,
    isRunning: Boolean,
    connectionsCount: Int,
    memoryBytes: Long?,
    startedAtMs: Long,
    totalTraffic: State<Pair<String, String>>
) {
    CupertinoGroupedSection(title = AppStrings.get("core_title", lang)) {
        InfoRow(AppStrings.get("core_version", lang), versionDisplay)
        CupertinoDivider()
        InfoRow(
            AppStrings.get("core_connections", lang),
            if (isRunning) connectionsCount.toString() else "0"
        )
        CupertinoDivider()
        InfoRow(
            AppStrings.get("core_memory", lang),
            memoryBytes?.takeIf { value -> value > 0L }?.let { value -> trafficString(value) } ?: "\u2014"
        )
        CupertinoDivider()
        UptimeInfoRow(startedAtMs = startedAtMs, isRunning = isRunning, lang = lang)
        CupertinoDivider()
        TotalTrafficInfoRow(totalTraffic = totalTraffic, lang = lang)
    }
}

@Composable
private fun GeoDatabaseSection(
    lang: String,
    geoFiles: List<GeoFileInfo>,
    updatingGeo: String?,
    exportingDiagnostics: Boolean,
    onUpdateGeo: (GeoFileInfo) -> Unit,
    onExportDiagnostics: () -> Unit
) {
    CupertinoGroupedSection(title = AppStrings.get("geo_update", lang)) {
        if (geoFiles.isEmpty()) {
            InfoRow(AppStrings.get("core_provider_empty", lang), "")
        } else {
            geoFiles.forEachIndexed { index, info ->
                if (index > 0) CupertinoDivider()
                GeoFileRow(
                    info = info,
                    updating = updatingGeo == info.fileName,
                    onUpdate = { onUpdateGeo(info) }
                )
            }
            CupertinoDivider()
            CupertinoCell(
                title = AppStrings.get("diagnostics_export", lang),
                value = if (exportingDiagnostics) "\u2026" else "",
                showChevron = false,
                onClick = onExportDiagnostics
            )
        }
    }
}

@Composable
private fun CoreRankingSection(
    lang: String,
    ranking: List<Triple<String, Long, Long>>
) {
    CupertinoGroupedSection(title = AppStrings.get("core_traffic_ranking", lang)) {
        if (ranking.isEmpty()) {
            InfoRow(AppStrings.get("core_ranking_empty", lang), "")
        } else {
            ranking.forEachIndexed { index, (host, up, down) ->
                if (index > 0) CupertinoDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = host,
                        color = LoonTextPrimary,
                        style = LoonTextStyles.body(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "\u2191 ${trafficString(up)}  \u2193 ${trafficString(down)}",
                        color = LoonTextSecondary,
                        style = LoonTextStyles.callout()
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreProvidersSection(
    config: CoreProviderSectionConfig,
    lang: String,
    providers: List<MihomoApi.ApiProvider>,
    updatingNames: List<String>,
    onUpdateProvider: (String, Boolean) -> Unit
) {
    CupertinoGroupedSection(title = config.title) {
        if (providers.isEmpty()) {
            InfoRow(AppStrings.get("core_provider_empty", lang), "")
        } else {
            providers.forEachIndexed { index, provider ->
                if (index > 0) CupertinoIndentedDivider()
                ProviderRow(
                    icon = config.icon,
                    iconBg = config.iconBg,
                    provider = provider,
                    updating = provider.name in updatingNames,
                    onUpdate = { onUpdateProvider(provider.name, config.isRule) }
                )
            }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder)
    ) {
        Text(
            text = text,
            color = color,
            style = LoonTextStyles.calloutLarge(),
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
        Text(text = label, color = LoonTextPrimary, style = LoonTextStyles.body())
        Text(text = value, color = LoonTextSecondary, style = LoonTextStyles.calloutLarge())
    }
}

@Composable
private fun GeoFileRow(
    info: GeoFileInfo,
    updating: Boolean,
    onUpdate: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale).withZone(ZoneId.systemDefault())
    }
    val formattedTime = remember(info.lastModifiedMs, dateFormat) {
        dateFormat.format(Instant.ofEpochMilli(info.lastModifiedMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LoonSizes.touchTargetMin)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .clickable(enabled = !updating, onClick = onUpdate)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.fileName,
                color = LoonTextPrimary,
                style = LoonTextStyles.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = AppStrings.get("geo_last_updated", lang).replace("{time}", formattedTime) +
                    " \u00B7 " + trafficString(info.sizeBytes),
                color = LoonTextSecondary,
                style = LoonTextStyles.subtitle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (updating) {
            CupertinoActivityIndicator(
                modifier = Modifier.sizeIn(minWidth = 24.dp),
                color = LoonInfoText
            )
        } else {
            Text(
                text = AppStrings.get("geo_update", lang),
                color = LoonInfoText,
                style = LoonTextStyles.calloutLarge()
            )
        }
    }
}

@Composable
private fun UptimeInfoRow(startedAtMs: Long, isRunning: Boolean, lang: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, startedAtMs, lifecycleOwner) {
        if (!isRunning) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }
    InfoRow(
        AppStrings.get("core_uptime", lang),
        if (isRunning && startedAtMs > 0L) formatUptime(nowMs - startedAtMs, lang) else "\u2014"
    )
}

@Composable
private fun TotalTrafficInfoRow(
    totalTraffic: State<Pair<String, String>>,
    lang: String
) {
    val (up, down) = totalTraffic.value
    InfoRow(AppStrings.get("core_total_traffic", lang), "\u2191 $up   \u2193 $down")
}

@Composable
private fun ProviderRow(
    icon: ImageVector,
    iconBg: Color,
    provider: MihomoApi.ApiProvider,
    updating: Boolean,
    onUpdate: () -> Unit
) {
    val lang = LocalAppLanguage.current
    CupertinoCell(
        title = provider.name,
        subtitle = buildString {
            append(
                AppStrings.get("core_provider_count", lang)
                    .replace("{count}", provider.itemCount.toString())
            )
            append(" \u00B7 ")
            append(provider.vehicleType)
            append(" \u00B7 ")
            append(
                if (provider.updatedAtMs > 0L) relativeTime(provider.updatedAtMs, lang)
                else AppStrings.get("core_provider_never", lang)
            )
        },
        icon = icon,
        iconBg = iconBg,
        showChevron = false,
        trailing = {
            if (updating) {
                Text(
                    text = AppStrings.get("core_provider_updating", lang),
                    color = LoonTextSecondary,
                    style = LoonTextStyles.calloutLarge()
                )
            } else {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = LoonSizes.touchTargetMin, minHeight = LoonSizes.touchTargetMin)
                        .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                        .clickable(onClick = onUpdate),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.get("core_provider_update", lang),
                        color = LoonInfoText,
                        style = LoonTextStyles.body(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
