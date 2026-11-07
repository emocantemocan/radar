package com.radarnav.core

import com.radarnav.core.diag.ErrorLog
import com.radarnav.core.osm.InMemoryTextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogTest {
    private val disk = InMemoryTextStore()
    private var now = 1_800_000_000_000L
    private fun log() = ErrorLog(disk, "0.4.0", maxEntries = 3, clock = { now })

    @Test
    fun `hatalar en yenisi basta saklanir ve sinirlanir`() {
        val l = log()
        repeat(5) { i -> now += 1000; l.record("yer$i", IllegalStateException("hata $i")) }
        val e = log().entries()
        assertEquals(3, e.size)
        assertTrue(e[0], e[0].contains("yer4") && e[0].contains("hata 4") && e[0].contains("0.4.0"))
        assertTrue(e[2].contains("yer2"))
        log().clear()
        assertEquals(0, log().entries().size)
    }

    @Test
    fun `kisa ozet kok nedeni ve uygulama satirini verir`() {
        val e = RuntimeException("sarmalayan", IllegalArgumentException("asıl neden"))
        val s = ErrorLog.short(e)
        assertTrue(s, s.startsWith("IllegalArgumentException: asıl neden"))
        assertTrue(s, s.contains("ErrorLogTest.kt:"))
    }
}
