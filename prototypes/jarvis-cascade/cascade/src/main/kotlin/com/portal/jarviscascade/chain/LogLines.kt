package com.portal.jarviscascade.chain

import java.time.Instant

/**
 * The on-device log's lines: one JSON object per line (JSON Lines), each with a `type` and the event's wall clock as
 * both `at` (ISO-8601 UTC) and `atMs` (epoch ms). Counting false accepts and false rejects later (report §8) reads
 * `candidate` lines plus the tester's `mark` lines.
 */
object LogLines {
    /** A capture session started: what it captures from and on which device. */
    fun session(atMs: Long, source: String, device: String, sdk: Int, settings: CascadeSettings): String =
        JsonLine("session", atMs)
            .put("source", source)
            .put("device", device)
            .put("sdk", sdk)
            .put("stage1Threshold", settings.stage1Threshold)
            .put("stage2Threshold", settings.stage2Threshold)
            .put("stage2Boost", settings.stage2Boost)
            .toString()

    /** One stage-1 candidate; [audioFile] names its saved window, when audio saving is on. */
    fun candidate(record: CandidateRecord, source: String, audioFile: String?): String =
        JsonLine("candidate", record.atMs)
            .put("streamMs", record.streamMs)
            .put("source", source)
            .put("stage1Peak", record.stage1Peak)
            .put("stage1Threshold", record.settings.stage1Threshold)
            .put("stage2", record.verdict.label)
            .put("stage2Threshold", record.settings.stage2Threshold)
            .put("stage2Boost", record.settings.stage2Boost)
            .put("stage2Keywords", record.keywords.joinToString(" "))
            .put("stage2Ms", record.stage2Ms)
            .put("triggered", record.triggered)
            .putIfPresent("error", record.error)
            .putIfPresent("audio", audioFile)
            .toString()

    /** A tester's ground-truth mark (see [Mark]); a false trigger names the triggered candidate it means. */
    fun mark(atMs: Long, mark: Mark, candidateAtMs: Long?): String = JsonLine("mark", atMs)
        .put("mark", mark.label)
        .putIfPresent("candidateAtMs", candidateAtMs)
        .toString()

    /** Android 10's record-silencing state changed: while silenced the detector is deaf, not hearing quiet. */
    fun silenced(atMs: Long, silenced: Boolean): String =
        JsonLine("silenced", atMs).put("silenced", silenced).toString()

    /** Capture stopped or failed. */
    fun stopped(atMs: Long, reason: String): String = JsonLine("stopped", atMs).put("reason", reason).toString()

    /** A periodic summary of the capture and stage-1 cost (see [CaptureStats]). */
    fun stats(atMs: Long, stats: CaptureStats.Snapshot): String = JsonLine("stats", atMs)
        .put("frames", stats.frames)
        .put("levelDbfs", stats.levelDbfs)
        .put("zeroFraction", stats.zeroFraction)
        .put("stage1MsMean", stats.stage1MsMean)
        .put("stage1MsMax", stats.stage1MsMax)
        .toString()
}

/** Ground truth a tester adds while testing. */
enum class Mark(val label: String) {
    /** "I said Hey Jarvis and nothing triggered": a false reject. */
    MISSED("missed"),

    /** "That last trigger wasn't me / wasn't Hey Jarvis": a false accept. */
    FALSE_TRIGGER("false_trigger"),
}

/** Builds one flat JSON object. Numbers are written as given; NaN and infinities become null. */
internal class JsonLine(type: String, atMs: Long) {
    private val out = StringBuilder("{")

    init {
        put("type", type)
        put("at", Instant.ofEpochMilli(atMs).toString())
        put("atMs", atMs)
    }

    fun put(key: String, value: String): JsonLine = raw(key, quote(value))

    fun put(key: String, value: Number): JsonLine {
        val finite = when (value) {
            is Float -> value.isFinite()
            is Double -> value.isFinite()
            else -> true
        }
        return raw(key, if (finite) value.toString() else "null")
    }

    fun put(key: String, value: Boolean): JsonLine = raw(key, value.toString())

    fun putIfPresent(key: String, value: String?): JsonLine = if (value == null) this else put(key, value)

    fun putIfPresent(key: String, value: Long?): JsonLine = if (value == null) this else put(key, value)

    private fun raw(key: String, json: String): JsonLine {
        if (out.length > 1) out.append(',')
        out.append(quote(key)).append(':').append(json)
        return this
    }

    override fun toString(): String = "$out}"

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private companion object {
        const val HEX = 16
        const val UNICODE_ESCAPE_DIGITS = 4
    }
}
