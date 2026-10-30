package com.portal.jarviscascade.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The whole chain on the real models (openWakeWord stage 1 through ONNX Runtime, sherpa-onnx stage 2 through its JVM
 * binding), fed frame by frame as the app feeds it.
 */
class ModelChainTest {
    private fun run(audio: ShortArray, settings: CascadeSettings = CascadeSettings()): List<CandidateRecord> {
        val records = mutableListOf<CandidateRecord>()
        Models.openWakeWord().use { oww ->
            SequenceVerifier(Models.spotters).use { verifier ->
                val chain = CascadeChain(oww, verifier, { settings }, { it.run() }, { 0L }) { r, _ -> records += r }
                for (f in 0 until audio.size / Pcm.FRAME_SAMPLES) {
                    chain.onFrame(audio.copyOfRange(f * Pcm.FRAME_SAMPLES, (f + 1) * Pcm.FRAME_SAMPLES))
                }
            }
        }
        return records
    }

    /** 1 s of quiet before and 1.5 s after (enough for the post-roll), with faint noise as a quiet room has. */
    private fun padded(clip: ShortArray): ShortArray {
        val random = Random(3)
        val lead = Pcm.SAMPLE_RATE
        val out = ShortArray(lead + clip.size + Pcm.samplesFor(1_500)) { random.nextInt(-20, 21).toShort() }
        clip.forEachIndexed { i, s -> out[lead + i] = (out[lead + i] + s).coerceIn(-32_768, 32_767).toShort() }
        return out
    }

    @Test
    fun `room noise and tones open no candidate`() {
        val random = Random(5)
        val audio = ShortArray(10 * Pcm.SAMPLE_RATE) { i ->
            val tone = if ((i / 4_000) % 3 == 0) ((i / 20) % 2 * 2 - 1) * 2_000 else 0
            (random.nextInt(-300, 301) + tone).toShort()
        }
        assertTrue(run(audio).isEmpty())
    }

    @Test
    fun `stage 2 on the real spotter rejects a window of noise`() {
        val random = Random(9)
        val window = ShortArray(Pcm.samplesFor(CascadeChain.WINDOW_MS)) { random.nextInt(-3_000, 3_001).toShort() }
        SequenceVerifier(Models.spotters).use { verifier ->
            val result = verifier.verify(window, CascadeSettings())
            assertFalse(result.confirmed)
            assertEquals(emptyList<String>(), result.keywords)
        }
    }

    /**
     * Local TTS clips (`tools/make-tts-fixtures.sh`, macOS `say`): every `positive_*.wav` must trigger, and no
     * `negative_*.wav` may. Skipped when the clips aren't there (CI has no TTS).
     */
    @Test
    fun `local TTS clips - positives trigger, negatives don't`() {
        val clips = Models.fixtures.listFiles { f: File -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        assumeTrue("no local TTS clips in ${Models.fixtures}", clips.isNotEmpty())
        val wrong = clips.mapNotNull { clip ->
            val records = run(padded(Wav.decode(clip.readBytes())))
            val triggered = records.any { it.triggered }
            val summary = records.joinToString {
                "stage1 %.2f -> %s".format(Locale.ROOT, it.stage1Peak, it.verdict.label)
            }
            println("${clip.name}: ${if (triggered) "TRIGGERED" else "no trigger"} [$summary]")
            val positive = clip.name.startsWith("positive_")
            clip.name.takeIf { triggered != positive }
        }
        assertEquals("clips with the wrong outcome", emptyList<String>(), wrong)
    }
}
