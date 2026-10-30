package com.portal.jarviscascade

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.portal.jarviscascade.chain.CascadeSettings
import com.portal.jarviscascade.chain.LogLines
import com.portal.jarviscascade.chain.Mark
import java.util.Locale

/**
 * The test screen: start/stop, the audio source, the two stages' thresholds, the audio-saving switch, the two
 * ground-truth marks, and the newest log lines. Kept awake, because on Android 10 it must stay in front to hear.
 */
class MainActivity : Activity() {
    private lateinit var settings: TestSettings
    private lateinit var log: EventLog
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var logView: TextView
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = TestSettings.of(this)
        log = EventLog.of(this)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        logView = findViewById(R.id.log)
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            findViewById<View>(R.id.foreground_only).visibility = View.VISIBLE
        }
        findViewById<TextView>(R.id.log_path).text = getString(R.string.log_path, log.file.path)

        toggle.setOnClickListener { if (CascadeService.running) CascadeService.stop(this) else startListening() }
        setUpSource()
        setUpThresholds()
        findViewById<CheckBox>(R.id.save_audio).apply {
            isChecked = settings.saveAudio
            setOnCheckedChangeListener { _, checked -> settings.saveAudio = checked }
        }
        findViewById<Button>(R.id.mark_missed).setOnClickListener { mark(Mark.MISSED) }
        findViewById<Button>(R.id.mark_false_trigger).setOnClickListener { mark(Mark.FALSE_TRIGGER) }
    }

    override fun onStart() {
        super.onStart()
        log.addListener(refresh)
        render()
    }

    override fun onStop() {
        log.removeListener(refresh)
        super.onStop()
    }

    private fun setUpSource() {
        val spinner = findViewById<Spinner>(R.id.source)
        val sources = AudioSource.entries
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.name })
        spinner.setSelection(sources.indexOf(settings.audioSource))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = sources[position]
                if (chosen == settings.audioSource) return
                settings.audioSource = chosen
                if (CascadeService.running) CascadeService.restart(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpThresholds() {
        val stage1 = findViewById<EditText>(R.id.stage1_threshold)
        val stage2 = findViewById<EditText>(R.id.stage2_threshold)
        val boost = findViewById<EditText>(R.id.stage2_boost)
        val current = settings.cascade
        stage1.setText(format(current.stage1Threshold))
        stage2.setText(format(current.stage2Threshold))
        boost.setText(format(current.stage2Boost))
        findViewById<Button>(R.id.apply).setOnClickListener {
            val parsed = parseSettings(stage1.text.toString(), stage2.text.toString(), boost.text.toString())
            if (parsed == null) {
                Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show()
            } else {
                settings.cascade = parsed
            }
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CascadeService.start(this)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            CascadeService.start(this)
        } else {
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun mark(mark: Mark) {
        val now = System.currentTimeMillis()
        val candidate = if (mark == Mark.FALSE_TRIGGER) log.lastTriggerAtMs else null
        log.append(LogLines.mark(now, mark, candidate), "${EventLog.clock(now)} MARK ${mark.label}")
        Toast.makeText(this, getString(R.string.marked, mark.label), Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val running = CascadeService.running
        status.text =
            if (running) {
                getString(
                    R.string.status_listening,
                    settings.audioSource.name,
                )
            } else {
                getString(R.string.status_stopped)
            }
        toggle.setText(if (running) R.string.stop else R.string.start)
        logView.text = log.recentLines().joinToString("\n")
    }

    private companion object {
        const val MIC_REQUEST = 1

        fun format(value: Float) = String.format(Locale.ROOT, "%.2f", value)

        /** Thresholds in [0, 1] and a positive boost, or null. */
        fun parseSettings(stage1: String, stage2: String, boost: String): CascadeSettings? {
            val unit = 0f..1f
            val t1 = stage1.toFloatOrNull()?.takeIf { it in unit }
            val t2 = stage2.toFloatOrNull()?.takeIf { it in unit }
            val b = boost.toFloatOrNull()?.takeIf { it > 0f }
            return if (t1 != null && t2 != null && b != null) CascadeSettings(t1, t2, b) else null
        }
    }
}
