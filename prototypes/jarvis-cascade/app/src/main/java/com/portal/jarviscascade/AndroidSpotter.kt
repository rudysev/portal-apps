package com.portal.jarviscascade

import ai.onnxruntime.OrtEnvironment
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.portal.jarviscascade.chain.KeywordSpotter
import com.portal.jarviscascade.chain.OpenWakeWord
import com.portal.jarviscascade.chain.Pcm
import com.portal.jarviscascade.chain.SequenceVerifier
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaKeywordSpotter

/**
 * Stage 2's spotter on the sherpa-onnx Android binding, reading the KWS model and `hey_jarvis_keywords.txt` from the
 * APK's assets. The same config `:cascade`'s tests build with the JVM binding. [openWakeWord] and [verifier] build
 * both stages from the APK's models.
 */
class AndroidSpotter(assets: AssetManager, threshold: Float, boost: Float) : KeywordSpotter {
    private val kws = SherpaKeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = Pcm.SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                numThreads = 1,
                debug = false,
            ),
            keywordsFile = "hey_jarvis_keywords.txt",
            keywordsThreshold = threshold,
            keywordsScore = boost,
        ),
    )

    override fun spot(samples: FloatArray): List<String> {
        val stream = kws.createStream()
        try {
            stream.acceptWaveform(samples, Pcm.SAMPLE_RATE)
            stream.inputFinished()
            val spotted = mutableListOf<String>()
            while (kws.isReady(stream)) {
                kws.decode(stream)
                val keyword = kws.getResult(stream).keyword
                if (keyword.isNotEmpty()) {
                    spotted += keyword
                    kws.reset(stream)
                }
            }
            return spotted
        } finally {
            stream.release()
        }
    }

    override fun close() = kws.release()

    companion object {
        private const val FEATURE_DIM = 80

        /** Stage 1 on the APK's openWakeWord models. */
        fun openWakeWord(assets: AssetManager) = OpenWakeWord(
            OrtEnvironment.getEnvironment(),
            assets.open("oww/melspectrogram.onnx").use { it.readBytes() },
            assets.open("oww/embedding_model.onnx").use { it.readBytes() },
            assets.open("oww/hey_jarvis_v0.1.onnx").use { it.readBytes() },
        )

        /** Stage 2 on the APK's sherpa-onnx KWS model. */
        fun verifier(assets: AssetManager) = SequenceVerifier { threshold, boost ->
            AndroidSpotter(assets, threshold, boost)
        }
    }
}
