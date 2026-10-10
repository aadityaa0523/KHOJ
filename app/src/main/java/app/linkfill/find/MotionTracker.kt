package app.linkfill.find

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.linkfill.core.find.MotionClass
import app.linkfill.core.find.StepDetector

/** Counts steps from the accelerometer (no activity-recognition permission needed). Runs only while a Find session is active. */
class MotionTracker(
    context: Context,
    private val onStep: (timeMs: Long, strideM: Double) -> Unit,
) : SensorEventListener {
    constructor(context: Context, onStepSimple: (Long) -> Unit) : this(context, { t, _ -> onStepSimple(t) })

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val detector = StepDetector()
    private val stepHistory = ArrayDeque<Long>()
    var running = false
        private set

    fun start() {
        if (running) return
        val acc = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        running = true
        stepHistory.clear()
    }

    fun stop() {
        sensors?.unregisterListener(this)
        running = false
        stepHistory.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        if (detector.add(e.timestamp / 1_000_000, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())) {
            stepHistory.addLast(now)
            while (stepHistory.isNotEmpty() && now - stepHistory.first() > 5_000) {
                stepHistory.removeFirst()
            }
            onStep(now, detector.lastStrideM)
        }
    }

    fun cadence(now: Long = System.currentTimeMillis(), windowMs: Long = 4_000): Double {
        while (stepHistory.isNotEmpty() && now - stepHistory.first() > windowMs) {
            stepHistory.removeFirst()
        }
        if (stepHistory.size < 2) return 0.0
        val dt = (now - stepHistory.first()) / 1000.0
        return if (dt > 0.0) (stepHistory.size - 1) / dt else 0.0
    }

    fun motionClass(now: Long = System.currentTimeMillis()): MotionClass =
        MotionClass.fromCadence(cadence(now))

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
