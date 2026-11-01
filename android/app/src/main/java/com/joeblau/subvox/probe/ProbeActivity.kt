package com.joeblau.subvox.probe

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.joeblau.subvox.core.HeadTrackerConverter
import com.joeblau.subvox.core.MotionSample
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 feasibility probe for Pixel Buds Pro 2.
 *
 * Answers, on a real phone with the buds connected:
 *  1. Does a third-party app see the buds' head tracker as a dynamic `TYPE_HEAD_TRACKER` sensor?
 *  2. What does it stream (layout, rate, discontinuities)?
 *  3. Does a hold-to-mark segment of mouthing look different from rest?
 *
 * Writes `motion.csv` (same columns as the macOS app) and `events.csv` to
 * `/sdcard/Android/data/com.joeblau.subvox.probe/files/Sessions/<timestamp>/`.
 */
class ProbeActivity : Activity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var audioManager: AudioManager
    private lateinit var info: TextView
    private lateinit var live: TextView
    private lateinit var startButton: Button
    private lateinit var markButton: Button

    private val main = Handler(Looper.getMainLooper())
    private val converter = HeadTrackerConverter()

    private var tracker: Sensor? = null
    private var streaming = false
    private var sessionDir: File? = null
    private var motionOut: BufferedWriter? = null
    private var eventsOut: BufferedWriter? = null
    private var t0: Double? = null
    private var latest: MotionSample? = null
    private var rawLength = 0
    private var sampleCount = 0
    private var discontinuities = 0
    private val rateWindow = ArrayDeque<Double>()
    private var markStart: Double? = null

    private val dynamicCallback = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) = refreshInfo()
        override fun onDynamicSensorDisconnected(sensor: Sensor) {
            if (sensor == tracker) stopStreaming()
            refreshInfo()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderLive()
            main.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        info = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        live = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 13f }
        startButton = Button(this).apply {
            text = "Start recording"
            setOnClickListener { if (streaming) stopStreaming() else startStreaming() }
        }
        markButton = Button(this).apply {
            text = "Hold while mouthing"
            isEnabled = false
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> markStart = latest?.t
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endMark()
                }
                false
            }
        }
        val refresh = Button(this).apply { text = "Refresh sensors"; setOnClickListener { refreshInfo() } }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(startButton); addView(markButton); addView(refresh); addView(live); addView(info)
        }
        setContentView(ScrollView(this).apply { addView(column) })

        sensorManager.registerDynamicSensorCallback(dynamicCallback, main)
        audioManager.spatializer.addOnHeadTrackerAvailableListener(mainExecutor) { _, _ -> refreshInfo() }
        refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        stopStreaming()
        sensorManager.unregisterDynamicSensorCallback(dynamicCallback)
        super.onDestroy()
    }

    private fun refreshInfo() {
        val dynamic = sensorManager.getDynamicSensorList(Sensor.TYPE_ALL)
        tracker = sensorManager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER).firstOrNull()
            ?: sensorManager.getSensorList(Sensor.TYPE_HEAD_TRACKER).firstOrNull()
        val spatializer = audioManager.spatializer

        info.text = buildString {
            appendLine("Dynamic sensor discovery supported: ${sensorManager.isDynamicSensorDiscoverySupported}")
            appendLine("Spatializer available=${spatializer.isAvailable} enabled=${spatializer.isEnabled} " +
                "level=${spatializer.immersiveAudioLevel}")
            appendLine("Spatializer head tracker available: ${spatializer.isHeadTrackerAvailable}")
            appendLine()
            val t = tracker
            if (t == null) {
                appendLine("No TYPE_HEAD_TRACKER sensor visible to this app.")
                if (spatializer.isHeadTrackerAvailable) {
                    // SensorService hides head trackers from non-system apps unless lifted from adb.
                    appendLine("The system sees a head tracker, but Android hides it from apps by default.")
                    appendLine("Lift the restriction from a computer (lasts until reboot), then tap Refresh sensors:")
                    appendLine("  adb shell cmd sensorservice unrestrict-ht")
                } else {
                    appendLine("Check: buds connected, Settings › Connected devices › Pixel Buds › Spatial Audio › Head tracking on.")
                }
            } else {
                appendLine("HEAD TRACKER: ${t.name}")
                appendLine("  vendor=${t.vendor} version=${t.version} id=${t.id} dynamic=${t.isDynamicSensor}")
                appendLine("  minDelay=${t.minDelay} µs (max ${if (t.minDelay > 0) 1_000_000 / t.minDelay else 0} Hz) " +
                    "maxDelay=${t.maxDelay} µs reportingMode=${t.reportingMode}")
                appendLine("  fifoMax=${t.fifoMaxEventCount} power=${t.power} mA wakeUp=${t.isWakeUpSensor}")
            }
            appendLine()
            appendLine("All dynamic sensors (${dynamic.size}):")
            dynamic.forEach { appendLine("  [${it.type}] ${it.stringType} — ${it.name} (${it.vendor})") }
        }
        startButton.isEnabled = tracker != null || streaming
    }

    private fun startStreaming() {
        val sensor = tracker ?: return
        converter.reset()
        sampleCount = 0
        discontinuities = 0
        rateWindow.clear()
        t0 = null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(getExternalFilesDir(null), "Sessions/$stamp-pixelbuds").apply { mkdirs() }
        sessionDir = dir
        motionOut = File(dir, "motion.csv").bufferedWriter().apply { write(MotionSample.CSV_HEADER + ",discontinuity\n") }
        eventsOut = File(dir, "events.csv").bufferedWriter().apply { write("start,end,kind,label\n") }
        File(dir, "meta.json").writeText(
            """{"device":"${sensor.name}","vendor":"${sensor.vendor}","minDelayUs":${sensor.minDelay},""" +
                """"condition":"probe","platform":"android"}""" + "\n",
        )

        streaming = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, main)
        if (!streaming) closeFiles()
        startButton.text = if (streaming) "Stop recording" else "Start failed — tap to retry"
        markButton.isEnabled = streaming
    }

    private fun stopStreaming() {
        if (!streaming) return
        sensorManager.unregisterListener(this)
        streaming = false
        closeFiles()
        startButton.text = "Start recording"
        markButton.isEnabled = false
    }

    private fun closeFiles() {
        motionOut?.close(); motionOut = null
        eventsOut?.close(); eventsOut = null
    }

    private fun endMark() {
        val start = markStart ?: return
        val end = latest?.t ?: return
        val origin = t0 ?: return
        markStart = null
        eventsOut?.write(String.format(Locale.ROOT, "%.6f,%.6f,mark,\n", start - origin, end - origin))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEAD_TRACKER) return
        rawLength = event.values.size
        val reading = converter.convert(event.timestamp, event.values, event.firstEventAfterDiscontinuity) ?: return
        val s = reading.sample
        val origin = t0 ?: s.t.also { t0 = it }
        if (reading.discontinuity) discontinuities++
        sampleCount++
        latest = s
        rateWindow.addLast(s.t)
        while (rateWindow.isNotEmpty() && rateWindow.first() < s.t - 1) rateWindow.removeFirst()
        motionOut?.write(s.csvRow(origin) + "," + (if (reading.discontinuity) 1 else 0) + "\n")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun renderLive() {
        val s = latest
        val rate = if (rateWindow.size > 1) (rateWindow.size - 1) / (rateWindow.last() - rateWindow.first()) else 0.0
        live.text = buildString {
            appendLine(if (streaming) "● recording → ${sessionDir?.absolutePath}" else "○ idle")
            appendLine(String.format(Locale.ROOT, "rate %.1f Hz  samples %d  discontinuities %d  values[%d]",
                rate, sampleCount, discontinuities, rawLength))
            if (s != null) {
                appendLine(String.format(Locale.ROOT, "ω  %+.3f %+.3f %+.3f rad/s", s.rx, s.ry, s.rz))
                appendLine(String.format(Locale.ROOT, "pitch %+.3f  roll %+.3f  yaw %+.3f rad", s.pitch, s.roll, s.yaw))
            }
            if (markStart != null) appendLine("MARKING…")
        }
    }
}
