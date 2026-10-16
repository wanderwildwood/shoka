package com.wanderwildwood.shoka.ui

import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors

/** A barcode as read: its text, and whether it was a book's EAN-13 or something else. */
data class Code(val text: String, val format: BarcodeReader.Format)

/**
 * The camera, reading barcodes continuously. A code that stays in view is reported once;
 * it has to leave the frame for [REPEAT_MS] before it counts again, so holding a book up
 * doesn't add it twice.
 */
@Composable
fun Scanner(modifier: Modifier, torch: Boolean, onCode: (Code) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
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
            options.formats = setOf(
                BarcodeReader.Format.EAN_13, BarcodeReader.Format.UPC_A,
                BarcodeReader.Format.EAN_8, BarcodeReader.Format.UPC_E,
            )
            options.tryRotate = true
            options.tryHarder = true
            options.eanAddOnSymbol = BarcodeReader.EanAddOnSymbol.IGNORE
        }
        val lastSeen = HashMap<String, Long>()
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
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            provider.unbindAll()
            camera = runCatching {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.getOrNull()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            camera = null
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
    }

    AndroidView(factory = { previewView }, modifier = modifier.clipToBounds())
}

private const val REPEAT_MS = 2500L
