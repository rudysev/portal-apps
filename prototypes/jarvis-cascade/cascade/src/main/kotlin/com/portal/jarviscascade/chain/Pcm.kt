package com.portal.jarviscascade.chain

/** The one audio format the chain runs on: 16 kHz mono 16-bit PCM, fed in 80 ms frames. */
object Pcm {
    const val SAMPLE_RATE = 16_000

    /** One frame: 80 ms, the step openWakeWord scores on. */
    const val FRAME_SAMPLES = 1_280

    private const val MS_PER_S = 1_000L

    const val FRAME_MS = FRAME_SAMPLES * MS_PER_S / SAMPLE_RATE

    fun samplesFor(ms: Long): Int = (ms * SAMPLE_RATE / MS_PER_S).toInt()
}

/**
 * The last [capacity] samples written, oldest dropped first. Single-threaded: the capture thread writes and takes
 * [latest] copies, which are then safe to hand to another thread.
 */
class PcmRing(val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var next = 0

    /** Samples held, at most [capacity]. */
    var size = 0
        private set

    fun write(samples: ShortArray) {
        for (s in samples) {
            buf[next] = s
            next = (next + 1) % capacity
        }
        size = minOf(capacity, size + samples.size)
    }

    /** A copy of the newest [n] samples (fewer while the ring is filling), oldest first. */
    fun latest(n: Int): ShortArray {
        val count = minOf(n, size)
        val start = Math.floorMod(next - count, capacity)
        return ShortArray(count) { buf[(start + it) % capacity] }
    }
}
