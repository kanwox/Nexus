package com.github.mihomo.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.TunInterface
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

object ClashCore {
    private const val TAG = "ClashCore"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var initialized = false

    var isCoreLoaded = false
        private set

    fun notifyNetworkChanged(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(activeNet)
            val dnsServers = linkProps?.dnsServers?.mapNotNull { it.hostAddress }?.filter { !it.contains(":") } ?: emptyList()
            val dnsStr = if (dnsServers.isNotEmpty()) dnsServers.joinToString(",") else "223.5.5.5,119.29.29.29"
            Bridge.nativeNotifyDnsChanged(dnsStr)
            Bridge.nativeNotifyTimeZoneChanged(
                java.util.TimeZone.getDefault().id,
                java.util.TimeZone.getDefault().rawOffset / 1000
            )
            Log.i(TAG, "Notified native core DNS: $dnsStr")
        }.onFailure {
            Log.w(TAG, "Failed to notify DNS to native core", it)
        }
    }

    fun init(context: Context) {
        if (!initialized) {
            Bridge.init(context)
            initialized = true
            notifyNetworkChanged(context)
            Log.i(TAG, "Mihomo Core initialized. Version: ${getCoreVersion()}")
        }
    }

    suspend fun ensureCoreLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isCoreLoaded) {
            notifyNetworkChanged(context)
            return@withContext true
        }
        runCatching {
            init(context)
            val settings = com.github.mihomo.android.data.SettingsManager(context)
            val profiles = com.github.mihomo.android.data.ConfigManager.getProfiles(context)
            val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
            if (activeProfile != null && activeProfile.file.exists()) {
                val configFile = com.github.mihomo.android.data.ConfigManager.prepareConfig(context, activeProfile.file)
                val res = load(configFile)
                if (res.isSuccess) {
                    isCoreLoaded = true
                    startHttp("127.0.0.1:10809")
                    notifyNetworkChanged(context)
                    return@withContext true
                }
            }
            false
        }.getOrDefault(false)
    }

    fun getCoreVersion(): String {
        return try {
            Bridge.nativeCoreVersion()
        } catch (e: Throwable) {
            "Unknown (${e.message})"
        }
    }

    suspend fun load(configFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deferred = CompletableDeferred<Unit>()
            val configDir = if (configFile.isDirectory) configFile.absolutePath else (configFile.parentFile?.absolutePath ?: configFile.absolutePath)
            Log.i(TAG, "Loading core config from directory: $configDir (target file: ${configFile.name})")
            Bridge.nativeLoad(deferred, configDir)
            deferred.await()
            isCoreLoaded = true
            startHttp("127.0.0.1:10809")
            Unit
        }
    }

    fun startTun(
        fd: Int,
        stack: String = "mixed",
        gateway: String = "172.19.0.1/30,fdfe:dcba:9876::1/126",
        portal: String = "172.19.0.2,fdfe:dcba:9876::2",
        dns: String = "172.19.0.2,fdfe:dcba:9876::2",
        vpnService: VpnService
    ) {
        Log.i(TAG, "Starting TUN with fd=$fd, stack=$stack")
        val cm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } else null

        Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                vpnService.protect(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
                    try {
                        val srcParts = source.split(":")
                        val dstParts = target.split(":")
                        if (srcParts.size == 2 && dstParts.size == 2) {
                            val srcAddr = InetSocketAddress(srcParts[0], srcParts[1].toInt())
                            val dstAddr = InetSocketAddress(dstParts[0], dstParts[1].toInt())
                            return cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
                        }
                    } catch (_: Throwable) {
                    }
                }
                return 0
            }
        })
    }

    fun stopTun() {
        Log.i(TAG, "Stopping TUN")
        runCatching { Bridge.nativeStopTun() }
    }

    fun startHttp(listenAt: String = "127.0.0.1:10809"): String? {
        return runCatching { Bridge.nativeStartHttp(listenAt) }.getOrNull()
    }

    fun stopHttp() {
        runCatching { Bridge.nativeStopHttp() }
    }

    fun queryTrafficNow(): Traffic {
        return runCatching { Bridge.nativeQueryTrafficNow() }.getOrDefault(0L)
    }

    fun queryTrafficTotal(): Traffic {
        return runCatching { Bridge.nativeQueryTrafficTotal() }.getOrDefault(0L)
    }

    fun queryTunnelState(): TunnelState {
        return runCatching {
            val res = Bridge.nativeQueryTunnelState()
            json.decodeFromString<TunnelState>(res)
        }.getOrDefault(TunnelState("rule"))
    }

    fun queryGroupNames(excludeNotSelectable: Boolean = false): List<String> {
        return runCatching {
            val raw = Bridge.nativeQueryGroupNames(excludeNotSelectable)
            val array = json.decodeFromString<JsonArray>(raw)
            array.mapNotNull { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
    }

    fun queryGroup(name: String, sort: ProxySort = ProxySort.Default): ProxyGroup? {
        return runCatching {
            val raw = Bridge.nativeQueryGroup(name, sort.name)
            if (!raw.isNullOrEmpty()) {
                json.decodeFromString<ProxyGroup>(raw)
            } else null
        }.getOrNull()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return runCatching {
            Bridge.nativePatchSelector(selector, name)
        }.getOrDefault(false)
    }

    suspend fun healthCheckGroupNative(name: String): Map<String, Int> = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Unit>()
        try {
            Bridge.nativeHealthCheck(deferred, name)
            withTimeout(5500L) {
                deferred.await()
            }
        } catch (_: Throwable) {
        }
        val group = queryGroup(name)
        val result = mutableMapOf<String, Int>()
        group?.proxies?.forEach { p ->
            val d = if (p.delay in 1..65534) p.delay else if (p.delay >= 65535 || p.delay == -2) -2 else -1
            result[p.name] = d
        }
        result
    }

    suspend fun healthCheck(name: String): Map<String, Int> = healthCheckGroupNative(name)

    suspend fun testProxyDelayNative(proxyName: String, groupName: String? = null): Int = withContext(Dispatchers.IO) {
        if (proxyName.equals("DIRECT", ignoreCase = true)) return@withContext 0
        if (proxyName.equals("REJECT", ignoreCase = true)) return@withContext -2
        if (proxyName.equals("COMPATIBLE", ignoreCase = true)) return@withContext -2

        val deferred = CompletableDeferred<Unit>()
        try {
            Bridge.nativeHealthCheck(deferred, proxyName)
            withTimeout(5500L) {
                deferred.await()
            }
        } catch (_: Throwable) {
        }

        if (!groupName.isNullOrBlank()) {
            val group = queryGroup(groupName)
            val p = group?.proxies?.find { it.name == proxyName }
            if (p != null) {
                return@withContext if (p.delay in 1..65534) p.delay else -2
            }
        }

        val groups = queryGroupNames()
        for (g in groups) {
            val group = queryGroup(g)
            val p = group?.proxies?.find { it.name == proxyName }
            if (p != null) {
                return@withContext if (p.delay in 1..65534) p.delay else -2
            }
        }
        -2
    }

    fun healthCheckAll() {
        runCatching { Bridge.nativeHealthCheckAll() }
    }

    fun reset() {
        isCoreLoaded = false
        runCatching { Bridge.nativeReset() }
    }
}




