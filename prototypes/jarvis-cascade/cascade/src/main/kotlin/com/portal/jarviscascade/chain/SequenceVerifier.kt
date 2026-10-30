package com.portal.jarviscascade.chain

/**
 * sherpa-onnx's open-vocabulary keyword spotter, as stage 2 uses it. The app and the JVM tests each adapt their own
 * sherpa-onnx binding (Android AAR, JVM jar) to this.
 */
interface KeywordSpotter : AutoCloseable {
    /** Decodes [samples] (16 kHz, in [-1, 1]) as one finished stream; returns the keywords spotted, in order. */
    fun spot(samples: FloatArray): List<String>

    /** Builds a spotter for the HEY JARVIS keywords file with this threshold and boost. */
    fun interface Factory {
        fun create(threshold: Float, boost: Float): KeywordSpotter
    }
}

/**
 * Stage 2: confirms HEY → JARVIS in a candidate's window with the open-vocabulary spotter (report §6.1).
 *
 * - The window is followed by [TAIL_PADDING_MS] of silence before the stream is finished. Without it the streaming
 *   Zipformer never flushes its last chunks and misses a "jarvis" at the end of the window (checked off-device with
 *   sherpa-onnx 2.0.1: 0 of 4 TTS positives without padding, 4 of 4 with it).
 * - sherpa-onnx fixes threshold and boost when the spotter is built (a per-stream override only adds a second
 *   keyword next to the file's), so a settings change rebuilds the spotter on the next verification.
 *
 * Not thread-safe: the chain calls it from one stage-2 thread.
 */
class SequenceVerifier(private val factory: KeywordSpotter.Factory) :
    Stage2,
    AutoCloseable {
    private var spotter: KeywordSpotter? = null
    private var builtFor: Pair<Float, Float>? = null

    override fun verify(window: ShortArray, settings: CascadeSettings): Stage2Result {
        val samples = FloatArray(window.size + Pcm.samplesFor(TAIL_PADDING_MS))
        window.forEachIndexed { i, s -> samples[i] = s / PCM_SCALE }
        val keywords = spotterFor(settings).spot(samples)
        return Stage2Result(confirmed = KEYWORD in keywords, keywords = keywords)
    }

    /** Builds the spotter for [settings] ahead of the first candidate, so that one isn't slowed by model loading. */
    fun prepare(settings: CascadeSettings) {
        spotterFor(settings)
    }

    private fun spotterFor(settings: CascadeSettings): KeywordSpotter {
        val wanted = settings.stage2Threshold to settings.stage2Boost
        val current = spotter
        if (current != null && builtFor == wanted) return current
        current?.close()
        spotter = null
        return factory.create(wanted.first, wanted.second).also {
            spotter = it
            builtFor = wanted
        }
    }

    override fun close() {
        spotter?.close()
        spotter = null
    }

    companion object {
        /** The keyword name in `hey_jarvis_keywords.txt` (`▁HE Y ▁JA R VI S @HEY_JARVIS`). */
        const val KEYWORD = "HEY_JARVIS"
        const val TAIL_PADDING_MS = 600L
        private const val PCM_SCALE = 32_768f
    }
}
