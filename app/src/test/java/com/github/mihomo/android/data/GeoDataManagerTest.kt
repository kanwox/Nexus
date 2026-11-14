package com.github.mihomo.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoDataManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `GeoFile all uses single geoip metadb and geosite dat without Country mmdb`() {
        assertEquals(listOf(GeoFile.GeoIpMetadb, GeoFile.GeoSiteDat), GeoFile.all)
        assertEquals(listOf("geoip.metadb", "geosite.dat"), GeoFile.all.map { it.fileName })
        assertFalse(GeoFile.all.any { it.fileName.equals("Country.mmdb", ignoreCase = true) })
    }

    @Test
    fun `looksLikeErrorPage strips BOM and whitespace before detecting error payloads`() {
        val utf8BomHtml = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "  \r\n\t<html>502 Bad Gateway</html>".encodeToByteArray()
        val utf16BeJson = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            " {\"error\": \"rate limited\"}".encodeToByteArray()
        val utf16LeArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "[{\"message\":\"denied\"}]".encodeToByteArray()
        val whitespaceOnlyWithBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            " \n\t ".encodeToByteArray()
        val validProtobufHead = byteArrayOf(0x0A, 0x05, 0x0A, 0x02, 0x08, 0x01)

        assertTrue(GeoDataManager.looksLikeErrorPage(utf8BomHtml))
        assertTrue(GeoDataManager.looksLikeErrorPage(utf16BeJson))
        assertTrue(GeoDataManager.looksLikeErrorPage(utf16LeArray))
        assertTrue(GeoDataManager.looksLikeErrorPage(whitespaceOnlyWithBom))
        assertFalse(GeoDataManager.looksLikeErrorPage(validProtobufHead))
    }

    @Test
    fun `hasValidGeoSiteProtobufHeader validates wire tag, varint length, and inner field tag`() {
        assertTrue(GeoDataManager.hasValidGeoSiteProtobufHeader(byteArrayOf(0x0A, 0x05, 0x0A, 0x03, 0x43, 0x4E, 0x00)))
        assertTrue(GeoDataManager.hasValidGeoSiteProtobufHeader(byteArrayOf(0x0A, 0x04, 0x12, 0x02, 0x08, 0x01)))
        assertFalse(GeoDataManager.hasValidGeoSiteProtobufHeader(byteArrayOf(0x0A, 0x00, 0x0A, 0x01)))
        assertFalse(GeoDataManager.hasValidGeoSiteProtobufHeader(byteArrayOf(0x08, 0x05, 0x0A, 0x01)))
        assertFalse(GeoDataManager.hasValidGeoSiteProtobufHeader("<html>error</html>".encodeToByteArray()))
    }

    @Test
    fun `validateDownload enforces size bounds and format checks for GeoSiteDat and GeoIpMetadb`() {
        val tooSmall = tempFolder.newFile("small_geosite.dat").apply {
            writeBytes(byteArrayOf(0x0A, 0x05, 0x0A, 0x02, 0x43, 0x4E))
        }
        assertThrows(IllegalStateException::class.java) {
            GeoDataManager.validateDownload(tooSmall, GeoFile.GeoSiteDat)
        }

        val htmlPayload = tempFolder.newFile("html_payload.dat").apply {
            val content = ByteArray(GeoDataManager.MIN_VALID_BYTES.toInt() + 128) { ' '.code.toByte() }
            val html = "<html/>".encodeToByteArray()
            html.copyInto(content, destinationOffset = 4)
            writeBytes(content)
        }
        assertThrows(IllegalStateException::class.java) {
            GeoDataManager.validateDownload(htmlPayload, GeoFile.GeoSiteDat)
        }

        val validGeoSite = tempFolder.newFile("valid_geosite.dat").apply {
            val data = ByteArray(GeoDataManager.MIN_VALID_BYTES.toInt() + 64) { 0x01 }
            data[0] = 0x0A
            data[1] = 0x10
            data[2] = 0x0A
            writeBytes(data)
        }
        GeoDataManager.validateDownload(validGeoSite, GeoFile.GeoSiteDat)
        assertThrows(IllegalStateException::class.java) {
            GeoDataManager.validateDownload(validGeoSite, GeoFile.GeoIpMetadb)
        }

        val markerAtExactEndWithoutMetadata = tempFolder.newFile("truncated_metadb.metadb").apply {
            val marker = GeoDataManager.MAXMIND_METADATA_MARKER
            val data = ByteArray(GeoDataManager.MIN_VALID_BYTES.toInt() + 64) { 0x01 }
            marker.copyInto(data, destinationOffset = data.size - marker.size)
            writeBytes(data)
        }
        assertFalse(GeoDataManager.hasMaxMindMetadataMarker(markerAtExactEndWithoutMetadata))
        assertThrows(IllegalStateException::class.java) {
            GeoDataManager.validateDownload(markerAtExactEndWithoutMetadata, GeoFile.GeoIpMetadb)
        }

        val validMetadb = tempFolder.newFile("valid_geoip.metadb").apply {
            val marker = GeoDataManager.MAXMIND_METADATA_MARKER
            val data = ByteArray(GeoDataManager.MIN_VALID_BYTES.toInt() + 64) { 0x01 }
            marker.copyInto(data, destinationOffset = data.size - marker.size - 8)
            writeBytes(data)
        }
        assertTrue(GeoDataManager.hasMaxMindMetadataMarker(validMetadb))
        GeoDataManager.validateDownload(validMetadb, GeoFile.GeoIpMetadb)
    }

    @Test
    fun `copyStreamCapped copies within limit and throws when exceeding maxBytes`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val outWithin = ByteArrayOutputStream()
        val copied = GeoDataManager.copyStreamCapped(ByteArrayInputStream(payload), outWithin, maxBytes = 5)
        assertEquals(5L, copied)
        assertArrayEquals(payload, outWithin.toByteArray())

        val oversized = ByteArray(6) { 7 }
        val outOver = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            GeoDataManager.copyStreamCapped(ByteArrayInputStream(oversized), outOver, maxBytes = 5)
        }
    }

    @Test
    fun `atomicReplace swaps staged file into existing target`() {
        val target = tempFolder.newFile("geosite.dat").apply { writeText("old-data") }
        val staged = tempFolder.newFile("geosite.dat.update.tmp").apply { writeText("new-validated-data") }

        GeoDataManager.atomicReplace(staged, target)

        assertEquals("new-validated-data", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `proxyClient uses shared single-active-port pool and evicts stale ports`() {
        GeoDataManager.proxyClient(17890)
        assertEquals(setOf(17890), UpdateChecker.activeProxyClientPortsForTest())

        GeoDataManager.proxyClient(17891)
        assertEquals(setOf(17891), UpdateChecker.activeProxyClientPortsForTest())
    }
}
