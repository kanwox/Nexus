package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the backup settings snapshot.
 *
 * [BackupManager.SETTINGS_SNAPSHOT_KEYS] is the single list of backed-up settings; the
 * snapshot writer and the restore reader must agree on it, otherwise a setting is
 * written into the backup but never read back (or the other way round).
 */
class BackupManagerSettingsTest {

    @Test
    fun `the snapshot key list has no duplicates`() {
        val keys = BackupManager.SETTINGS_SNAPSHOT_KEYS
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the snapshot covers the settings that carry user state`() {
        val keys = BackupManager.SETTINGS_SNAPSHOT_KEYS
        // The LAN password and switch must be part of the snapshot: they are the
        // security-relevant pair that the restore has to handle together.
        assertTrue("allowLan" in keys)
        assertTrue("allowLanPassword" in keys)
        assertTrue("appFont" in keys)
        assertTrue("themeMode" in keys)
        assertTrue("blockThirdPartyDns" in keys)
        assertTrue("allowPrivateSubscriptionHosts" in keys)
    }

    @Test
    fun `the snapshot key list matches the keys the restore reads`() {
        // The restore reads exactly these keys (plus the per-group "sel_node_*" picks).
        val restoredByRestore = setOf(
            "tunnelMode", "tunStack", "perAppProxyMode", "selectedPackages", "bootOnStartup",
            "allowLan", "fakeIpEnabled", "ipv6Enabled", "allowLanPassword", "dnsIpv6Enabled",
            "dnsNameservers", "dnsFallbackServers", "mixedPort", "tunMtu", "killSwitchEnabled",
            "allowBypassEnabled", "allowPrivateSubscriptionHosts", "blockThirdPartyDns",
            "proxiesViewMode", "visibleShortcuts", "shortcutOrder", "scriptingEnabled",
            "showGroupIcons", "nodeColumns", "nodeSortMode", "logLevel", "themeMode",
            "appLanguage", "appFont"
        )
        assertEquals(restoredByRestore, BackupManager.SETTINGS_SNAPSHOT_KEYS.toSet())
    }
}
