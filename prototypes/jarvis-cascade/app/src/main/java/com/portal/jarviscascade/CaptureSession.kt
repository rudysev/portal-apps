package com.portal.jarviscascade

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.portal.jarviscascade.chain.CandidateRecord
import com.portal.jarviscascade.chain.CaptureStats
import com.portal.jarviscascade.chain.CascadeChain
import com.portal.jarviscascade.chain.LogLines
import com.portal.jarviscascade.chain.Pcm
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One capture session: an `AudioRecord` on the chosen source (16 kHz mono 16-bit, no effects) read in 80 ms frames on
 * its own thread, each frame through the [CascadeChain]; stage 2 on a second thread. Logs a `session` line at start,
 * every candidate, a `stats` line every [STATS_INTERVAL_MS] and at the end, silencing changes (Android 10) and why it
 * stopped.
 * [onTrigger] runs on the stage-2 thread.
 */
class CaptureSession(
    private val context: Context,
    private val source: AudioSource,
    private val onTrigger: () -> Unit,
    private val onStopped: (CaptureSession) -> Unit,
) {
    private val settings = TestSettings.of(context)
    private val log = EventLog.of(context)
    private val stage2Thread = Executors.newSingleThreadExecutor { Thread(it, "cascade-stage2") }

    @Volatile private var running = true
    private val thread = Thread(::run, "cascade-capture")

    fun start() = thread.start()

    /** Asks the session to end; the capture thread releases the mic and calls [onStopped]. */
    fun stop() {
        running = false
    }

    private fun run() {
        try {
            val reason = runCatching { capture() }.getOrElse { e ->
                Log.e(TAG, "capture failed", e)
                "failed: ${e.message ?: e.javaClass.name}"
            }
            log.append(LogLines.stopped(System.currentTimeMillis(), reason), "${clock()} capture $reason")
        } finally {
            stage2Thread.shutdown()
            stage2Thread.awaitTermination(STAGE2_DRAIN_S, TimeUnit.SECONDS)
            onStopped(this)
        }
    }

    /** Captures until [stop] or a capture error; returns why it ended. */
    private fun capture(): String {
        val verifier = AndroidSpotter.verifier(context.assets)
        AndroidSpotter.openWakeWord(context.assets).use { stage1 ->
            val chain = CascadeChain(
                stage1,
                verifier,
                { settings.cascade },
                stage2Thread::execute,
                System::currentTimeMillis,
                ::onCandidate,
            )
            // Build the spotter now, so the first candidate's stage2Ms is decoding, not model loading.
            // A failure here surfaces again, as an error verdict, on the first candidate.
            stage2Thread.execute {
                runCatching { verifier.prepare(settings.cascade) }.onFailure { Log.e(TAG, "stage 2 not ready", it) }
            }
            try {
                val record = openRecord() ?: return "failed: AudioRecord on $source did not start"
                try {
                    return readLoop(record, chain)
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            } finally {
                // The spotter lives on the stage-2 thread; close it there, after any queued verification.
                stage2Thread.execute { runCatching { verifier.close() } }
            }
        }
    }

    /** A started record on [source] (logging the session), or null when it can't be built or started. */
    private fun openRecord(): AudioRecord? {
        val record = newRecord() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) watchSilencing(record)
        record.startRecording()
        val started = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (started) {
            val now = System.currentTimeMillis()
            val device = "${Build.MANUFACTURER} ${Build.MODEL}"
            log.append(
                LogLines.session(now, source.name, device, Build.VERSION.SDK_INT, settings.cascade),
                "${clock(now)} listening on ${source.name}",
            )
        } else {
            record.release()
        }
        return record.takeIf { started }
    }

    // MissingPermission: MainActivity holds RECORD_AUDIO before it starts the service; without the grant the
    // AudioRecord fails to initialize, which this reports as null.
    @SuppressLint("MissingPermission")
    private fun newRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, CHANNEL, ENCODING)
        // minBuf <= 0 is ERROR / ERROR_BAD_VALUE: this rate/channel/encoding isn't supported.
        if (minBuf <= 0) return null
        val bufferBytes = maxOf(minBuf, FRAME_BYTES * BUFFER_FRAMES)
        val record = AudioRecord(source.id, Pcm.SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
        val ready = record.state == AudioRecord.STATE_INITIALIZED
        if (!ready) record.release()
        return record.takeIf { ready }
    }

    /**
     * Android 10 silences a capturing app whose mic another app takes (a call, the Portal's own assistant). A silenced
     * detector is deaf, not hearing a quiet room, so log each change: candidates can't come from silenced spans.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun watchSilencing(record: AudioRecord) {
        var silenced = false
        record.registerAudioRecordingCallback(
            context.mainExecutor,
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val now = record.activeRecordingConfiguration?.isClientSilenced ?: return
                    if (now != silenced) {
                        silenced = now
                        val at = System.currentTimeMillis()
                        log.append(
                            LogLines.silenced(at, now),
                            "${clock(at)} ${if (now) "SILENCED (deaf)" else "unsilenced"}",
                        )
                    }
                }
            },
        )
    }

    private fun readLoop(record: AudioRecord, chain: CascadeChain): String {
        val frame = ShortArray(Pcm.FRAME_SAMPLES)
        val stats = CaptureStats()
        var lastStats = System.currentTimeMillis()
        var ended = readFrame(record, frame)
        while (ended == null) {
            val started = System.nanoTime()
            chain.onFrame(frame)
            stats.add(frame, System.nanoTime() - started)
            val now = System.currentTimeMillis()
            if (now - lastStats >= STATS_INTERVAL_MS) {
                lastStats = now
                logStats(now, stats.snapshot())
            }
            ended = readFrame(record, frame)
        }
        // The rest since the last stats line, so a short session (one source's quick check) still gets one.
        stats.snapshot().takeIf { it.frames > 0 }?.let { logStats(System.currentTimeMillis(), it) }
        return ended
    }

    /** Fills [frame]; returns null when it did, or why capture ends. */
    private fun readFrame(record: AudioRecord, frame: ShortArray): String? {
        var filled = 0
        var ended: String? = null
        while (ended == null && filled < frame.size) {
            val n = if (running) record.read(frame, filled, frame.size - filled) else 0
            ended = when {
                !running -> "stopped"
                n < 0 -> "failed: AudioRecord.read returned $n"
                else -> null
            }
            filled += maxOf(n, 0)
        }
        return ended
    }

    private fun logStats(now: Long, snap: CaptureStats.Snapshot) = log.append(
        LogLines.stats(now, snap),
        "${clock(now)} level %.1f dBFS, zeros %.0f%%, stage 1 %.1f ms/frame".format(
            Locale.ROOT,
            snap.levelDbfs,
            snap.zeroFraction * PERCENT,
            snap.stage1MsMean,
        ),
    )

    private fun onCandidate(record: CandidateRecord, window: ShortArray) {
        val audio = if (settings.saveAudio) log.saveWindow(record.atMs, window) else null
        if (record.triggered) log.lastTriggerAtMs = record.atMs
        val outcome = if (record.triggered) "TRIGGERED" else "no trigger"
        log.append(
            LogLines.candidate(record, source.name, audio),
            "${clock(record.atMs)} stage 1 %.2f → stage 2 %s (%d ms): %s".format(
                Locale.ROOT,
                record.stage1Peak,
                record.verdict.label,
                record.stage2Ms,
                outcome,
            ),
        )
        if (record.triggered) onTrigger()
    }

    private companion object {
        const val TAG = "JarvisCascade"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_BYTES = Pcm.FRAME_SAMPLES * 2
        const val BUFFER_FRAMES = 4
        const val STATS_INTERVAL_MS = 60_000L
        const val STAGE2_DRAIN_S = 5L
        const val PERCENT = 100.0

        fun clock(atMs: Long = System.currentTimeMillis()): String = EventLog.clock(atMs)
    }
}
