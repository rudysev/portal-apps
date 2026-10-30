package com.portal.jarviscascade

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portal.jarviscascade.chain.CandidateRecord
import com.portal.jarviscascade.chain.CascadeChain
import com.portal.jarviscascade.chain.CascadeSettings
import com.portal.jarviscascade.chain.Pcm
import com.portal.jarviscascade.chain.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * The chain through the Android bindings (onnxruntime-android, the sherpa-onnx AAR reading the APK's assets), on
 * whatever device or emulator runs `./gradlew connectedDebugAndroidTest`. Clips pushed to [FIXTURES]
 * (`adb push fixtures/. /data/local/tmp/jarvis-cascade-fixtures/`, see README) are replayed as `ModelChainTest`
 * replays them on the JVM; without them that test skips.
 */
@RunWith(AndroidJUnit4::class)
class AndroidChainTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private fun run(audio: ShortArray): List<CandidateRecord> {
        val records = mutableListOf<CandidateRecord>()
        AndroidSpotter.openWakeWord(assets).use { oww ->
            AndroidSpotter.verifier(assets).use { verifier ->
                val settings = CascadeSettings()
                val chain = CascadeChain(oww, verifier, { settings }, { it.run() }, { 0L }) { r, _ -> records += r }
                for (f in 0 until audio.size / Pcm.FRAME_SAMPLES) {
                    chain.onFrame(audio.copyOfRange(f * Pcm.FRAME_SAMPLES, (f + 1) * Pcm.FRAME_SAMPLES))
                }
            }
        }
        return records
    }

    /** 1 s of quiet before and 1.5 s after, with faint noise as a quiet room has. */
    private fun padded(clip: ShortArray): ShortArray {
        val random = Random(3)
        val lead = Pcm.SAMPLE_RATE
        val out = ShortArray(lead + clip.size + Pcm.samplesFor(1_500)) { random.nextInt(-20, 21).toShort() }
        clip.forEachIndexed { i, s -> out[lead + i] = (out[lead + i] + s).coerceIn(-32_768, 32_767).toShort() }
        return out
    }

    @Test
    fun noiseOpensNoCandidate() {
        val random = Random(5)
        assertTrue(run(ShortArray(5 * Pcm.SAMPLE_RATE) { random.nextInt(-300, 301).toShort() }).isEmpty())
    }

    @Test
    fun stage2RejectsANoiseWindow() {
        val random = Random(9)
        val window = ShortArray(Pcm.samplesFor(CascadeChain.WINDOW_MS)) { random.nextInt(-3_000, 3_001).toShort() }
        AndroidSpotter.verifier(assets).use { assertFalse(it.verify(window, CascadeSettings()).confirmed) }
    }

    @Test
    fun pushedClipsPositivesTriggerNegativesDont() {
        val clips = File(FIXTURES).listFiles { f: File -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        assumeTrue("no clips in $FIXTURES", clips.isNotEmpty())
        val wrong = clips.mapNotNull { clip ->
            val records = run(padded(Wav.decode(clip.readBytes())))
            val triggered = records.any { it.triggered }
            val summary = records.joinToString {
                "stage1 %.2f -> %s (%d ms)".format(Locale.ROOT, it.stage1Peak, it.verdict.label, it.stage2Ms)
            }
            println("${clip.name}: ${if (triggered) "TRIGGERED" else "no trigger"} [$summary]")
            clip.name.takeIf { triggered != clip.name.startsWith("positive_") }
        }
        assertEquals("clips with the wrong outcome", emptyList<String>(), wrong)
    }

    private companion object {
        const val FIXTURES = "/data/local/tmp/jarvis-cascade-fixtures"
    }
}
