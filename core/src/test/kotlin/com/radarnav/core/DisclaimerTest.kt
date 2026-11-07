package com.radarnav.core

import com.radarnav.core.legal.Disclaimer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerTest {
    @Test
    fun `metin temel maddeleri icerir`() {
        val t = Disclaimer.TEXT
        listOf("resmî uygulaması değildir", "garanti", "sorumlu değildir", "trafik kurallarına", "yapay zekâ")
            .forEach { assertTrue(it, t.contains(it)) }
    }

    @Test
    fun `eski ya da hic verilmemis onay gecersizdir`() {
        assertFalse(Disclaimer.isAccepted(0))
        assertFalse(Disclaimer.isAccepted(Disclaimer.VERSION - 1))
        assertTrue(Disclaimer.isAccepted(Disclaimer.VERSION))
    }
}
