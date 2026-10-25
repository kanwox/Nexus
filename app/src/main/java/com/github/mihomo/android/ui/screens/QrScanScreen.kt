package com.github.mihomo.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.mihomo.android.ui.components.CupertinoButton
import com.github.mihomo.android.ui.components.CupertinoGroupedSection
import com.github.mihomo.android.ui.components.CupertinoNavBar
import com.github.mihomo.android.ui.theme.AppStrings
import com.github.mihomo.android.ui.theme.LocalAppLanguage
import com.github.mihomo.android.ui.theme.LoonBg
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qrDecodeHints: Map<DecodeHintType, Any> =
    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

private fun qrLocaleText(lang: String, zh: String, en: String): String {
    val effective = if (lang == "SYSTEM") Locale.getDefault().language else lang
    return if (effective.startsWith("zh", ignoreCase = true)) zh else en
}

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
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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

        if (hasCameraPermission) {
            Text(
                text = AppStrings.get("qr_scan_hint", lang),
                color = LoonTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
            QrCameraContent(
                modifier = Modifier.weight(1f),
                onResult = onResult
            )
        } else {
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
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        CupertinoButton(
                            text = qrLocaleText(lang, "去授权", "Grant Access"),
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }
        }
    }
}

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
    val analyzer = remember {
        QrFrameAnalyzer(qrDecodeHints) { url ->
            // Analysis runs on its own executor: hop back to the main thread so the
            // callback can safely touch Compose state.
            mainHandler.post { latestOnResult(url) }
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
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }
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
            runCatching { cameraProvider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }
}
