package com.portal.jarviscascade.chain

import ai.onnxruntime.OrtEnvironment
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaKeywordSpotter

/** The real models from `fetch-deps.sh`, as the app ships them (`app/src/main/assets`). */
object Models {
    private val assets = File(checkNotNull(System.getProperty("cascade.assets")) { "cascade.assets not set" })

    /** Local TTS clips, when present (README "Validating locally"). */
    val fixtures = File(checkNotNull(System.getProperty("cascade.fixtures")) { "cascade.fixtures not set" })

    fun openWakeWord(warmupNoise: ShortArray = OpenWakeWord.defaultWarmupNoise()) = OpenWakeWord(
        OrtEnvironment.getEnvironment(),
        assets.resolve("oww/melspectrogram.onnx").readBytes(),
        assets.resolve("oww/embedding_model.onnx").readBytes(),
        assets.resolve("oww/hey_jarvis_v0.1.onnx").readBytes(),
        warmupNoise,
    )

    /** The JVM sherpa-onnx binding behind stage 2's port: the same config the app builds with its Android binding. */
    val spotters = KeywordSpotter.Factory { threshold, boost -> JvmSpotter(threshold, boost) }

    private class JvmSpotter(threshold: Float, boost: Float) : KeywordSpotter {
        private val kws = SherpaKeywordSpotter(
            KeywordSpotterConfig.builder()
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(Pcm.SAMPLE_RATE).setFeatureDim(80).build())
                .setOnlineModelConfig(
                    OnlineModelConfig.builder()
                        .setTransducer(
                            OnlineTransducerModelConfig.builder()
                                .setEncoder(kws("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
                                .setDecoder(kws("decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
                                .setJoiner(kws("joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
                                .build(),
                        )
                        .setTokens(kws("tokens.txt"))
                        .setNumThreads(1)
                        .setDebug(false)
                        .build(),
                )
                .setKeywordsFile(assets.resolve("hey_jarvis_keywords.txt").path)
                .setKeywordsThreshold(threshold)
                .setKeywordsScore(boost)
                .build(),
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

        private fun kws(name: String) = assets.resolve("kws/$name").path
    }
}
