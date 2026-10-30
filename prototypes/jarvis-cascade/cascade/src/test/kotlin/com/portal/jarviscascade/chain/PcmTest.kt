package com.portal.jarviscascade.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun `the ring keeps the newest samples in order across wrap-around`() {
        val ring = PcmRing(5)
        ring.write(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.latest(10))
        ring.write(shortArrayOf(4, 5, 6, 7))
        assertEquals(5, ring.size)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.latest(5))
        assertArrayEquals(shortArrayOf(6, 7), ring.latest(2))
    }

    @Test
    fun `a WAV round-trips its samples`() {
        val samples = ShortArray(1_000) { (it * 37 - 16_000).toShort() }
        val wav = Wav.encode(samples)
        assertEquals(44 + 2_000, wav.size)
        assertArrayEquals(samples, Wav.decode(wav))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a WAV in another format is rejected`() {
        val wav = Wav.encode(ShortArray(10))
        wav[24] = 0x44 // sample rate 16_000 (0x3E80) -> 0x3E44
        Wav.decode(wav)
    }

    @Test
    fun `capture stats report level, zero share and stage-1 time, then restart`() {
        val stats = CaptureStats()
        stats.add(ShortArray(Pcm.FRAME_SAMPLES) { if (it % 2 == 0) 16_384 else 0 }, 1_000_000)
        stats.add(ShortArray(Pcm.FRAME_SAMPLES) { if (it % 2 == 0) -16_384 else 0 }, 3_000_000)
        val snap = stats.snapshot()
        assertEquals(2, snap.frames)
        assertEquals(0.5, snap.zeroFraction, 1e-9)
        // RMS of half-scale on half the samples: 0.5 / sqrt(2) -> about -9.03 dBFS.
        assertEquals(-9.03, snap.levelDbfs, 0.01)
        assertEquals(2.0, snap.stage1MsMean, 1e-9)
        assertEquals(3.0, snap.stage1MsMax, 1e-9)
        assertEquals(0, stats.snapshot().frames)
    }
}
