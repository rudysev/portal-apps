package com.portal.jarviscascade

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portal.jarviscascade.chain.Wav
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The on-device log: every line goes to `cascade-log.jsonl` in the app's external files dir
 * (`/sdcard/Android/data/com.portal.jarviscascade/files/`, readable with `adb pull`, no root), to logcat, and to a
 * short in-memory list the screen shows. Saved candidate windows go to `candidates/` beside it. One per process
 * ([of]); safe from any thread.
 */
class EventLog private constructor(private val dir: File) {
    val file: File = dir.resolve("cascade-log.jsonl")
    private val recent = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Wall clock of the latest trigger, so a "false trigger" mark can name the candidate it means. */
    @Volatile var lastTriggerAtMs: Long? = null

    /** Appends [json] to the log file and [display] to the on-screen list. */
    fun append(json: String, display: String) {
        Log.i(TAG, json)
        synchronized(this) {
            try {
                dir.mkdirs()
                file.appendText(json + "\n")
            } catch (e: IOException) {
                Log.e(TAG, "can't write ${file.path}", e)
            }
            recent.addFirst(display)
            while (recent.size > RECENT_LINES) recent.removeLast()
        }
        main.post { listeners.toList().forEach { it() } }
    }

    /** Saves a candidate's window as a WAV; returns its name relative to the log's directory, or null on failure. */
    fun saveWindow(atMs: Long, window: ShortArray): String? {
        val name = "candidates/cand-$atMs.wav"
        return try {
            dir.resolve("candidates").mkdirs()
            dir.resolve(name).writeBytes(Wav.encode(window))
            name
        } catch (e: IOException) {
            Log.e(TAG, "can't save $name", e)
            null
        }
    }

    fun recentLines(): List<String> = synchronized(this) { recent.toList() }

    /** Main thread only. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Main thread only. */
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    companion object {
        private const val TAG = "JarvisCascade"
        private const val RECENT_LINES = 200

        @Volatile private var instance: EventLog? = null

        /** Local time of day, for the on-screen lines. */
        fun clock(atMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(atMs))

        fun of(context: Context): EventLog = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: EventLog(app.getExternalFilesDir(null) ?: app.filesDir).also { instance = it }
        }
    }
}
