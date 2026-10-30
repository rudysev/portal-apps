package com.portal.jarviscascade.chain

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Running numbers for comparing capture sources and measuring stage-1 cost on device (report §8 steps 1 and 4): the
 * level, the share of exact-zero samples (a source that returns zeros is deaf), and stage-1 time per frame.
 * [snapshot] reads and restarts the counts. Single-threaded: the capture thread.
 */
class CaptureStats {
    private var frames = 0L
    private var samples = 0L
    private var zeros = 0L
    private var sumSquares = 0.0
    private var stage1Nanos = 0L
    private var stage1MaxNanos = 0L

    fun add(frame: ShortArray, stage1Nanos: Long) {
        frames++
        samples += frame.size
        for (s in frame) {
            if (s.toInt() == 0) zeros++
            sumSquares += s.toDouble() * s
        }
        this.stage1Nanos += stage1Nanos
        stage1MaxNanos = maxOf(stage1MaxNanos, stage1Nanos)
    }

    /** Level in dBFS (RMS against full scale; -inf for silence), zero share, and stage-1 ms per frame. */
    data class Snapshot(
        val frames: Long,
        val levelDbfs: Double,
        val zeroFraction: Double,
        val stage1MsMean: Double,
        val stage1MsMax: Double,
    )

    fun snapshot(): Snapshot {
        val rms = if (samples == 0L) 0.0 else sqrt(sumSquares / samples)
        val snap = Snapshot(
            frames = frames,
            levelDbfs = DB_PER_DECADE * log10(rms / FULL_SCALE),
            zeroFraction = if (samples == 0L) 0.0 else zeros.toDouble() / samples,
            stage1MsMean = if (frames == 0L) 0.0 else stage1Nanos.toDouble() / frames / NANOS_PER_MS,
            stage1MsMax = stage1MaxNanos / NANOS_PER_MS,
        )
        frames = 0
        samples = 0
        zeros = 0
        sumSquares = 0.0
        stage1Nanos = 0
        stage1MaxNanos = 0
        return snap
    }

    private companion object {
        const val FULL_SCALE = 32_768.0
        const val DB_PER_DECADE = 20.0
        const val NANOS_PER_MS = 1e6
    }
}
