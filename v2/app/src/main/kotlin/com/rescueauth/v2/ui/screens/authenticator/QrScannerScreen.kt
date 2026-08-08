@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.rescueauth.v2.ui.screens.authenticator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rescueauth.v2.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera QR scanner (Phase 4 P2).
 *
 * - CameraX `Preview` + `PreviewView`, ML Kit `BarcodeScanning` for on-device
 *   QR decode (mature stack; never hand-implement a QR decoder).
 * - Lifecycle-aware: binds to the current [androidx.lifecycle.LifecycleOwner]
 *   and unbinds/releases the camera when the screen leaves composition or the
 *   app backgrounds.
 * - Camera permission is requested here (only when Scan QR is opened), never
 *   at app startup; denied / permanently-denied states have explicit UI.
 * - A last-value + cooldown guard prevents the same QR re-triggering an
 *   import every frame.
 * - Torch toggle (CameraX, no extra permission).
 * - **No camera image is saved, uploaded or logged**; only the raw scanned
 *   string leaves this composable via [onQrDetected].
 */
@Composable
fun QrScannerScreen(
    onQrDetected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            permanentlyDenied = !shouldShowRationale(context)
        }
    }

    // Detect permanent denial after the first denied result.
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) {
            permanentlyDenied = !shouldShowRationale(context)
        }
    }

    when {
        hasCameraPermission -> {
            CameraPreview(
                lifecycleOwner = lifecycleOwner,
                torchOn = torchOn,
                onQrDetected = onQrDetected,
                modifier = modifier.fillMaxSize(),
            )
            CameraOverlay(
                torchOn = torchOn,
                onToggleTorch = { torchOn = !torchOn },
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }
        permanentlyDenied -> {
            PermissionDeniedContent(
                needsSettings = true,
                onDismiss = onDismiss,
                modifier = modifier.fillMaxSize(),
            )
        }
        else -> {
            PermissionDeniedContent(
                needsSettings = false,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onDismiss = onDismiss,
                modifier = modifier.fillMaxSize(),
            )
            // Ask on first entry to Scan QR (never at app startup).
            LaunchedEffect(Unit) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

private fun shouldShowRationale(context: Context): Boolean = try {
    @Suppress("DEPRECATION")
    (context as? android.app.Activity)
        ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ?: true
} catch (e: Exception) {
    true
}

@Composable
private fun CameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    torchOn: Boolean,
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    // Guards against the same QR re-triggering within the cooldown window.
    val lastDetected = remember { mutableStateOf<String?>(null) }
    val lastDetectedAt = remember { mutableStateOf(0L) }

    DisposableEffect(lifecycleOwner, torchOn) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var scanner: BarcodeScanner? = null
        val executor: ExecutorService = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            scanner = BarcodeScanning.getClient()
            analysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
                analyzeFrame(imageProxy, scanner!!, lastDetected, lastDetectedAt, onQrDetected)
            }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                camera.cameraControl.enableTorch(torchOn)
            } catch (e: Exception) {
                // Camera unavailable — no scanning; the user can dismiss.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            scanner?.close()
            executor.shutdown()
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Exception) {
                // Already unbound / provider unavailable.
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@SuppressLint("UnsafeOptInUsageError")
private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    lastDetected: androidx.compose.runtime.MutableState<String?>,
    lastDetectedAt: androidx.compose.runtime.MutableState<Long>,
    onQrDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val value = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
            if (value != null) {
                val now = System.currentTimeMillis()
                val last = lastDetected.value
                if (value != last || now - lastDetectedAt.value > 2_000L) {
                    lastDetected.value = value
                    lastDetectedAt.value = now
                    // Callback runs on the analyzer thread; hop to the main
                    // thread so the caller can update Compose state safely.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onQrDetected(value)
                    }
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

@Composable
private fun CameraOverlay(
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onToggleTorch) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    contentDescription = stringResource(R.string.scan_torch),
                )
            }
            FilledTonalIconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_cancel),
                )
            }
        }
        Text(
            text = stringResource(R.string.scan_qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .padding(12.dp),
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    needsSettings: Boolean,
    onDismiss: () -> Unit,
    onRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (needsSettings) {
                Text(
                    text = stringResource(R.string.scan_permission_denied_settings),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    text = stringResource(R.string.scan_permission_denied),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (needsSettings) {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            } else {
                Button(onClick = onRequest ?: onDismiss) {
                    Text(stringResource(R.string.scan_permission_request))
                }
            }
        }
    }
}
