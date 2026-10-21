package com.wanderwildwood.shoka.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wanderwildwood.shoka.Band
import com.wanderwildwood.shoka.Grey
import com.wanderwildwood.shoka.NumberReader
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** A barcode as read: its text, and whether it was a book's EAN-13 or something else. */
data class Code(val text: String, val format: BarcodeReader.Format)

/**
 * The camera, reading barcodes continuously. A code that stays in view is reported once;
 * it has to leave the frame for [REPEAT_MS] before it counts again, so holding a book up
 * doesn't add it twice.
 *
 * With [readText] it reads the ISBN printed as text instead, in the box [TextBox] describes,
 * and reports only a number whose check digit is right ([onNumber], as an ISBN-13). The OCR
 * runs on one frame at a time, at most every [TEXT_GAP_MS], and the frames that arrive while
 * it works are dropped.
 */
@Composable
fun Scanner(
    modifier: Modifier,
    torch: Boolean,
    formats: Set<BarcodeReader.Format> = BOOKS,
    readText: Boolean = false,
    textBoxHeightPx: Int = 0,
    onNumber: (String) -> Unit = {},
    onCode: (Code) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
    val numberCallback by rememberUpdatedState(onNumber)
    val textMode by rememberUpdatedState(readText)
    val boxHeight by rememberUpdatedState(textBoxHeightPx)
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView, not a SurfaceView: a SurfaceView ignores Compose's clipping and
            // paints the preview over the tabs and buttons around it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(owner) {
        val reader = BarcodeReader().apply {
            options.formats = formats
            options.tryRotate = true
            options.tryHarder = true
            options.eanAddOnSymbol = BarcodeReader.EanAddOnSymbol.IGNORE
        }
        val lastSeen = HashMap<String, Long>()
        val numbers = NumberReader(context)
        var lastText = 0L
        val preview = AtomicReference<Preview?>()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                ).build()
            )
            .build()
        analysis.setAnalyzer(executor) { image ->
            if (textMode) {
                val now = System.currentTimeMillis()
                val isbn = if (now - lastText < TEXT_GAP_MS) {
                    image.close(); null
                } else try {
                    lastText = now
                    numbers.read(band(image, previewView, preview.get(), boxHeight))
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (isbn != null) {
                    val before = lastSeen[isbn]
                    lastSeen[isbn] = now
                    if (before == null || now - before > REPEAT_MS) main.post { numberCallback(isbn) }
                }
                return@setAnalyzer
            }
            // Back to barcodes: the OCR engine's memory is given back until it is wanted again.
            numbers.close()
            val results = try {
                reader.read(image)
            } catch (e: Exception) {
                emptyList()
            } finally {
                image.close()
            }
            val now = System.currentTimeMillis()
            for (r in results) {
                val text = r.text ?: continue
                val before = lastSeen[text]
                lastSeen[text] = now
                if (before == null || now - before > REPEAT_MS) {
                    main.post { callback(Code(text, r.format)) }
                }
            }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val shown = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            preview.set(shown)
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            }.getOrNull()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            camera = null
            // After any read still running on the same thread.
            executor.execute { numbers.close() }
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
    }

    AndroidView(factory = { previewView }, modifier = modifier.clipToBounds())
}

private const val REPEAT_MS = 2500L

/** Whether there is a camera at all: Supernote's tablets have none. */
fun hasCamera(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private const val TEXT_GAP_MS = 350L

/** The box the ISBN line is held in: this share of the camera's width, and a fixed height. */
object TextBox {
    const val WIDTH = 0.86f
    val HEIGHT = 40.dp
}

/**
 * The part of [image] under the text box, with some room around it, upright and greyscale.
 * The box's place is worked out from the preview's size and shape, since the preview is
 * cropped to fill its view and need not be the same shape as the frames analysed.
 */
private fun band(image: ImageProxy, view: PreviewView, preview: Preview?, boxHeightPx: Int): Grey {
    val rotation = image.imageInfo.rotationDegrees
    val sideways = rotation == 90 || rotation == 270
    val frameW = if (sideways) image.height else image.width
    val frameH = if (sideways) image.width else image.height
    val info = preview?.resolutionInfo
    val (previewW, previewH) = info?.let {
        val turned = it.rotationDegrees == 90 || it.rotationDegrees == 270
        val r = it.resolution
        if (turned) r.height to r.width else r.width to r.height
    } ?: (frameW to frameH)
    val viewW = view.width
    val viewH = view.height
    val boxH = if (viewH > 0 && boxHeightPx > 0) boxHeightPx.toFloat() / viewH else 0.3f
    val (w, h) = Band.share(viewW, viewH, TextBox.WIDTH, boxH, previewW, previewH, frameW, frameH)
    // A margin either way, for a hand that isn't quite steady.
    val fracW = (w * 1.1f).coerceAtMost(1f)
    val fracH = (h * 1.6f).coerceAtMost(1f)
    // Small print in a small strip is doubled, which Tesseract reads more surely.
    val scale = if (frameW * fracW < 900) 2 else 1
    val y = image.planes[0]
    return Band.cut(y.buffer, y.rowStride, image.width, image.height, rotation, fracW, fracH, scale)
}

/** What's printed on the back of a book: the ISBN as an EAN-13, and the retail codes beside it. */
val BOOKS = setOf(
    BarcodeReader.Format.EAN_13, BarcodeReader.Format.UPC_A,
    BarcodeReader.Format.EAN_8, BarcodeReader.Format.UPC_E,
)
