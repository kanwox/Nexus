package com.github.mihomo.android.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class ConfigManagerPhase3Test {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        ProfileStore.resetForTest()
        tempDir = Files.createTempDirectory("cm-phase3-").toFile()
    }

    @After
    fun tearDown() {
        ProfileStore.resetForTest()
        tempDir.deleteRecursively()
    }

    @Test
    fun `resolveSubscriptionYaml decodes standard and url-safe base64 clash yaml`() {
        val plainYaml = "proxies:\n  - name: hk\n    type: ss\n    server: 1.2.3.4\n    port: 443\n"
        val stdB64 = Base64.getEncoder().encodeToString(plainYaml.toByteArray(Charsets.UTF_8))
        val urlSafeB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plainYaml.toByteArray(Charsets.UTF_8))
            .chunked(24)
            .joinToString("\n")

        assertEquals(plainYaml.trim(), ConfigValidation.resolveSubscriptionYaml(stdB64))
        assertEquals(plainYaml.trim(), ConfigValidation.resolveSubscriptionYaml(urlSafeB64))
        assertTrue(ConfigManager.isValidSubscriptionPayload(urlSafeB64))

        val b64Html = Base64.getEncoder().encodeToString("<html>error</html>".toByteArray(Charsets.UTF_8))
        assertNull(ConfigValidation.resolveSubscriptionYaml(b64Html))
        assertFalse(ConfigManager.isValidSubscriptionPayload("vmess://eyJhZGQiOiIxLjEuMS4xIn0="))
    }

    @Test
    fun `validateConfigStructure accepts legacy keys and rejects invalid rules and multi-doc`() {
        val legacyYaml = """
            Proxy:
              - name: legacy-ss
                type: ss
            Proxy Group:
              - name: Auto
                type: select
                proxies: [legacy-ss]
            Rule:
              - DOMAIN,example.com,Auto
              - FINAL,DIRECT
        """.trimIndent()
        assertNull(ConfigManager.validateConfigStructure(legacyYaml))

        assertNotNull(ConfigManager.validateConfigStructure("proxies: []\n---\nmode: global\n"))
        assertNotNull(ConfigManager.validateConfigStructure("rules:\n  - DOMAIN,example.com\n"))
        assertNotNull(ConfigManager.validateConfigStructure("rules:\n  - DOMAIN,example.com, \n"))
    }

    @Test
    fun `extractProfileName handles case-insensitive filename star, quoted semicolons, and control chars`() {
        val responseStar = mockResponse(
            "https://sub.example.com/dl",
            "attachment; FILENAME*=utf-8''Hong%20Kong%20VIP.yaml; filename=\"fallback.yaml\""
        )
        assertEquals(
            "Hong Kong VIP",
            ConfigManager.extractProfileName(null, responseStar, "", "https://sub.example.com/dl")
        )

        val responseQuotedSemi = mockResponse(
            "https://sub.example.com/dl",
            "attachment; filename=\"Node;Pool.yml\"; size=42"
        )
        assertEquals(
            "Node;Pool",
            ConfigManager.extractProfileName(null, responseQuotedSemi, "", "https://sub.example.com/dl")
        )

        assertEquals(
            "CleanName",
            ConfigManager.extractProfileName("  Clean\r\nName\u0000 ", null, "", "https://sub.example.com/dl")
        )
    }

    @Test
    fun `sanitizeUserConfig rejects non-map root and places dns reject before prependRules`() {
        val listOut = ConfigManager.sanitizeUserConfig(
            raw = "- item1\n- item2\n",
            overrides = ConfigOverrides(prependRules = listOf("DOMAIN,first.example,DIRECT")),
            blockThirdPartyDns = true
        )
        val parsedListOut = YamlSupport.create().load<Any>(listOut) as Map<*, *>
        val listRules = parsedListOut["rules"] as List<*>
        assertEquals(ConfigManager.buildDnsRejectRule(), listRules[0])
        assertEquals("DOMAIN,first.example,DIRECT", listRules[1])

        val mergedOut = ConfigManager.sanitizeUserConfig(
            raw = "proxies: []\n---\nexternal-controller: 0.0.0.0:9090\n",
            overrides = ConfigOverrides(prependRules = listOf("DOMAIN,top.example,DIRECT")),
            blockThirdPartyDns = true
        )
        assertFalse(mergedOut.contains("external-controller"))
        val parsedMerged = YamlSupport.create().load<Any>(mergedOut) as Map<*, *>
        val rules = parsedMerged["rules"] as List<*>
        assertEquals(ConfigManager.buildDnsRejectRule(), rules[0])
        assertEquals("DOMAIN,top.example,DIRECT", rules[1])
    }

    @Test
    fun `sanitizeUserConfigByText handles uniform indent, preserves node port, truncates docs, and strips flow keys`() {
        val indentedBroken = """
              mode: global
              external-controller: 0.0.0.0:9090
              proxies:
                - name: n1
                  type: ss
                  port: 443
              broken: [
            ---
            secret: leaked
        """.trimIndent()
        val cleaned = ConfigValidation.sanitizeUserConfigByText(indentedBroken)
        assertFalse(cleaned.contains("mode: global"))
        assertFalse(cleaned.contains("external-controller"))
        assertFalse(cleaned.contains("secret: leaked"))
        assertTrue(cleaned.contains("port: 443"))

        val flowBroken = """
            {"external-controller": "0.0.0.0:9090", "proxies": []
            broken: [
        """.trimIndent()
        val cleanedFlow = ConfigValidation.sanitizeUserConfigByText(flowBroken)
        assertFalse(cleanedFlow.contains("external-controller"))
        assertTrue(cleanedFlow.contains("proxies"))
    }

    @Test
    fun `newestProfileHistoryFile returns v1 newest slot and deleteProfile removes history and prefs`() {
        val ctx = TestProfileContext(tempDir)
        val item = ConfigManager.saveLocalProfile(ctx, "Work", "proxies: []\nrules:\n  - MATCH,DIRECT\n")
        val dir = ProfileStore.getProfilesDir(ctx)
        File(dir, "${item.id}.yaml.v1").writeText("proxies:\n  - name: newest\n    type: ss\n")
        File(dir, "${item.id}.yaml.v2").writeText("proxies:\n  - name: mid\n    type: ss\n")
        File(dir, "${item.id}.yaml.v3").writeText("proxies:\n  - name: oldest\n    type: ss\n")
        File(dir, "${item.id}.yaml.bak").writeText("proxies:\n  - name: bak\n    type: ss\n")
        File(dir, "${item.id}.yaml.staged").writeText("staged")
        File(dir, "${item.id}.yaml.123.tmp").writeText("tmp")

        val newest = ConfigManager.newestProfileHistoryFile(ctx, item.id)
        assertNotNull(newest)
        assertEquals("${item.id}.yaml.v1", newest!!.name)

        // Encoded base64 history slot should be decoded when restored
        val base64V1 = Base64.getEncoder().encodeToString("proxies:\n  - name: decoded\n    type: ss\n".toByteArray())
        newest.writeText(base64V1)
        assertTrue(ConfigManager.restoreProfileFromHistory(ctx, item.id, newest).isSuccess)
        assertTrue(item.file.readText().contains("name: decoded"))

        val prefs = ctx.getSharedPreferences("mihomo_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("preferred_group_${item.id}", "Proxy").apply()
        ConfigManager.deleteProfile(ctx, item.id)

        assertFalse(item.file.exists())
        assertFalse(File(dir, "${item.id}.yaml.v1").exists())
        assertFalse(File(dir, "${item.id}.yaml.v2").exists())
        assertFalse(File(dir, "${item.id}.yaml.v3").exists())
        assertFalse(File(dir, "${item.id}.yaml.bak").exists())
        assertFalse(File(dir, "${item.id}.yaml.staged").exists())
        assertFalse(File(dir, "${item.id}.yaml.123.tmp").exists())
        assertNull(prefs.getString("preferred_group_${item.id}", null))
    }

    @Test
    fun `commitSubscriptionUpdate aborts when profile deleted mid-download and preserves rename`() {
        val ctx = TestProfileContext(tempDir)
        val item = ConfigManager.saveLocalProfile(ctx, "InitialName", "proxies: []\n")

        // Rename profile while a background download is in flight
        ConfigManager.updateProfile(ctx, item.copy(name = "RenamedByUser"))
        val staged = ProfileStore.profileStagedFile(ctx, item.id)!!
        staged.writeText("proxies:\n  - name: updated\n    type: ss\n")

        val committed = ProfileStore.commitSubscriptionUpdate(
            context = ctx,
            id = item.id,
            fallbackName = "InitialName",
            url = "https://sub.example.com/sub",
            file = item.file,
            stagedFile = staged,
            backupFile = ProfileStore.profileBackupFile(ctx, item.id),
            userInfo = null
        )
        assertEquals("RenamedByUser", committed.name)
        assertTrue(item.file.readText().contains("updated"))

        // If profile was deleted before commit, staged file is cleaned up and commit fails
        ConfigManager.deleteProfile(ctx, item.id)
        staged.writeText("proxies:\n  - name: ghost\n    type: ss\n")
        val failed = runCatching {
            ProfileStore.commitSubscriptionUpdate(
                context = ctx,
                id = item.id,
                fallbackName = "InitialName",
                url = "https://sub.example.com/sub",
                file = item.file,
                stagedFile = staged,
                backupFile = null,
                userInfo = null
            )
        }
        assertTrue(failed.isFailure)
        assertFalse(item.file.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun `migrateLegacyScriptScopes binds enabled script ids once without overwriting subsequent edits`() {
        val ctx = TestProfileContext(tempDir)
        val enabledScript = ScriptManager.saveScript(
            context = ctx,
            name = "Rewrite",
            code = "function main(c) { return c; }",
            enabled = true
        )
        ScriptManager.saveScript(
            context = ctx,
            name = "Disabled",
            code = "function main(c) { return c; }",
            enabled = false
        )
        val legacyProfile = ConfigManager.saveLocalProfile(ctx, "Legacy", "proxies: []\n")
            .copy(scriptEnabled = true, scriptIds = emptyList())
        ConfigManager.updateProfile(ctx, legacyProfile)

        ConfigManager.migrateLegacyScriptScopes(ctx)
        val afterFirstMigration = ConfigManager.getProfiles(ctx, forceReload = true).single()
        assertEquals(listOf(enabledScript.id), afterFirstMigration.scriptIds)

        // User intentionally clears scriptIds afterwards; migration flag prevents re-populating
        ConfigManager.updateProfile(ctx, afterFirstMigration.copy(scriptIds = emptyList()))
        ConfigManager.migrateLegacyScriptScopes(ctx)
        val afterSecondCall = ConfigManager.getProfiles(ctx, forceReload = true).single()
        assertTrue(afterSecondCall.scriptIds.isEmpty())
    }

    private fun mockResponse(url: String, contentDisposition: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Disposition", contentDisposition)
            .body("".toResponseBody(null))
            .build()

    private class TestProfileContext(private val rootDir: File) : ContextWrapper(null) {
        private val prefsMap = mutableMapOf<String, InMemoryPrefs>()

        override fun getFilesDir(): File = rootDir

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefsMap.getOrPut(name ?: "default") { InMemoryPrefs() }
    }

    private class InMemoryPrefs : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(data)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true; pending.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) target.clear()
                for ((k, v) in pending) {
                    if (v == null) target.remove(k) else target[k] = v
                }
            }
        }
    }
}
