package com.github.mihomo.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class PerAppProxyMode {
    DISABLED, WHITELIST, BLACKLIST
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class AppFont {
    SYSTEM, IOS
}

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mihomo_settings", Context.MODE_PRIVATE)

    var selectedProfileId: String?
        get() = prefs.getString("selected_profile_id", null)
        set(value) = prefs.edit { putString("selected_profile_id", value) }

    var tunnelMode: String
        get() = prefs.getString("tunnel_mode", "rule") ?: "rule"
        set(value) = prefs.edit { putString("tunnel_mode", value) }

    var tunStack: String
        get() = prefs.getString("tun_stack", "mixed") ?: "mixed"
        set(value) = prefs.edit { putString("tun_stack", value) }

    var perAppProxyMode: PerAppProxyMode
        get() {
            val raw = prefs.getString("per_app_proxy_mode", PerAppProxyMode.DISABLED.name)
            return runCatching { PerAppProxyMode.valueOf(raw!!) }.getOrDefault(PerAppProxyMode.DISABLED)
        }
        set(value) = prefs.edit { putString("per_app_proxy_mode", value.name) }

    var selectedPackages: Set<String>
        get() = prefs.getStringSet("selected_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet("selected_packages", value) }

    var bootOnStartup: Boolean
        get() = prefs.getBoolean("boot_on_startup", false)
        set(value) = prefs.edit { putBoolean("boot_on_startup", value) }

    /** Exposes the mixed port to the local network (LAN sharing). */
    var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan", false)
        set(value) = prefs.edit { putBoolean("allow_lan", value) }

    /** Switches the DNS enhanced mode between fake-ip and redir-host. */
    var fakeIpEnabled: Boolean
        get() = prefs.getBoolean("fake_ip_enabled", true)
        set(value) = prefs.edit { putBoolean("fake_ip_enabled", value) }

    /**
     * Master IPv6 switch. Drives both the core's top-level `ipv6` key and whether the
     * VPN interface routes ::/0 — the two must stay in lockstep or IPv6 traffic either
     * bypasses the tunnel or dies inside it.
     */
    var ipv6Enabled: Boolean
        get() = prefs.getBoolean("ipv6_enabled", false)
        set(value) = prefs.edit { putBoolean("ipv6_enabled", value) }

    /**
     * Access password for LAN proxy clients; the username is fixed to "nexus". Empty means
     * the mixed port is open without authentication. Only injected when allow-lan is on.
     */
    var allowLanPassword: String
        get() = prefs.getString("allow_lan_password", "") ?: ""
        set(value) = prefs.edit { putString("allow_lan_password", value.trim()) }

    var dnsIpv6Enabled: Boolean
        get() = prefs.getBoolean("dns_ipv6_enabled", false)
        set(value) = prefs.edit { putBoolean("dns_ipv6_enabled", value) }

    @Suppress("unused")
    var dnsCacheEnabled: Boolean
        get() = prefs.getBoolean("dns_cache_enabled", true)
        set(value) = prefs.edit { putBoolean("dns_cache_enabled", value) }

    var dnsNameservers: String
        get() = prefs.getString("dns_nameservers", DEFAULT_NAMESERVERS) ?: DEFAULT_NAMESERVERS
        set(value) = prefs.edit { putString("dns_nameservers", value) }

    var dnsFallbackServers: String
        get() = prefs.getString("dns_fallback_servers", DEFAULT_FALLBACK_SERVERS) ?: DEFAULT_FALLBACK_SERVERS
        set(value) = prefs.edit { putString("dns_fallback_servers", value) }

    companion object {
        const val DEFAULT_NAMESERVERS = "https://223.5.5.5/dns-query, https://doh.pub/dns-query, 223.5.5.5, 119.29.29.29"
        const val DEFAULT_FALLBACK_SERVERS = "https://1.1.1.1/dns-query, https://8.8.8.8/dns-query, 1.1.1.1, 8.8.8.8"
    }

    fun getSelectedNode(group: String): String? {
        return prefs.getString("sel_node_$group", null)
    }

    fun setSelectedNode(group: String, node: String) {
        prefs.edit { putString("sel_node_$group", node) }
    }

    fun getAllSelectedNodes(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("sel_node_") && value is String && value.isNotBlank()) {
                result[key.removePrefix("sel_node_")] = value
            }
        }
        return result
    }

    // View mode persistence (TABS or LIST)
    var proxiesViewMode: String
        get() = prefs.getString("proxies_view_mode", "TABS") ?: "TABS"
        set(value) = prefs.edit { putString("proxies_view_mode", value) }

    // Visible shortcuts set
    var visibleShortcuts: Set<String>
        get() {
            val defaultShortcuts = setOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "plugins", "network_share")
            val raw = prefs.getStringSet("visible_shortcuts", defaultShortcuts) ?: defaultShortcuts
            return raw.filter { it != "core_logs" }.toSet()
        }
        set(value) = prefs.edit { putStringSet("visible_shortcuts", value.filter { it != "core_logs" }.toSet()) }

    // Ordered shortcuts list (for reordering)
    var shortcutOrder: List<String>
        get() {
            val defaultOrder = listOf("nodes", "profiles", "request_logs", "dns", "rules", "routing", "plugins", "network_share")
            val raw = prefs.getString("shortcut_order", null)
            val list = if (raw.isNullOrBlank()) {
                defaultOrder
            } else {
                val saved = raw.split(",").filter { it.isNotBlank() }
                (saved + defaultOrder.filter { !saved.contains(it) }).distinct()
            }
            return list.filter { it != "core_logs" }
        }
        set(value) = prefs.edit { putString("shortcut_order", value.filter { it != "core_logs" }.joinToString(",")) }

    // JavaScript Scripting & Rewrite Settings
    var scriptingEnabled: Boolean
        get() = prefs.getBoolean("scripting_enabled", true)
        set(value) = prefs.edit { putBoolean("scripting_enabled", value) }

    var rewriteEnabled: Boolean
        get() = prefs.getBoolean("rewrite_enabled", false)
        set(value) = prefs.edit { putBoolean("rewrite_enabled", value) }

    @Suppress("unused")
    var customScripts: String
        get() = prefs.getString("custom_scripts", "") ?: ""
        set(value) = prefs.edit { putString("custom_scripts", value) }

    @Suppress("unused")
    var customRewrites: String
        get() = prefs.getString("custom_rewrites", "") ?: ""
        set(value) = prefs.edit { putString("custom_rewrites", value) }

    var showGroupIcons: Boolean
        get() = prefs.getBoolean("show_group_icons", true)
        set(value) = prefs.edit { putBoolean("show_group_icons", value) }

    @Suppress("unused")
    var nodeCardSize: String
        get() = prefs.getString("node_card_size", "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit { putString("node_card_size", value) }

    var nodeColumns: Int
        get() = prefs.getInt("node_columns", 1)
        set(value) = prefs.edit { putInt("node_columns", value.coerceIn(1, 3)) }

    var nodeSortMode: String
        get() = prefs.getString("node_sort_mode", "DEFAULT") ?: "DEFAULT"
        set(value) = prefs.edit { putString("node_sort_mode", value) }

    // "info" is the lowest level whose connection/DNS lines still feed the request and DNS
    // views; "debug" makes the core format a log line for every internal event before the
    // screen-off gate in LogRepository can drop it.
    var logLevel: String
        get() = prefs.getString("core_log_level", "info") ?: "info"
        set(value) = prefs.edit { putString("core_log_level", value) }

    var themeMode: ThemeMode
        get() {
            val raw = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
            return runCatching { ThemeMode.valueOf(raw!!) }.getOrDefault(ThemeMode.SYSTEM)
        }
        set(value) = prefs.edit { putString("theme_mode", value.name) }

    var appLanguage: String
        get() = prefs.getString("app_language", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit { putString("app_language", value) }

    var appFont: AppFont
        get() {
            val raw = prefs.getString("app_font", AppFont.SYSTEM.name)
            return runCatching { AppFont.valueOf(raw!!) }.getOrDefault(AppFont.SYSTEM)
        }
        set(value) = prefs.edit { putString("app_font", value.name) }
}
