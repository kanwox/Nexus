package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the path-traversal guard.
 *
 * Profile ids and script file names are persisted metadata (and arrive from restored
 * backups), so they end up in file paths. A value like `../../databases/x` must never
 * escape the intended directory.
 */
class SafeFileNameTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "safe-file-name-test").apply {
        mkdirs()
    }

    @Test
    fun `plain names are accepted`() {
        assertEquals("abc-123_x.js", SafeFileName.sanitize("abc-123_x.js"))
        assertEquals("config.yaml", SafeFileName.sanitize("config.yaml"))
        assertEquals("a", SafeFileName.sanitize("a"))
    }

    @Test
    fun `names with separators or traversal segments are rejected`() {
        assertNull(SafeFileName.sanitize("../databases/foo"))
        assertNull(SafeFileName.sanitize("../../etc/passwd"))
        assertNull(SafeFileName.sanitize("dir/file.js"))
        assertNull(SafeFileName.sanitize("dir\\file.js"))
        assertNull(SafeFileName.sanitize("/absolute/path"))
    }

    @Test
    fun `dot names are rejected`() {
        assertNull(SafeFileName.sanitize("."))
        assertNull(SafeFileName.sanitize(".."))
    }

    @Test
    fun `empty and null names are rejected`() {
        assertNull(SafeFileName.sanitize(null))
        assertNull(SafeFileName.sanitize(""))
        assertNull(SafeFileName.sanitize("   "))
    }

    @Test
    fun `overly long names are rejected`() {
        assertNull(SafeFileName.sanitize("a".repeat(201)))
    }

    @Test
    fun `resolveChild returns a direct child of the base directory`() {
        val child = SafeFileName.resolveChild(base, "profile.yaml")
        assertNotNull(child)
        assertEquals(base.canonicalFile, child!!.parentFile?.canonicalFile)
        assertEquals("profile.yaml", child.name)
    }

    @Test
    fun `resolveChild returns null for a traversal attempt`() {
        assertNull(SafeFileName.resolveChild(base, "../../databases/foo"))
        assertNull(SafeFileName.resolveChild(base, ".."))
        assertNull(SafeFileName.resolveChild(base, "sub/dir/file"))
        assertNull(SafeFileName.resolveChild(base, null))
    }

    @Test
    fun `resolveChild never leaves the base directory`() {
        val child = SafeFileName.resolveChild(base, "..%2Fescape.yaml")
        // The percent sign is not in the allowed character set, so it is rejected.
        assertNull(child)
    }
}
