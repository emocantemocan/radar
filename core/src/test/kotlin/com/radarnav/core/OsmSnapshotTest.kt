package com.radarnav.core

import com.radarnav.core.net.LenientJson
import com.radarnav.core.osm.OsmCameraSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uygulamaya gömülü Türkiye kamera dosyası uygulamanın ayrıştırıcısıyla okunabilmeli. */
class OsmSnapshotTest {
    private val file = File("../app/src/main/assets/osm/speed_cameras_tr.json")

    @Test
    fun `gomulu kamera dosyasi gecerli`() {
        assertTrue("dosya yok: ${file.absolutePath}", file.exists())
        val text = file.readText()
        assertTrue("ODbL atfı olmalı", text.contains("OpenStreetMap"))
        val cams = OsmCameraSource.parse(LenientJson.parseToJsonElement(text))
        assertTrue("en az 500 kamera beklenir, ${cams.size} var", cams.size >= 500)
        assertEquals("kimlikler tekil", cams.size, cams.map { it.id }.toSet().size)
        assertTrue("hepsi Türkiye sınırları içinde", cams.all { it.location.lat in 35.5..42.5 && it.location.lon in 25.5..45.0 })
        assertTrue("hız sınırlarının bir kısmı okunmalı", cams.count { it.speedLimit != null } > 100)
        assertTrue("yönlerin bir kısmı okunmalı", cams.count { it.facing != null } > 100)
    }

    @Test
    fun `gomulu egm eds dosyasi gecerli`() {
        val f = File("../app/src/main/assets/egm/eds_tr.json")
        assertTrue(f.exists())
        val pts = com.radarnav.core.egm.EgmEdsSource.parseJson(f.readText())
        assertTrue("en az 1000 EDS noktası beklenir, ${pts.size} var", pts.size >= 1000)
        assertEquals("kimlikler tekil olmalı", pts.size, pts.map { it.id }.toSet().size)
        assertTrue(pts.none { it.name.uppercase().contains("OHTS") })
    }
}
