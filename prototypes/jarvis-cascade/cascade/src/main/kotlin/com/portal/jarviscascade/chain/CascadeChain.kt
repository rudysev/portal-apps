package com.portal.jarviscascade.chain

/** Stage 2's verdict on one candidate. */
enum class Verdict(val label: String) {
    CONFIRMED("confirmed"),
    REJECTED("rejected"),

    /** Stage 2 threw: no verdict. Never triggers. */
    ERROR("error"),
}

/** One stage-1 candidate and what the chain made of it. */
data class CandidateRecord(
    /** Wall clock (epoch ms) of the frame that opened the candidate. */
    val atMs: Long,
    /** Where that frame ends in this capture session's audio, in ms from its start. */
    val streamMs: Long,
    /** The highest stage-1 score from the opening frame through the post-roll. */
    val stage1Peak: Float,
    /** The settings in force when the candidate opened; stage 2 ran with these. */
    val settings: CascadeSettings,
    val verdict: Verdict,
    /** Keywords stage 2 spotted in the window (empty when it rejected or failed). */
    val keywords: List<String>,
    /** How long stage 2 took, ms. */
    val stage2Ms: Long,
    /** The chain's output: stage 1 opened the candidate AND stage 2 confirmed HEY → JARVIS. */
    val triggered: Boolean,
    /** Stage 2's failure, when [verdict] is [Verdict.ERROR]. */
    val error: String? = null,
)

/**
 * The two-check chain (report §6.1): stage 1 scores every frame; a frame scoring at or above the stage-1 threshold
 * opens a **candidate**; [POST_ROLL_MS] later the last [WINDOW_MS] of audio go to stage 2, which confirms or rejects
 * the HEY → JARVIS sequence; a confirmed candidate is a **trigger**. Every candidate, triggered or not, reaches
 * [onCandidate] with the window stage 2 saw.
 *
 * [onFrame] runs on the capture thread. Stage 2 runs through [runStage2] (the app passes a single worker thread, so a
 * slow verification never stalls capture), and [onCandidate] is called on that thread. After a candidate opens, new
 * ones are ignored for [REFRACTORY_MS], so one utterance is one candidate.
 */
class CascadeChain(
    private val stage1: Stage1,
    private val stage2: Stage2,
    private val settings: () -> CascadeSettings,
    private val runStage2: (Runnable) -> Unit,
    private val wallClockMs: () -> Long,
    private val onCandidate: (CandidateRecord, ShortArray) -> Unit,
) {
    private val ring = PcmRing(Pcm.samplesFor(RING_MS))
    private var frames = 0L
    private var open: Open? = null
    private var lastOpenFrame = -REFRACTORY_FRAMES

    private class Open(val frame: Long, val atMs: Long, val settings: CascadeSettings, var peak: Float)

    /** Feeds one [Pcm.FRAME_SAMPLES]-sample frame; returns its stage-1 score. */
    fun onFrame(frame: ShortArray): Float {
        require(frame.size == Pcm.FRAME_SAMPLES) { "frame has ${frame.size} samples, not ${Pcm.FRAME_SAMPLES}" }
        ring.write(frame)
        frames++
        val score = stage1.score(frame)
        val pending = open
        if (pending != null) {
            pending.peak = maxOf(pending.peak, score)
        } else {
            val current = settings()
            if (score >= current.stage1Threshold && frames - lastOpenFrame >= REFRACTORY_FRAMES) {
                open = Open(frames, wallClockMs(), current, score)
                lastOpenFrame = frames
            }
        }
        open?.takeIf { frames - it.frame >= POST_ROLL_FRAMES }?.let {
            open = null
            dispatch(it, ring.latest(Pcm.samplesFor(WINDOW_MS)))
        }
        return score
    }

    private fun dispatch(candidate: Open, window: ShortArray) {
        val streamMs = candidate.frame * Pcm.FRAME_MS
        runStage2(
            Runnable {
                val started = System.nanoTime()
                val outcome = runCatching { stage2.verify(window, candidate.settings) }
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
                val result = outcome.getOrNull()
                val verdict = when {
                    result == null -> Verdict.ERROR
                    result.confirmed -> Verdict.CONFIRMED
                    else -> Verdict.REJECTED
                }
                val record = CandidateRecord(
                    atMs = candidate.atMs,
                    streamMs = streamMs,
                    stage1Peak = candidate.peak,
                    settings = candidate.settings,
                    verdict = verdict,
                    keywords = result?.keywords.orEmpty(),
                    stage2Ms = elapsedMs,
                    triggered = verdict == Verdict.CONFIRMED,
                    error = outcome.exceptionOrNull()?.let { it.message ?: it.javaClass.name },
                )
                onCandidate(record, window)
            },
        )
    }

    companion object {
        /** Audio kept for stage 2 (report §6.1: a 2.5 s ring buffer). */
        const val RING_MS = 2_500L

        /** The window stage 2 checks: the last ~2 s, ending [POST_ROLL_MS] after the candidate opened. */
        const val WINDOW_MS = 2_000L

        /**
         * Audio collected after the candidate opens before stage 2 runs. openWakeWord peaks around the end of
         * "jarvis", so this lets the word finish inside the window.
         */
        const val POST_ROLL_MS = 400L

        /** One utterance, one candidate: the report's 2 s activation refractory period (§4.1). */
        const val REFRACTORY_MS = 2_000L

        private const val POST_ROLL_FRAMES = POST_ROLL_MS / Pcm.FRAME_MS
        private const val REFRACTORY_FRAMES = REFRACTORY_MS / Pcm.FRAME_MS
        private const val NANOS_PER_MS = 1_000_000L
    }
}
