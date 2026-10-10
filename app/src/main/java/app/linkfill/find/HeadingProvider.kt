package app.linkfill.find

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Display
import android.view.WindowManager
import app.linkfill.core.find.Angles
import app.linkfill.core.find.HeadingFix
import app.linkfill.core.find.Orientation
import app.linkfill.core.find.Posture

/**
 * The way the phone is pointing, referenced to TRUE north.
 *
 * - Source: `TYPE_ROTATION_VECTOR` (accelerometer + gyroscope + magnetometer fused by the platform), converted to a heading by
 *   [Orientation.heading], which handles flat and upright postures and display rotation (tested in core).
 * - Magnetic to true: the rotation vector is relative to magnetic north, so [GeomagneticField] declination is added using the
 *   latest known position (set by [setLocation]); without a position the declination is 0 and [declinationKnown] is false.
 * - Uncertainty: the sensor's own estimated heading accuracy (`values[4]`, radians) when the platform provides it, otherwise a
 *   coarse value from the sensor status (calibration level). This is the platform's estimate, not a measured error; near steel
 *   structures or speakers magnetic interference can exceed it, which is why the UI drops the arrow if it grows too large.
 */
class HeadingProvider(private val context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var status = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var declination = 0.0
    var declinationKnown = false
        private set
    var posture: Posture? = null
        private set
    var latest: HeadingFix? = null
        private set
    var onHeading: ((HeadingFix) -> Unit)? = null

    val available get() = sensor != null

    fun start() { sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }

    fun stop() { sensors?.unregisterListener(this); latest = null }

    /** Declination depends on where you are on Earth (about 0° to a few degrees in India, up to tens of degrees elsewhere). */
    fun setLocation(lat: Double, lon: Double, altM: Double, timeMs: Long) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), altM.toFloat(), timeMs).declination.toDouble()
        declinationKnown = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { status = accuracy }

    override fun onSensorChanged(e: SensorEvent) {
        val rotation = (context.getSystemService(WindowManager::class.java)?.defaultDisplay ?: return).rotation
        val sol = Orientation.heading(e.values, rotation) ?: return
        posture = sol.posture
        val estimated = if (e.values.size >= 5 && e.values[4] >= 0f) Angles.toDegrees(e.values[4].toDouble()) else null
        val byStatus = when (status) { SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 10.0; SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 20.0; SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 35.0; else -> 90.0 }
        val unc = maxOf(estimated ?: byStatus, 5.0)
        val fix = HeadingFix(Angles.magneticToTrue(sol.magneticDeg, declination), unc, System.currentTimeMillis())
        latest = fix
        onHeading?.invoke(fix)
    }
}
