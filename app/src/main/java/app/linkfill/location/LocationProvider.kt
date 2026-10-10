package app.linkfill.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import app.linkfill.core.protocol.LocationFix
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * The phone's own position from the fused location provider. High-accuracy mode uses GPS, which works
 * without mobile data. Updates arrive on the main thread about once a second while started.
 */
class LocationProvider(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    /** The caller must hold ACCESS_FINE_LOCATION; [onError] is called if the request is refused. */
    @SuppressLint("MissingPermission")
    fun start(onFix: (LocationFix) -> Unit, onError: (String) -> Unit) {
        if (callback != null) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { toFix(it) }?.takeIf { it.isValid() }?.let(onFix)
            }
        }
        callback = cb
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).setMinUpdateIntervalMillis(1_000).build()
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
                .addOnFailureListener { onError("Location unavailable: ${it.message}") }
        } catch (e: SecurityException) {
            callback = null
            onError("Location permission is missing.")
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun toFix(l: Location) = LocationFix(
        latitude = l.latitude,
        longitude = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy else 100f,
        speedMps = if (l.hasSpeed()) l.speed else Float.NaN,
        bearingDeg = if (l.hasBearing()) l.bearing % 360f else Float.NaN,
        fixTimeMs = l.time,
    )
}

/** Device heading (degrees from north) from the rotation-vector sensor, used to point the arrow. */
class Compass(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var onHeading: ((Float) -> Unit)? = null
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    val available get() = sensor != null

    fun start(onHeading: (Float) -> Unit) {
        val s = sensor ?: return
        this.onHeading = onHeading
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() { sensors.unregisterListener(this); onHeading = null }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        onHeading?.invoke(((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
