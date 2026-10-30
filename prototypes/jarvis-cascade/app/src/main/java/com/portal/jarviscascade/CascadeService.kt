package com.portal.jarviscascade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The foreground service that owns the mic while the test runs, so a gen-1 Portal (Android 9) keeps listening with
 * the app in the background. On a gen-2 Portal (Android 10) the Portal only lets the app capture while it is in the
 * foreground, so keep [MainActivity] on screen there.
 *
 * Runs one [CaptureSession] at a time. A restart (new audio source) waits for the old session to release the mic
 * before opening the new one: Android 9 capture is exclusive. Beeps on every trigger.
 */
class CascadeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: CaptureSession? = null
    private var startWhenStopped = false
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        running = true
        if (tone ==
            null
        ) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME) }.getOrNull()
        }
        val current = session
        if (current == null) {
            startSession()
        } else if (intent?.action == ACTION_RESTART) {
            startWhenStopped = true
            current.stop()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        val source = TestSettings.of(this).audioSource
        session = CaptureSession(
            this,
            source,
            onTrigger = { main.post { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) } },
            onStopped = { stopped -> main.post { onSessionStopped(stopped) } },
        ).also { it.start() }
    }

    private fun onSessionStopped(stopped: CaptureSession) {
        if (session !== stopped) return
        session = null
        if (startWhenStopped && running) {
            startWhenStopped = false
            startSession()
        } else if (running) {
            // Capture ended on its own (the source failed or the mic was lost): nothing left to serve.
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        session?.stop()
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val ACTION_RESTART = "com.portal.jarviscascade.RESTART"
        private const val CHANNEL_ID = "cascade_listening"
        private const val NOTIFICATION_ID = 1
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 150

        /** True from start until the service is destroyed. Main thread writes. */
        @Volatile var running = false
            private set

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, CascadeService::class.java))

        /** Restarts capture, e.g. on a new audio source; starts it when it isn't running. */
        fun restart(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, CascadeService::class.java).setAction(ACTION_RESTART),
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, CascadeService::class.java))
        }
    }
}
