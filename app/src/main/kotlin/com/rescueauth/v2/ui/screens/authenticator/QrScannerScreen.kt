@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.rescueauth.v2.ui.screens.authenticator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconHeader
import com.rescueauth.v2.ui.components.RescueAuthMetaPill
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera QR scanner (Phase 4 P2).
 */
@Composable
fun QrScannerScreen(
    onQrDetected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    errorMessage: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onDismiss)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            permanentlyDenied = !shouldShowRationale(context)
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) {
            permanentlyDenied = !shouldShowRationale(context)
        }
    }

    when {
        hasCameraPermission -> {
            Box(modifier = modifier.fillMaxSize()) {
                CameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    torchOn = torchOn,
                    onQrDetected = onQrDetected,
                    modifier = Modifier.fillMaxSize(),
                )
                CameraOverlay(
                    torchOn = torchOn,
                    onToggleTorch = { torchOn = !torchOn },
                    onDismiss = onDismiss,
                    statusMessage = statusMessage,
                    errorMessage = errorMessage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
} catch (_: Exception) {
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

    val lastDetected = remember { mutableStateOf<String?>(null) }
    val lastDetectedAt = remember { mutableStateOf(0L) }

    DisposableEffect(lifecycleOwner, torchOn) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var scanner: BarcodeScanner? = null
        val executor: ExecutorService = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (_: Exception) {
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
            } catch (_: Exception) {
                // Camera unavailable — user can dismiss.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            scanner?.close()
            executor.shutdown()
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (_: Exception) {
                // Already unbound.
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
    statusMessage: String?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (!errorMessage.isNullOrBlank()) {
                RescueAuthCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentPadding = Spacing.sm,
                    modifier = Modifier.widthIn(max = 420.dp),
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            RescueAuthMetaPill(
                text = statusMessage ?: stringResource(R.string.scan_qr_hint),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun PermissionDeniedContent(
    needsSettings: Boolean,
    onDismiss: () -> Unit,
    onRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        RescueAuthCard(
            contentPadding = CardTokens.contentPadding,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing)) {
                RescueAuthIconHeader(
                    icon = Icons.Filled.CameraAlt,
                    title = stringResource(R.string.add_totp_mode_scan),
                    subtitle = stringResource(
                        if (needsSettings) R.string.scan_permission_denied_settings else R.string.scan_permission_denied,
                    ),
                    modifier = Modifier.testTag("camera_permission_header"),
                )
                if (needsSettings) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.scan_open_system_settings))
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                } else {
                    Button(onClick = onRequest ?: onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scan_permission_request))
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}
