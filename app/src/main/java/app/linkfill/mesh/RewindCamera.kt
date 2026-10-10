package app.linkfill.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.linkfill.core.protocol.LocationFix
import java.io.ByteArrayOutputStream

/** One remembered view from the pendant: a small JPEG, when, and where. Kept in memory only. */
class RewindFrame(val atMs: Long, val jpeg: ByteArray, val lat: Double?, val lon: Double?)

/**
 * "What did the pendant see?" The rear camera of a phone worn as a pendant faces forward. Every few seconds
 * it keeps a small photo with time and GPS position, for the last [keepMs] only, in memory. After a
 * separation the family scrolls back to the last frames that show the missing person and where that was.
 * Nothing is saved to storage or sent anywhere; stopping the mesh clears it.
 */
class RewindCamera(private val context: Context, private val fix: () -> LocationFix?, private val intervalMs: Long = 3_000, private val keepMs: Long = 180_000) {
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private val frames = ArrayDeque<RewindFrame>()
    private var lastShot = 0L
    private var busy = false
    var running = false
        private set
    var error: String? = null
        private set

    val snapshot: List<RewindFrame> get() = frames.toList().asReversed()

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        owner.registry.currentState = Lifecycle.State.RESUMED
        bind()
    }

    private fun bind() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running || paused) return@addListener
            try {
                val p = future.get()
                provider = p
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                capture = ic
                error = null
            } catch (e: Exception) {
                error = "Camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private var paused = false

    /** Releases the camera (keeping the frames) while something else, like the QR scanner, needs it. */
    fun pause() {
        paused = true
        capture?.let { provider?.unbind(it) }
        capture = null
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (running) bind()
    }

    /** Called from the controller's loop. */
    fun tick(now: Long) {
        val ic = capture ?: return
        while (frames.isNotEmpty() && now - frames.first().atMs > keepMs) frames.removeFirst()
        if (busy || now - lastShot < intervalMs) return
        busy = true
        lastShot = now
        ic.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val raw = ByteArray(buf.remaining()).also { buf.get(it) }
                    small(raw, image.imageInfo.rotationDegrees)?.let { jpeg ->
                        val f = fix()
                        frames.addLast(RewindFrame(System.currentTimeMillis(), jpeg, f?.latitude, f?.longitude))
                    }
                } finally {
                    image.close(); busy = false
                }
            }

            override fun onError(exception: ImageCaptureException) { error = exception.message; busy = false }
        })
    }

    /** Shrinks the capture to about 480 px wide so 60 frames stay a few MB of memory. */
    private fun small(jpeg: ByteArray, rotation: Int): ByteArray? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val rotated = if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    fun stop() {
        running = false
        capture?.let { provider?.unbind(it) }
        capture = null
        owner.registry.currentState = Lifecycle.State.CREATED
        frames.clear()
    }
}
