package com.pinna.app.qr

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
@Composable
internal fun CameraQrScanner(
    onValidPayload: (String) -> Unit,
    modifier: Modifier = Modifier,
    onScanError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnValidPayload by rememberUpdatedState(onValidPayload)
    val currentOnScanError by rememberUpdatedState(onScanError)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(context, lifecycleOwner, previewView) {
        var disposed = false
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val resultHandler = QrScanResultHandler(
            onValidPayload = { payload ->
                cameraProvider?.unbindAll()
                currentOnValidPayload(payload)
            },
            onScanError = { message -> currentOnScanError(message) },
        )
        val analyzer = MlKitQrImageAnalyzer(
            scanner = scanner,
            resultHandler = resultHandler,
            callbackExecutor = mainExecutor,
        )
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val bindCamera = Runnable {
            if (disposed) return@Runnable
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { cameraPreview ->
                    cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                cameraProvider = provider
            } catch (_: Exception) {
                currentOnScanError("Unable to start camera scanner.")
            }
        }
        cameraProviderFuture.addListener(bindCamera, mainExecutor)

        onDispose {
            disposed = true
            cameraProvider?.unbindAll()
            cameraProvider = null
            scanner.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

internal class QrScanResultHandler(
    private val onValidPayload: (String) -> Unit,
    private val onScanError: (String) -> Unit = {},
    private val mapResult: (String?) -> QrScanResult = { rawValue -> QrScanResultMapper.map(rawValue) },
) {
    var isComplete: Boolean = false
        private set

    fun accept(rawValue: String?) {
        if (isComplete) return
        when (val result = mapResult(rawValue)) {
            is QrScanResult.Valid -> {
                isComplete = true
                onValidPayload(result.rawPayload)
            }
            is QrScanResult.Error -> onScanError(result.message)
            QrScanResult.Ignored -> Unit
        }
    }
}

private class MlKitQrImageAnalyzer(
    private val scanner: BarcodeScanner,
    private val resultHandler: QrScanResultHandler,
    private val callbackExecutor: Executor,
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        if (resultHandler.isComplete) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        try {
            scanner.process(inputImage)
                .addOnSuccessListener(callbackExecutor) { barcodes ->
                    for (barcode in barcodes) {
                        if (resultHandler.isComplete) break
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            resultHandler.accept(barcode.rawValue)
                        }
                    }
                }
                .addOnCompleteListener(callbackExecutor) {
                    imageProxy.close()
                }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }
}
