package com.radarnav.core

import com.radarnav.core.gemini.EchoGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGateTest {
    private var now = 10_000L
    private val gate = EchoGate(bytesPerSecond = 48_000, tailMs = 500, clock = { now })

    @Test
    fun `asistan konusurken ve hemen sonrasinda mikrofon kapali`() {
        assertTrue(gate.micOpen())
        // Üç parça, her biri 1 sn ses: art arda kuyruğa girer, 3 sn sürer
        repeat(3) { gate.onPlayback(48_000) }
        now += 2_900
        assertFalse("hâlâ konuşuyor", gate.micOpen())
        now += 400 // ses bitti (3 sn), ama yankı payı sürüyor
        assertFalse(gate.micOpen())
        now += 300
        assertTrue(gate.micOpen())
    }

    @Test
    fun `kesilince kuyruk sifirlanir`() {
        gate.onPlayback(48_000 * 5)
        now += 1_000
        gate.onInterrupted()
        now += 600
        assertTrue(gate.micOpen())
    }
}
