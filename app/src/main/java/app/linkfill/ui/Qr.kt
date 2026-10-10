package app.linkfill.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.linkfill.core.mesh.Band
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/** QR code as a bitmap. Medium error correction survives a creased or slightly wet printed band. */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
    return bmp
}

@Composable
fun QrImage(text: String, description: String) {
    val bmp = remember(text) { qrBitmap(text).asImageBitmap() }
    Image(bmp, contentDescription = null, filterQuality = FilterQuality.None,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = description })
}

/**
 * Printable wristband: the QR plus large readable text (name, number, meeting point), so it still helps if
 * the person who finds the child has no app at all. Saved to the phone's Pictures for printing.
 */
fun saveBandImage(context: Context, band: Band): Boolean {
    val w = 1800; val h = 600
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Color.WHITE)
    c.drawBitmap(Bitmap.createScaledBitmap(qrBitmap(band.toQrText(), 540), 540, 540, false), 30f, 30f, null)
    val big = Paint().apply { color = Color.BLACK; textSize = 84f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val mid = Paint().apply { color = Color.BLACK; textSize = 54f; isAntiAlias = true }
    val small = Paint().apply { color = Color.DKGRAY; textSize = 40f; isAntiAlias = true }
    c.drawText("${band.name}${if (band.age > 0) ", ${band.age}" else ""}", 620f, 140f, big)
    c.drawText("If lost, call: ${band.phone}", 620f, 250f, mid)
    c.drawText("Meet at: ${band.meetingPoint}", 620f, 340f, mid)
    c.drawText("Volunteers: scan with KHOJ to alert the family", 620f, 450f, small)
    c.drawText("Khoya-Paya: bring to the nearest help desk", 620f, 510f, small)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "KHOJ band ${band.name}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KHOJ")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return context.contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
}

/**
 * Camera QR scanner, fully offline (ZXing on the camera's luminance plane). Calls [onResult] once per new
 * code. Requires the CAMERA permission.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) } }
    val last = remember { arrayOf("") }
    val bound = remember { arrayOfNulls<androidx.camera.core.UseCase>(2) }
    DisposableEffect(Unit) {
        onDispose {
            // Release only our own use cases: the Rewind camera may resume on the same provider afterwards.
            ProcessCameraProvider.getInstance(context).get().unbind(*bound.filterNotNull().toTypedArray())
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).semantics { contentDescription = "Camera viewfinder for scanning a QR code" },
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val plane = image.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                        if (text != last[0]) { last[0] = text; ContextCompat.getMainExecutor(context).execute { onResult(text) } }
                    } catch (_: NotFoundException) {
                    } finally {
                        reader.reset(); image.close()
                    }
                }
                bound[0] = preview; bound[1] = analysis
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
