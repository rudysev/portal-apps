package com.portal.jarviscascade.chain

/** The thresholds the chain reads live, so a change in the app applies to the next frame or candidate. */
data class CascadeSettings(
    /** Stage-1 score at or above which a frame opens a candidate. Recall-tuned: 0.3, as in report §4. */
    val stage1Threshold: Float = DEFAULT_STAGE1_THRESHOLD,
    /** sherpa-onnx `keywords_threshold`: lower accepts more. */
    val stage2Threshold: Float = DEFAULT_STAGE2_THRESHOLD,
    /** sherpa-onnx `keywords_score` (boost): higher accepts more. */
    val stage2Boost: Float = DEFAULT_STAGE2_BOOST,
) {
    companion object {
        // The off-device cascade with the best recall in report §4.3: oww@0.3 AND sherpa(0.15, 1.5).
        const val DEFAULT_STAGE1_THRESHOLD = 0.3f
        const val DEFAULT_STAGE2_THRESHOLD = 0.15f
        const val DEFAULT_STAGE2_BOOST = 1.5f
    }
}

/** Stage 1: the always-on acoustic detector, scoring every frame. */
fun interface Stage1 {
    /** The wake score in [0, 1] after this [Pcm.FRAME_SAMPLES]-sample frame. */
    fun score(frame: ShortArray): Float
}

/** Stage 2: the sequence check, run only on a stage-1 candidate's buffered window. May block for a while. */
fun interface Stage2 {
    fun verify(window: ShortArray, settings: CascadeSettings): Stage2Result
}

/** What stage 2 made of one window: the keywords it spotted, and whether that confirms "HEY JARVIS". */
data class Stage2Result(val confirmed: Boolean, val keywords: List<String>)
