package com.portal.jarviscascade.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadeChainTest {
    /** Stage 1 that replays scripted per-frame scores (0 once the script runs out). */
    private class ScriptedStage1(private val scores: Map<Int, Float>) : Stage1 {
        var frames = 0
        override fun score(frame: ShortArray): Float = scores[frames++] ?: 0f
    }

    private class RecordingStage2(private val answer: (ShortArray) -> Stage2Result) : Stage2 {
        val windows = mutableListOf<ShortArray>()
        val settingsSeen = mutableListOf<CascadeSettings>()
        override fun verify(window: ShortArray, settings: CascadeSettings): Stage2Result {
            windows += window
            settingsSeen += settings
            return answer(window)
        }
    }

    private val records = mutableListOf<CandidateRecord>()
    private val deferred = mutableListOf<Runnable>()
    private var settings = CascadeSettings()
    private val clock = 1_000_000L

    private fun chain(stage1: Stage1, stage2: Stage2, runStage2: (Runnable) -> Unit = { it.run() }) =
        CascadeChain(stage1, stage2, { settings }, runStage2, { clock }, { record, _ -> records += record })

    /** Frame [index]: every sample holds its frame index, so a window's samples tell which frames it spans. */
    private fun frame(index: Int) = ShortArray(Pcm.FRAME_SAMPLES) { index.toShort() }

    private fun CascadeChain.feed(frames: IntRange) = frames.forEach { onFrame(frame(it)) }

    private val confirm = { _: ShortArray -> Stage2Result(true, listOf("HEY_JARVIS")) }
    private val reject = { _: ShortArray -> Stage2Result(false, emptyList()) }

    @Test
    fun `a frame below the stage-1 threshold opens no candidate`() {
        val stage2 = RecordingStage2(confirm)
        chain(ScriptedStage1(mapOf(10 to 0.29f)), stage2).feed(0..60)
        assertTrue(stage2.windows.isEmpty())
        assertTrue(records.isEmpty())
    }

    @Test
    fun `a candidate confirmed by stage 2 triggers, with the window ending the post-roll after the opening frame`() {
        val stage2 = RecordingStage2(confirm)
        chain(ScriptedStage1(mapOf(30 to 0.3f, 32 to 0.9f)), stage2).feed(0..40)

        val window = stage2.windows.single()
        assertEquals(Pcm.samplesFor(CascadeChain.WINDOW_MS), window.size)
        // Opened on frame 30; 400 ms (5 frames) of post-roll, so the window's last frame is 35, its first 35 - 24.
        assertEquals(35, window.last().toInt())
        assertEquals(11, window.first().toInt())

        val r = records.single()
        assertEquals(Verdict.CONFIRMED, r.verdict)
        assertTrue(r.triggered)
        assertEquals(0.9f, r.stage1Peak)
        assertEquals(listOf("HEY_JARVIS"), r.keywords)
        assertEquals(31 * Pcm.FRAME_MS, r.streamMs)
        assertEquals(clock, r.atMs)
    }

    @Test
    fun `a candidate stage 2 rejects is logged but does not trigger`() {
        chain(ScriptedStage1(mapOf(30 to 0.95f)), RecordingStage2(reject)).feed(0..40)
        val r = records.single()
        assertEquals(Verdict.REJECTED, r.verdict)
        assertFalse(r.triggered)
        assertEquals(0.95f, r.stage1Peak)
    }

    @Test
    fun `a stage-2 failure is an error verdict that never triggers`() {
        chain(ScriptedStage1(mapOf(30 to 0.95f)), RecordingStage2 { error("spotter gone") }).feed(0..40)
        val r = records.single()
        assertEquals(Verdict.ERROR, r.verdict)
        assertFalse(r.triggered)
        assertEquals("spotter gone", r.error)
    }

    @Test
    fun `stage 2 runs only on candidates, once per candidate`() {
        val stage1 = ScriptedStage1((30..36).associateWith { 0.8f })
        val stage2 = RecordingStage2(confirm)
        chain(stage1, stage2).feed(0..200)
        assertEquals(201, stage1.frames)
        assertEquals(1, stage2.windows.size)
    }

    @Test
    fun `a second candidate inside the refractory period is ignored, one after it opens`() {
        // Refractory 2 s = 25 frames after the frame that opened the first candidate (30).
        val stage2 = RecordingStage2(reject)
        chain(ScriptedStage1(mapOf(30 to 0.8f, 54 to 0.8f, 55 to 0.8f)), stage2).feed(0..80)
        assertEquals(2, records.size)
        assertEquals(56 * Pcm.FRAME_MS, records[1].streamMs)
    }

    @Test
    fun `the window is shorter while the ring is still filling`() {
        val stage2 = RecordingStage2(confirm)
        chain(ScriptedStage1(mapOf(5 to 0.8f)), stage2).feed(0..20)
        val window = stage2.windows.single()
        assertEquals(11 * Pcm.FRAME_SAMPLES, window.size)
        assertEquals(0, window.first().toInt())
        assertEquals(10, window.last().toInt())
    }

    @Test
    fun `stage 2 runs off the capture thread and later frames don't change the window it was handed`() {
        val stage2 = RecordingStage2(confirm)
        val c = chain(ScriptedStage1(mapOf(30 to 0.8f)), stage2) { deferred += it }
        c.feed(0..60)
        assertTrue(records.isEmpty())
        deferred.single().run()
        assertEquals(35, stage2.windows.single().last().toInt())
        assertTrue(records.single().triggered)
    }

    @Test
    fun `thresholds are read live, and a candidate keeps the settings it opened with`() {
        val stage1 = ScriptedStage1(mapOf(10 to 0.5f, 40 to 0.5f))
        val stage2 = RecordingStage2(confirm)
        val c = chain(stage1, stage2)
        settings = CascadeSettings(stage1Threshold = 0.6f)
        c.feed(0..12)
        assertTrue(records.isEmpty())

        settings = CascadeSettings(stage1Threshold = 0.4f, stage2Threshold = 0.2f)
        c.feed(13..40)
        settings = CascadeSettings(stage1Threshold = 0.4f, stage2Threshold = 0.9f)
        c.feed(41..50)
        assertEquals(0.2f, stage2.settingsSeen.single().stage2Threshold)
        assertEquals(0.2f, records.single().settings.stage2Threshold)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frames must be 80 ms`() {
        chain(ScriptedStage1(emptyMap()), RecordingStage2(confirm)).onFrame(ShortArray(Pcm.FRAME_SAMPLES - 1))
    }

    @Test
    fun `the window handed to the listener is the one stage 2 saw`() {
        val stage2 = RecordingStage2(confirm)
        val windows = mutableListOf<ShortArray>()
        CascadeChain(
            ScriptedStage1(mapOf(30 to 0.8f)),
            stage2,
            { settings },
            { it.run() },
            { clock },
        ) { _, window -> windows += window }.feed(0..40)
        assertArrayEquals(stage2.windows.single(), windows.single())
    }
}
