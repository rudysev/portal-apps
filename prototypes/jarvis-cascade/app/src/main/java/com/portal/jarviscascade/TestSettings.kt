package com.portal.jarviscascade

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import com.portal.jarviscascade.chain.CascadeSettings

/** The capture sources a tester can compare (report §8 step 1). */
enum class AudioSource(val id: Int) {
    // The Portal-proven default: portal-wake captures plain VOICE_RECOGNITION.
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC(MediaRecorder.AudioSource.MIC),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT),
}

/**
 * The tester's settings, kept in SharedPreferences. One instance per process ([of]); the capture threads read it live,
 * so a threshold change applies to the next frame (stage 1) or candidate (stage 2).
 */
class TestSettings private constructor(private val prefs: SharedPreferences) {
    @Volatile var cascade: CascadeSettings = CascadeSettings(
        stage1Threshold = prefs.getFloat(STAGE1_THRESHOLD, CascadeSettings.DEFAULT_STAGE1_THRESHOLD),
        stage2Threshold = prefs.getFloat(STAGE2_THRESHOLD, CascadeSettings.DEFAULT_STAGE2_THRESHOLD),
        stage2Boost = prefs.getFloat(STAGE2_BOOST, CascadeSettings.DEFAULT_STAGE2_BOOST),
    )
        set(value) {
            field = value
            prefs.edit()
                .putFloat(STAGE1_THRESHOLD, value.stage1Threshold)
                .putFloat(STAGE2_THRESHOLD, value.stage2Threshold)
                .putFloat(STAGE2_BOOST, value.stage2Boost)
                .apply()
        }

    /** Read when a capture session starts; changing it restarts capture. */
    @Volatile var audioSource: AudioSource =
        AudioSource.entries.firstOrNull { it.name == prefs.getString(SOURCE, null) } ?: AudioSource.VOICE_RECOGNITION
        set(value) {
            field = value
            prefs.edit().putString(SOURCE, value.name).apply()
        }

    /** Save each candidate's 2 s window as a WAV next to the log. Off by default: it is room audio. */
    @Volatile var saveAudio: Boolean = prefs.getBoolean(SAVE_AUDIO, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(SAVE_AUDIO, value).apply()
        }

    companion object {
        private const val STAGE1_THRESHOLD = "stage1Threshold"
        private const val STAGE2_THRESHOLD = "stage2Threshold"
        private const val STAGE2_BOOST = "stage2Boost"
        private const val SOURCE = "audioSource"
        private const val SAVE_AUDIO = "saveAudio"

        @Volatile private var instance: TestSettings? = null

        fun of(context: Context): TestSettings = instance ?: synchronized(this) {
            instance ?: TestSettings(
                context.applicationContext.getSharedPreferences("cascade", Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
