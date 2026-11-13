package com.github.mihomo.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeoDataManagerTest {

    @Test
    fun `GeoFile all uses single geoip metadb and geosite dat without Country mmdb`() {
        assertEquals(listOf(GeoFile.GeoIpMetadb, GeoFile.GeoSiteDat), GeoFile.all)
        assertEquals(listOf("geoip.metadb", "geosite.dat"), GeoFile.all.map { it.fileName })
        assertFalse(GeoFile.all.any { it.fileName.equals("Country.mmdb", ignoreCase = true) })
    }
}
