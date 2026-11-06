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
        get() {
            val v = prefs.getString("tun_stack", "mips") ?: "mips"
            return if (v.equals("gvisor", ignoreCase = true)) "gvisor" else "mips"
        }
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

    /**
     * Whether the user currently wants the tunnel to be running.
     *
     * This is the authoritative "intent" flag that survives a process death: the VPN
     * service's in-memory state is lost when the process is killed, so the tile and the
     * service's null-intent restart path have to consult a persisted value instead.
     * It is set when the tunnel reaches RUNNING and cleared on an explicit stop
     * (closeTunnel) or when the VPN permission is revoked (onRevoke).
     */
    var vpnIntentActive: Boolean
        get() = prefs.getBoolean("vpn_intent_active", false)
        set(value) = prefs.edit { putBoolean("vpn_intent_active", value) }

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

    /** Local mixed HTTP/SOCKS listen port injected into the core (also the LAN share port). */
    var mixedPort: Int
        get() = prefs.getInt("mixed_port", 7890)
        set(value) = prefs.edit { putInt("mixed_port", value.coerceIn(1024, 65535)) }

    /** TUN interface MTU handed to VpnService.Builder.setMtu. */
    var tunMtu: Int
        get() = prefs.getInt("tun_mtu", 9000)
        set(value) = prefs.edit { putInt("tun_mtu", value.coerceIn(1280, 65535)) }

    /**
     * Kill switch: blocks app traffic whenever the VPN interface is up but the tunnel is not
     * connected (setBlocking), so a stalled reconnect can never leak real IPs.
     */
    var killSwitchEnabled: Boolean
        get() = prefs.getBoolean("kill_switch_enabled", true)
        set(value) = prefs.edit { putBoolean("kill_switch_enabled", value) }

    /**
     * Allows apps to bind to a non-VPN network (setAllowBypass) so LAN-only features
     * (casting, printers, hotspots) keep working while the tunnel is up.
     */
    var allowBypassEnabled: Boolean
        get() = prefs.getBoolean("allow_bypass_enabled", true)
        set(value) = prefs.edit { putBoolean("allow_bypass_enabled", value) }

    /**
     * When false, subscription/script URLs pointing at private LAN ranges (192.168/10/172.16,
     * loopback) are rejected — a defense against pasted links probing the local network.
     * Self-hosted subscription converters need this left on.
     */
    var allowPrivateSubscriptionHosts: Boolean
        get() = prefs.getBoolean("allow_private_subscription_hosts", true)
        set(value) = prefs.edit { putBoolean("allow_private_subscription_hosts", value) }

    /**
     * Rejects plaintext third-party DNS (UDP port 53 to any address except the tunnel's own
     * portal) via a top-level REJECT rule. Apps hardcoding 8.8.8.8 then fall back to the
     * system resolver, which the tunnel controls — closing the DNS leak path.
     */
    var blockThirdPartyDns: Boolean
        get() = prefs.getBoolean("block_third_party_dns", false)
        set(value) = prefs.edit { putBoolean("block_third_party_dns", value) }

    companion object {
        const val DEFAULT_NAMESERVERS = "https://223.5.5.5/dns-query, https://doh.pub/dns-query, 223.5.5.5, 119.29.29.29"
        const val DEFAULT_FALLBACK_SERVERS = "https://1.1.1.1/dns-query, https://8.8.8.8/dns-query, 1.1.1.1, 8.8.8.8"

        /**
         * Reserved scope for selections restored from a backup before any profile was
         * active; getSelectedNodesForProfile adopts them on the first profile read.
         */
        const val LEGACY_STAGING_PROFILE_ID = "_legacy_restore_"

        /** Prefix of the pre-isolation global selection keys. */
        private const val LEGACY_GLOBAL_PREFIX = "sel_node_"
    }

    // ------------------------------------------------------------------
    // Per-profile node selection storage
    //
    // The key scope is deliberately the PROFILE, not the group name alone: two
    // subscriptions can both ship a "PROXY" group with same-named nodes, and a
    // global group-name scope carried profile A's choice into profile B (and made
    // B show A's measured delays). Keys are
    //
    //     "sel_node_<profileId>/<group>"
    //
    // Legacy global keys ("sel_node_<group>") are migrated lazily on the first
    // read of each profile (see [getSelectedNodesForProfile]): the legacy map is
    // applied once, then removed so it can never leak into another profile again.
    // ------------------------------------------------------------------

    /** Raw scoped read; returns the saved node for [group] within [profileId] only. */
    fun getSelectedNode(profileId: String, group: String): String? {
        return prefs.getString(selectedNodeKey(profileId, group), null)
    }

    /** Raw scoped write; the choice stays visible to this profile only. */
    fun setSelectedNode(profileId: String, group: String, node: String) {
        prefs.edit { putString(selectedNodeKey(profileId, group), node) }
    }

    /**
     * All scoped selections for one profile, keyed by group name. On the FIRST call
     * for a profile this also consumes the legacy global map: entries are copied
     * into the profile scope and then deleted, so the same group can no longer
     * resolve for a different profile. The write-back is wrapped in runCatching —
     * a read-only prefs process (robolectric tests, content-provider sandboxing)
     * must still be able to read.
     */
    fun getSelectedNodesForProfile(profileId: String): Map<String, String> {
        val prefix = selectedNodePrefix(profileId)
        val result = mutableMapOf<String, String>()
        val legacy = mutableMapOf<String, String>()
        val staging = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            val text = value as? String ?: continue
            if (text.isBlank()) continue
            when {
                key.startsWith(prefix) -> result[key.removePrefix(prefix)] = text
                key.startsWith("sel_node_") -> {
                    val rest = key.removePrefix("sel_node_")
                    if (rest.contains('/')) {
                        val scope = rest.substringBefore('/')
                        if (scope == LEGACY_STAGING_PROFILE_ID) {
                            staging[rest.substringAfter('/')] = text
                        }
                    } else {
                        legacy[rest] = text
                    }
                }
            }
        }
        // Adopt staged backup restores only when this profile has no scoped choices yet.
        if (staging.isNotEmpty() && result.isEmpty()) {
            for ((group, node) in staging) {
                result[group] = node
                runCatching { prefs.edit { putString(selectedNodeKey(profileId, group), node) } }
            }
            runCatching { clearScopedSelections(LEGACY_STAGING_PROFILE_ID) }
        }
        // Migrate pre-isolation global keys on the first read; they belong to whatever
        // profile is being read first, exactly as the old behavior implied.
        if (legacy.isNotEmpty() && result.isEmpty()) {
            for ((group, node) in legacy) {
                result[group] = node
                runCatching { prefs.edit { putString(selectedNodeKey(profileId, group), node) } }
            }
            runCatching { clearScopedSelections(LEGACY_GLOBAL_PREFIX) }
        }
        return result
    }

    /** Removes every selection key in the given scope ("sel_node_<group>" legacy form). */
    private fun clearScopedSelections(prefix: String) {
        val stale = prefs.all.keys.filter { it.startsWith(prefix) }
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }

    /** Clears every scoped selection of [profileId] (used when a profile is deleted). */
    fun clearSelectedNodesForProfile(profileId: String) = clearScopedSelections(selectedNodePrefix(profileId))

    // ------------------------------------------------------------------
    // Per-profile "home" strategy group (the dashboard / proxies default tab).
    //
    // The old code guessed the main group by name ("节点选择"/"PROXY"/"Proxies"), which
    // breaks for subscriptions using different naming. The pin lives in the same
    // profile scope as the node selections: switching profiles switches the pin.
    // ------------------------------------------------------------------

    /** The user-pinned home group of [profileId], or null when left on auto-guess. */
    fun getPreferredGroupName(profileId: String): String? {
        val pinned = prefs.getString(prefGroupKey(profileId), null)?.takeIf { it.isNotBlank() }
        if (pinned == null) {
            // Adopt a staged restore once, same mechanism as the node selections.
            val staged = prefs.getString(prefGroupKey(LEGACY_STAGING_PROFILE_ID), null)
            if (!staged.isNullOrBlank()) {
                runCatching {
                    prefs.edit {
                        putString(prefGroupKey(profileId), staged)
                        remove(prefGroupKey(LEGACY_STAGING_PROFILE_ID))
                    }
                }
                return staged
            }
        }
        return pinned
    }

    /** Pins [groupName] as the home group of [profileId]; null clears the pin. */
    fun setPreferredGroupName(profileId: String, groupName: String?) {
        val key = prefGroupKey(profileId)
        if (groupName.isNullOrBlank()) prefs.edit { remove(key) }
        else prefs.edit { putString(key, groupName) }
    }

    private fun prefGroupKey(profileId: String) = "preferred_group_$profileId"

    /** scoped key: "sel_node_<profileId>/<group>"; the id is a UUID, so '/' is unambiguous. */
    private fun selectedNodeKey(profileId: String, group: String) =
        "${selectedNodePrefix(profileId)}$group"

    private fun selectedNodePrefix(profileId: String) = "sel_node_$profileId/"

    /**
     * Backup round-trip support. Backups store selections under the legacy global key
     * format ("sel_node_<group>") because a backup must stay restorable on older app
     * versions AND because at export time the active profile id is already part of the
     * scoped key (see [getAllSelectedNodesForBackup]). On restore the entries are routed
     * to the profile that is active at restore time (or the first profile) and the raw
     * global key is NOT written — a global key would immediately leak into every profile.
     */
    fun restoreLegacySelection(group: String, node: String) {
        // selectedProfileId is the authoritative target; when it is unset the entries
        // are kept under a reserved staging scope that the next migration pass
        // (getSelectedNodesForProfile) adopts for the first profile being read.
        val scopeId = selectedProfileId ?: LEGACY_STAGING_PROFILE_ID
        prefs.edit { putString(selectedNodeKey(scopeId, group), node) }
    }

    /**
     * Flattens every profile's scoped selections into the legacy global key shape for
     * backup export. Same-name groups across profiles collapse (last wins by profile
     * order) — the same lossy behavior as before, but now deterministic. Also adopts
     * the legacy staging scope if a restore happened before any profile existed.
     */
    fun getAllSelectedNodesForBackup(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            val text = value as? String ?: continue
            if (text.isBlank()) continue
            if (key.startsWith("sel_node_") && !key.removePrefix("sel_node_").contains('/')) {
                // A legacy global key that survived (written by an older version between
                // backup and upgrade): keep exporting it as-is.
                result[key.removePrefix("sel_node_")] = text
            }
        }
        // Scoped entries: the active profile wins over the staging scope.
        val active = selectedProfileId
        for ((scope, group) in scopedSelectionEntries()) {
            if (scope == active || scope == LEGACY_STAGING_PROFILE_ID) {
                result[group] = prefs.getString(selectedNodeKey(scope, group), null) ?: continue
            }
        }
        return result
    }

    /** Enumerates (scopeId, group) pairs from the scoped selection keys. */
    private fun scopedSelectionEntries(): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        for (key in prefs.all.keys) {
            if (!key.startsWith("sel_node_")) continue
            val rest = key.removePrefix("sel_node_")
            val slash = rest.indexOf('/')
            if (slash > 0) entries += rest.substring(0, slash) to rest.substring(slash + 1)
        }
        return entries
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

    var autoCheckUpdate: Boolean
        get() = prefs.getBoolean("auto_check_update", true)
        set(value) = prefs.edit { putBoolean("auto_check_update", value) }

    var lastUpdateCheckTime: Long
        get() = prefs.getLong("last_update_check_time", 0L)
        set(value) = prefs.edit { putLong("last_update_check_time", value) }
}
