package com.portal.jarviscascade.chain

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLinesTest {
    private val at = 1_793_400_000_123L
    private val iso = "2026-10-30T22:40:00.123Z"

    @Test
    fun `a triggered candidate line carries both stages and the outcome`() {
        val record = CandidateRecord(
            atMs = at,
            streamMs = 2_480,
            stage1Peak = 0.91f,
            settings = CascadeSettings(0.3f, 0.15f, 1.5f),
            verdict = Verdict.CONFIRMED,
            keywords = listOf("HEY_JARVIS"),
            stage2Ms = 182,
            triggered = true,
        )
        assertEquals(
            """{"type":"candidate","at":"$iso","atMs":$at,"streamMs":2480,"source":"VOICE_RECOGNITION",""" +
                """"stage1Peak":0.91,"stage1Threshold":0.3,"stage2":"confirmed","stage2Threshold":0.15,""" +
                """"stage2Boost":1.5,"stage2Keywords":"HEY_JARVIS","stage2Ms":182,"triggered":true,""" +
                """"audio":"cand.wav"}""",
            LogLines.candidate(record, "VOICE_RECOGNITION", "cand.wav"),
        )
    }

    @Test
    fun `a failed candidate line carries the error and omits absent audio`() {
        val record =
            CandidateRecord(at, 80, 0.5f, CascadeSettings(), Verdict.ERROR, emptyList(), 3, false, "boom \"x\"")
        assertEquals(
            """{"type":"candidate","at":"$iso","atMs":$at,"streamMs":80,"source":"MIC","stage1Peak":0.5,""" +
                """"stage1Threshold":0.3,"stage2":"error","stage2Threshold":0.15,"stage2Boost":1.5,""" +
                """"stage2Keywords":"","stage2Ms":3,"triggered":false,"error":"boom \"x\""}""",
            LogLines.candidate(record, "MIC", null),
        )
    }

    @Test
    fun `marks, silencing, stop and session lines`() {
        assertEquals(
            """{"type":"mark","at":"$iso","atMs":$at,"mark":"missed"}""",
            LogLines.mark(at, Mark.MISSED, null),
        )
        assertEquals(
            """{"type":"mark","at":"$iso","atMs":$at,"mark":"false_trigger","candidateAtMs":42}""",
            LogLines.mark(at, Mark.FALSE_TRIGGER, 42),
        )
        assertEquals("""{"type":"silenced","at":"$iso","atMs":$at,"silenced":true}""", LogLines.silenced(at, true))
        assertEquals(
            """{"type":"stopped","at":"$iso","atMs":$at,"reason":"line1\nline2\u0001"}""",
            LogLines.stopped(at, "line1\nline2\u0001"),
        )
        assertEquals(
            """{"type":"session","at":"$iso","atMs":$at,"source":"MIC","device":"Portal+","sdk":28,""" +
                """"stage1Threshold":0.3,"stage2Threshold":0.15,"stage2Boost":1.5}""",
            LogLines.session(at, "MIC", "Portal+", 28, CascadeSettings()),
        )
    }

    @Test
    fun `stats lines write a silent level as null`() {
        val stats = CaptureStats().apply { add(ShortArray(Pcm.FRAME_SAMPLES), 2_000_000) }.snapshot()
        assertEquals(
            """{"type":"stats","at":"$iso","atMs":$at,"frames":1,"levelDbfs":null,"zeroFraction":1.0,""" +
                """"stage1MsMean":2.0,"stage1MsMax":2.0}""",
            LogLines.stats(at, stats),
        )
    }
}
