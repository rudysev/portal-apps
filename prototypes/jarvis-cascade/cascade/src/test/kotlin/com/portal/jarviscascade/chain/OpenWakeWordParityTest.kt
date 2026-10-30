package com.portal.jarviscascade.chain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin stage-1 port against openWakeWord 0.6.0 itself: the same deterministic signal and warm-up through both,
 * expected values from `tools/oww_parity_reference.py` (`oww_parity.txt`).
 */
class OpenWakeWordParityTest {
    private val reference = checkNotNull(javaClass.getResource("/oww_parity.txt")).readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split(" ") }
    private val scores = reference.filter { it[0] == "score" }.associate { it[1].toInt() to it[2].toFloat() }
    private val features = reference.filter { it[0] == "feature" }
        .associate { line -> line[1].toInt() to FloatArray(line.size - 2) { line[it + 2].toFloat() } }

    @Test
    fun `scores and features match openWakeWord frame by frame`() {
        Models.openWakeWord(lcg(seed = 7, n = 4 * Pcm.SAMPLE_RATE)).use { oww ->
            val x = signal(scores.size * Pcm.FRAME_SAMPLES)
            for (f in 1..scores.size) {
                val frame = x.copyOfRange((f - 1) * Pcm.FRAME_SAMPLES, f * Pcm.FRAME_SAMPLES)
                assertEquals("score at frame $f", scores.getValue(f), oww.score(frame), SCORE_TOLERANCE)
                features[f]?.let { want ->
                    val got = oww.latestFeature()
                    assertEquals(want.size, got.size)
                    want.indices.forEach {
                        assertEquals("feature $it at frame $f", want[it], got[it], FEATURE_TOLERANCE)
                    }
                }
            }
        }
    }

    private companion object {
        const val SCORE_TOLERANCE = 1e-5f
        const val FEATURE_TOLERANCE = 1e-3f

        // The reference script's generator: integer-only, so both sides see bit-identical samples.
        fun lcg(seed: Long, n: Int): ShortArray {
            var s = seed
            return ShortArray(n) {
                s = (s * 1_103_515_245L + 12_345L) % (1L shl 31)
                ((s shr 16) % 2_001 - 1_000).toShort()
            }
        }

        fun signal(n: Int): ShortArray {
            val noise = lcg(seed = 1, n = n)
            return ShortArray(n) { i ->
                val tone = if ((i / 8_000) % 2 == 0) {
                    if ((i / 40) % 2 == 0) 3_000 else -3_000
                } else {
                    if ((i / 16) % 2 == 0) 1_500 else -1_500
                }
                (noise[i] + tone).toShort()
            }
        }
    }
}
