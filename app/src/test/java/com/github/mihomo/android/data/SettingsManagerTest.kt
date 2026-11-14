package com.github.mihomo.android.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManagerTest {

    @Test
    fun `allowLanPassword encrypts at rest and never persists plaintext`() {
        val prefs = InMemorySharedPreferences()
        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())

        settings.allowLanPassword = "  MyS3cret!Pass  "
        assertEquals("MyS3cret!Pass", settings.allowLanPassword)
        assertNull(prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_LEGACY, null))

        val enc = prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, null)
        assertNotNull(enc)
        assertFalse(enc!!.contains("MyS3cret!Pass"))
        assertTrue(enc.startsWith("sw1:"))

        settings.allowLanPassword = "   "
        assertEquals("", settings.allowLanPassword)
        assertNull(prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, null))
    }

    @Test
    fun `allowLanPassword transparently migrates legacy plaintext preference`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_LEGACY, "legacy-pass-123").apply()

        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())
        assertEquals("legacy-pass-123", settings.allowLanPassword)
        assertNull(
            "legacy plaintext key must be removed after migration",
            prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_LEGACY, null)
        )
        assertNotNull(prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, null))
        assertEquals("legacy-pass-123", settings.allowLanPassword)
    }

    @Test
    fun `allowLanPassword fails closed on tampered ciphertext`() {
        val prefs = InMemorySharedPreferences()
        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())
        settings.allowLanPassword = "secret"

        val valid = prefs.getString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, null)!!
        prefs.edit().putString(SettingsManager.KEY_ALLOW_LAN_PASSWORD_ENC, valid.dropLast(2) + "AA").apply()

        assertEquals("", settings.allowLanPassword)
    }

    @Test
    fun `clearSelectedNodesForProfile removes both node picks and preferred_group key`() {
        val prefs = InMemorySharedPreferences()
        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())

        settings.setSelectedNode("p1", "PROXY", "HK-01")
        settings.setSelectedNode("p1", "AUTO", "JP-01")
        settings.setPreferredGroupName("p1", "PROXY")

        settings.setSelectedNode("p2", "PROXY", "US-01")
        settings.setPreferredGroupName("p2", "GLOBAL")

        settings.clearSelectedNodesForProfile("p1")

        assertNull(settings.getSelectedNode("p1", "PROXY"))
        assertNull(settings.getSelectedNode("p1", "AUTO"))
        assertNull(settings.getPreferredGroupName("p1"))

        assertEquals("US-01", settings.getSelectedNode("p2", "PROXY"))
        assertEquals("GLOBAL", settings.getPreferredGroupName("p2"))
    }

    @Test
    fun `getSelectedNodesForProfile migrates staging keys without leaking or touching other profiles`() {
        val prefs = InMemorySharedPreferences()
        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())

        // Pre-existing selections for another profile must remain untouched.
        settings.setSelectedNode("other-profile", "AUTO", "JP-02")

        // Stage legacy restore entries while selectedProfileId is null.
        settings.restoreLegacySelection("PROXY", "HK-01")
        val stagingKey = "${SettingsManager.LEGACY_GLOBAL_PREFIX}${SettingsManager.LEGACY_STAGING_PROFILE_ID}/PROXY"
        assertEquals("HK-01", prefs.getString(stagingKey, null))

        // First read adopts staging into "p1" and deletes staging key.
        assertEquals(mapOf("PROXY" to "HK-01"), settings.getSelectedNodesForProfile("p1"))
        assertNull("staging key must be deleted after migration", prefs.getString(stagingKey, null))

        // Persisted scoped value remains readable on subsequent reads.
        assertEquals("HK-01", settings.getSelectedNode("p1", "PROXY"))
        assertEquals(mapOf("PROXY" to "HK-01"), settings.getSelectedNodesForProfile("p1"))

        // Other profile's selections stay intact and empty third profile does not inherit staging.
        assertEquals(mapOf("AUTO" to "JP-02"), settings.getSelectedNodesForProfile("other-profile"))
        assertTrue(settings.getSelectedNodesForProfile("p3").isEmpty())
    }

    @Test
    fun `getSelectedNodesForProfile migrates legacy global keys without deleting migrated or other profile keys`() {
        val prefs = InMemorySharedPreferences()
        val settings = SettingsManager(prefs, LanPasswordCipher.softwareForTest())

        settings.setSelectedNode("other-profile", "PROXY", "US-99")
        val legacyProxyKey = "${SettingsManager.LEGACY_GLOBAL_PREFIX}PROXY"
        val legacyStreamKey = "${SettingsManager.LEGACY_GLOBAL_PREFIX}Streaming"
        prefs.edit()
            .putString(legacyProxyKey, "SG-01")
            .putString(legacyStreamKey, "TW-01")
            .apply()

        val migrated = settings.getSelectedNodesForProfile("p1")
        assertEquals(mapOf("PROXY" to "SG-01", "Streaming" to "TW-01"), migrated)

        // Legacy global keys are deleted so they never leak again.
        assertNull(prefs.getString(legacyProxyKey, null))
        assertNull(prefs.getString(legacyStreamKey, null))

        // Migrated entries are persisted in prefs (survive a fresh read) and other profile keys stay intact.
        assertEquals("SG-01", settings.getSelectedNode("p1", "PROXY"))
        assertEquals("TW-01", settings.getSelectedNode("p1", "Streaming"))
        assertEquals(mapOf("PROXY" to "SG-01", "Streaming" to "TW-01"), settings.getSelectedNodesForProfile("p1"))
        assertEquals("US-99", settings.getSelectedNode("other-profile", "PROXY"))
        assertTrue(settings.getSelectedNodesForProfile("p3").isEmpty())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class EditorImpl : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                staged[key] = value
                removals.remove(key)
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                staged[key] = values?.toSet()
                removals.remove(key)
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                staged[key] = value
                removals.remove(key)
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                staged[key] = value
                removals.remove(key)
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                staged[key] = value
                removals.remove(key)
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                staged[key] = value
                removals.remove(key)
            }
            override fun remove(key: String): SharedPreferences.Editor = apply {
                removals.add(key)
                staged.remove(key)
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                for ((k, v) in staged) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }
}
