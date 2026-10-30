package com.portal.jarviscascade.chain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit PCM WAV: saved candidate windows, and test clips read back. */
object Wav {
    private const val HEADER_BYTES = 44
    private const val FMT_CHUNK_BYTES = 16
    private const val FORMAT_PCM: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val CHUNK_HEADER_BYTES = 8
    private const val RIFF_HEADER_BYTES = 12
    private const val TAG_BYTES = 4
    private const val SIZE_OFFSET = 4

    // Field offsets inside the fmt chunk's body.
    private const val FMT_CHANNELS = 2
    private const val FMT_SAMPLE_RATE = 4
    private const val FMT_BITS_PER_SAMPLE = 14

    fun encode(samples: ShortArray): ByteArray {
        val data = samples.size * BYTES_PER_SAMPLE
        val b = ByteBuffer.allocate(HEADER_BYTES + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(HEADER_BYTES - CHUNK_HEADER_BYTES + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(FMT_CHUNK_BYTES).putShort(FORMAT_PCM).putShort(1)
        b.putInt(Pcm.SAMPLE_RATE).putInt(Pcm.SAMPLE_RATE * BYTES_PER_SAMPLE)
        b.putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS_PER_SAMPLE)
        b.put("data".toByteArray()).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    /** The samples of a 16 kHz mono 16-bit PCM WAV; any other format is rejected. */
    fun decode(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= RIFF_HEADER_BYTES && tag(b, 0) == "RIFF" && tag(b, CHUNK_HEADER_BYTES) == "WAVE") {
            "not a WAV file"
        }
        var pos = RIFF_HEADER_BYTES
        var formatOk = false
        while (pos + CHUNK_HEADER_BYTES <= bytes.size) {
            val id = tag(b, pos)
            val size = b.getInt(pos + SIZE_OFFSET)
            val body = pos + CHUNK_HEADER_BYTES
            when (id) {
                "fmt " -> formatOk = b.getShort(body) == FORMAT_PCM &&
                    b.getShort(body + FMT_CHANNELS).toInt() == 1 &&
                    b.getInt(body + FMT_SAMPLE_RATE) == Pcm.SAMPLE_RATE &&
                    b.getShort(body + FMT_BITS_PER_SAMPLE) == BITS_PER_SAMPLE

                "data" -> {
                    require(formatOk) { "WAV is not 16 kHz mono 16-bit PCM" }
                    val n = minOf(size, bytes.size - body) / BYTES_PER_SAMPLE
                    return ShortArray(n) { b.getShort(body + it * BYTES_PER_SAMPLE) }
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no data chunk")
    }

    private fun tag(b: ByteBuffer, at: Int): String = String(ByteArray(TAG_BYTES) { b.get(at + it) }, Charsets.US_ASCII)
}
