package com.github.mihomo.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdateDownloadManagerTest {

    @Test
    fun `computeUpdateProgressPercent returns null for unknown length and clamps percentage`() {
        assertNull(computeUpdateProgressPercent(1024L, -1L))
        assertNull(computeUpdateProgressPercent(0L, 0L))
        assertEquals(0, computeUpdateProgressPercent(0L, 1000L))
        assertEquals(50, computeUpdateProgressPercent(500L, 1000L))
        assertEquals(100, computeUpdateProgressPercent(1000L, 1000L))
        assertEquals(100, computeUpdateProgressPercent(1500L, 1000L))
    }

    @Test
    fun `canReuseReadyDownload validates matching asset and existing file`() {
        val tempFile = Files.createTempFile("nexus-update-test", ".apk").toFile()
        try {
            val ready = UpdateDownloadUiState.ReadyToInstall(
                assetName = "Nexus-arm64.apk",
                file = tempFile,
                verified = true
            )
            assertTrue(canReuseReadyDownload(ready, "Nexus-arm64.apk"))
            assertTrue(canReuseReadyDownload(ready, null))
            assertFalse(canReuseReadyDownload(ready, "Nexus-armeabi-v7a.apk"))
            assertFalse(canReuseReadyDownload(UpdateDownloadUiState.Idle, "Nexus-arm64.apk"))
            assertFalse(
                canReuseReadyDownload(
                    UpdateDownloadUiState.Downloading("Nexus-arm64.apk", 35),
                    "Nexus-arm64.apk"
                )
            )
        } finally {
            tempFile.delete()
        }

        val missing = UpdateDownloadUiState.ReadyToInstall(
            assetName = "Nexus-arm64.apk",
            file = File(tempFile.absolutePath),
            verified = true
        )
        assertFalse(canReuseReadyDownload(missing, "Nexus-arm64.apk"))
    }
}
