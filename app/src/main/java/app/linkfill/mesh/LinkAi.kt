package app.linkfill.mesh

import android.content.Context
import android.util.Log
import app.linkfill.core.mesh.LinkFeatures
import com.qualcomm.qti.QnnDelegate
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Link AI on the Snapdragon NPU: predicts, for every Bluetooth neighbour at once, the chance its link drops completely
 * in the next 5 s, so the mesh brings in Nearby / Wi-Fi Direct before the link is gone. Trained on the app's own field
 * logs from two iQOO phones (training/train_link_ai.py, metrics in assets/link_ai_v1.json).
 *
 * Runs through LiteRT with Qualcomm's QNN delegate on the Hexagon HTP (same setup as Nadaka on this phone), falling
 * back to the LiteRT CPU interpreter; if neither opens, the router keeps the identical pure-Kotlin model.
 * The model is tiny: the NPU's value here is keeping this always-on work off the CPU, not speed (latency is measured
 * and shown so the claim can be checked).
 */
class LinkAi private constructor(
    private val interpreter: Interpreter,
    val backend: String,
    private val delegate: AutoCloseable?,
    private val cols: IntArray,
    val meta: JSONObject,
) {
    private val batch = meta.getInt("batch")
    private val input = ByteBuffer.allocateDirect(batch * cols.size * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(batch * 4).order(ByteOrder.nativeOrder())
    /** Mean milliseconds per batch, for the diagnostics line. */
    var meanMs = 0.0
        private set
    var runs = 0L
        private set

    val mediumThreshold get() = meta.getDouble("medium_threshold")
    val highThreshold get() = meta.getDouble("high_threshold")

    /** One probability per row of LinkFeatures.ALL values; null if inference failed (the caller falls back). */
    fun predict(rows: List<DoubleArray>): DoubleArray? = try {
        val out = DoubleArray(rows.size)
        for (start in rows.indices step batch) {
            input.clear()
            for (r in 0 until batch) for (c in cols) input.putFloat(rows.getOrNull(start + r)?.get(c)?.toFloat() ?: 0f) // pad the batch
            input.rewind(); output.clear()
            val t0 = System.nanoTime()
            interpreter.run(input, output)
            val ms = (System.nanoTime() - t0) / 1e6
            meanMs = (meanMs * runs + ms) / (runs + 1); runs++
            if (runs == 50L) Log.i(TAG, "Link AI on $backend: ${"%.3f".format(meanMs)} ms per batch of $batch (mean of 50 runs)")
            output.rewind()
            for (r in 0 until batch) { val p = output.float; if (start + r < rows.size) out[start + r] = p.toDouble() }
        }
        out
    } catch (e: Throwable) { Log.w(TAG, "Link AI inference failed: $e"); null }

    fun close() { interpreter.close(); runCatching { delegate?.close() } }

    companion object {
        private const val TAG = "KHOJ"

        /** HTP first, then CPU; null if the model is missing or nothing can run it. Never throws. */
        fun open(ctx: Context): LinkAi? = try {
            val meta = JSONObject(ctx.assets.open("link_ai_v1.json").bufferedReader().use { it.readText() })
            val names = meta.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
            val cols = names.map { LinkFeatures.ALL.indexOf(it).also { i -> check(i >= 0) { "unknown feature $it" } } }.toIntArray()
            val fd = ctx.assets.openFd("link_ai_v1.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val opened = try {
                check(QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16)) { "no HTP fp16" }
                val qnn = QnnDelegate(QnnDelegate.Options().apply {
                    setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                    setSkelLibraryDir(ctx.applicationInfo.nativeLibraryDir)
                    setCacheDir(ctx.cacheDir.absolutePath)
                    setModelToken("link_ai_v1")
                    // Always-on background work: power saver, not burst.
                    setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_POWER_SAVER)
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
                })
                Triple(Interpreter(model, Interpreter.Options().addDelegate(qnn)), "NPU (Hexagon HTP via QNN)", qnn as AutoCloseable)
            } catch (e: Throwable) {
                Log.w(TAG, "Link AI: HTP unavailable ($e); using CPU")
                Triple(Interpreter(model, Interpreter.Options().setNumThreads(1)), "CPU (LiteRT)", null)
            }
            LinkAi(opened.first, opened.second, opened.third, cols, meta).also { Log.i(TAG, "Link AI on ${it.backend}") }
        } catch (e: Throwable) { Log.w(TAG, "Link AI unavailable: $e"); null }
    }
}
