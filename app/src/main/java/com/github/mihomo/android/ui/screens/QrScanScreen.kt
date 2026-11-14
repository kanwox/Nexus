package com.github.mihomo.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoButton
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonResponsiveContent
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.findActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qrDecodeHints: Map<DecodeHintType, Any> =
    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

/**
 * Per-frame QR decode worker: feeds the camera Y plane into zxing and reports the first
 * valid subscription URL. Duplicate callbacks for the same session are suppressed by
 * [delivered]; frames that contain no code (or no URL) simply keep the scanner alive.
 */
private class QrFrameAnalyzer(
    private val hints: Map<DecodeHintType, Any>,
    private val onQrDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply { setHints(hints) }
    private val delivered = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (delivered.get() || image.format != ImageFormat.YUV_420_888) return
            val text = decodeFrame(image) ?: return
            if ((text.startsWith("http://") || text.startsWith("https://")) &&
                delivered.compareAndSet(false, true)
            ) {
                onQrDetected(text)
            }
        } finally {
            image.close()
        }
    }

    /** Sensor frames arrive landscape: rotate the luminance source upright before scanning. */
    private fun decodeFrame(image: ImageProxy): String? {
        return try {
            val source = buildLuminanceSource(image)
            // LuminanceSource rotate helpers are protected in zxing; rotate the raw luminance
            // buffer manually so sensor-orientation frames are scanned upright.
            val upright = when (image.imageInfo.rotationDegrees) {
                90 -> rotateLuminance(source, clockwise = false)
                180 -> rotateLuminance(rotateLuminance(source, clockwise = false), clockwise = false)
                270 -> rotateLuminance(source, clockwise = true)
                else -> source
            }
            reader.decode(BinaryBitmap(HybridBinarizer(upright)), hints).text
        } catch (ignored: Exception) {
            // NotFoundException and stride/format edge cases: no code in this frame.
            null
        } finally {
            reader.reset()
        }
    }

    private fun buildLuminanceSource(image: ImageProxy): PlanarYUVLuminanceSource {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val luminance = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(luminance)
        } else {
            var offset = 0
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(luminance, offset, width)
                offset += width
            }
        }
        return PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    }

    /** Rotates a full-frame source into a new (height x width) luminance source. */
    private fun rotateLuminance(
        source: PlanarYUVLuminanceSource,
        clockwise: Boolean
    ): PlanarYUVLuminanceSource {
        val width = source.width
        val height = source.height
        val input = source.matrix
        val output = ByteArray(width * height)
        var index = 0
        if (clockwise) {
            for (x in 0 until width) {
                for (y in height - 1 downTo 0) {
                    output[index++] = input[y * width + x]
                }
            }
        } else {
            for (x in width - 1 downTo 0) {
                for (y in 0 until height) {
                    output[index++] = input[y * width + x]
                }
            }
        }
        return PlanarYUVLuminanceSource(output, height, width, 0, 0, height, width, false)
    }
}

/**
 * QR subscription import: live camera preview plus a zxing analyzer. A detected
 * http/https URL is reported once through [onResult]; toast and navigation stay with the
 * caller.
 */
private data class CameraPermissionState(
    val hasPermission: Boolean,
    val permanentlyDenied: Boolean,
    val requestPermission: () -> Unit,
    val openSettings: () -> Unit
)

@Composable
private fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            permanentlyDenied = false
        } else {
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                hasCameraPermission = granted
                if (granted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    return CameraPermissionState(
        hasPermission = hasCameraPermission,
        permanentlyDenied = permanentlyDenied,
        requestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        openSettings = {
            if (!openAppDetailsSettings(context)) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    )
}

@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    val lang = LocalAppLanguage.current
    val permissionState = rememberCameraPermissionState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        CupertinoNavBar(
            title = AppStrings.get("qr_scan_title", lang),
            showBackButton = true,
            onBack = onBack
        )

        LoonResponsiveContent {
            if (permissionState.hasPermission) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = AppStrings.get("qr_scan_hint", lang),
                        color = LoonTextSecondary,
                        style = LoonTextStyles.callout(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    QrCameraContent(
                        modifier = Modifier.weight(1f),
                        onResult = onResult
                    )
                }
            } else {
                QrPermissionDeniedCard(
                    lang = lang,
                    permanentlyDenied = permissionState.permanentlyDenied,
                    onRequestPermission = permissionState.requestPermission,
                    onOpenSettings = permissionState.openSettings
                )
            }
        }
    }
}

@Composable
private fun QrPermissionDeniedCard(
    lang: String,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CupertinoGroupedSection {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.get("qr_permission_denied", lang),
                    color = LoonTextPrimary,
                    style = LoonTextStyles.body(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                CupertinoButton(
                    text = AppStrings.get(
                        if (permanentlyDenied) "qr_open_settings" else "qr_grant_access",
                        lang
                    ),
                    onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission
                )
            }
        }
    }
}

private fun openAppDetailsSettings(context: Context): Boolean = runCatching {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)

@Composable
private fun QrCameraContent(
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val latestOnResult by rememberUpdatedState(onResult)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    val analyzer = remember {
        QrFrameAnalyzer(qrDecodeHints) { url ->
            if (!disposed.get()) {
                mainHandler.post {
                    if (!disposed.get()) {
                        latestOnResult(url)
                    }
                }
            }
        }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    runCatching {
                        val provider = providerFuture.get()
                        if (disposed.get()) {
                            provider.unbindAll()
                            return@runCatching
                        }
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also { target ->
                            target.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { target ->
                                target.setAnalyzer(analysisExecutor, analyzer)
                            }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )
            previewView
        },
        modifier = modifier.fillMaxWidth()
    )

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { cameraProvider?.unbindAll() }
            cameraProvider = null
            analysisExecutor.shutdown()
        }
    }
}
