package com.portal.jarviscascade.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceVerifierTest {
    private class FakeSpotter(val threshold: Float, val boost: Float, private val spotted: List<String>) :
        KeywordSpotter {
        val inputs = mutableListOf<FloatArray>()
        var closed = false
        override fun spot(samples: FloatArray): List<String> {
            inputs += samples
            return spotted
        }

        override fun close() {
            closed = true
        }
    }

    private val built = mutableListOf<FakeSpotter>()
    private var spots = listOf(SequenceVerifier.KEYWORD)
    private val verifier = SequenceVerifier { t, b -> FakeSpotter(t, b, spots).also { built += it } }

    @Test
    fun `the window reaches the spotter scaled to -1 to 1 and followed by silence`() {
        val window = shortArrayOf(Short.MIN_VALUE, 0, 16_384, Short.MAX_VALUE)
        verifier.verify(window, CascadeSettings())

        val input = built.single().inputs.single()
        assertEquals(window.size + Pcm.samplesFor(SequenceVerifier.TAIL_PADDING_MS), input.size)
        assertEquals(-1f, input[0])
        assertEquals(0f, input[1])
        assertEquals(0.5f, input[2])
        assertEquals(32_767f / 32_768f, input[3])
        assertTrue(input.drop(window.size).all { it == 0f })
    }

    @Test
    fun `spotting HEY_JARVIS confirms, anything else rejects`() {
        assertTrue(verifier.verify(ShortArray(10), CascadeSettings()).confirmed)
        spots = listOf("SOMETHING_ELSE")
        val other = SequenceVerifier { t, b -> FakeSpotter(t, b, spots) }
        val result = other.verify(ShortArray(10), CascadeSettings())
        assertFalse(result.confirmed)
        assertEquals(listOf("SOMETHING_ELSE"), result.keywords)
        assertFalse(
            SequenceVerifier { t, b ->
                FakeSpotter(t, b, emptyList())
            }.verify(ShortArray(10), CascadeSettings()).confirmed,
        )
    }

    @Test
    fun `the spotter is built with the stage-2 settings and rebuilt only when they change`() {
        verifier.verify(ShortArray(10), CascadeSettings(stage2Threshold = 0.2f, stage2Boost = 1.0f))
        verifier.verify(
            ShortArray(10),
            CascadeSettings(stage1Threshold = 0.9f, stage2Threshold = 0.2f, stage2Boost = 1.0f),
        )
        assertEquals(1, built.size)
        assertEquals(0.2f, built[0].threshold)
        assertEquals(1.0f, built[0].boost)

        verifier.verify(ShortArray(10), CascadeSettings(stage2Threshold = 0.3f, stage2Boost = 1.0f))
        assertEquals(2, built.size)
        assertTrue(built[0].closed)
        assertEquals(0.3f, built[1].threshold)

        verifier.close()
        assertTrue(built[1].closed)
    }

    @Test
    fun `prepare builds the spotter the next verification then reuses`() {
        val settings = CascadeSettings(stage2Threshold = 0.2f)
        verifier.prepare(settings)
        assertEquals(1, built.size)
        assertTrue(built.single().inputs.isEmpty())
        verifier.verify(ShortArray(10), settings)
        assertEquals(1, built.size)
        assertEquals(1, built.single().inputs.size)
    }
}
