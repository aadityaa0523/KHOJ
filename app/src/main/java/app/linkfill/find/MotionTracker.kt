package app.linkfill.find

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.linkfill.core.find.StepDetector

/** Counts steps from the accelerometer (no activity-recognition permission needed). Runs only while a Find session is active. */
class MotionTracker(context: Context, private val onStep: (Long) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val detector = StepDetector()
    var running = false
        private set

    fun start() {
        if (running) return
        val acc = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        running = true
    }

    fun stop() { sensors?.unregisterListener(this); running = false }

    override fun onSensorChanged(e: SensorEvent) {
        if (detector.add(e.timestamp / 1_000_000, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())) onStep(System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
