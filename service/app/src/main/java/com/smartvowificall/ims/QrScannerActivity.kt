package com.smartvowificall.ims

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.smartvowificall.ims.clash.SubscriptionQrParser
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CLASH_ID = "clash_id"
        const val EXTRA_SCANNED_URL = "scanned_url"
        const val EXTRA_RAW_TEXT = "raw_text"
    }

    private lateinit var cameraExecutor: ExecutorService
    private val delivered = AtomicBoolean(false)
    private var activeCamera by mutableStateOf<Camera?>(null)
    private var torchEnabled by mutableStateOf(false)
    private var statusHint by mutableStateOf("将 Clash 订阅二维码放入取景框内")
    private var errorBanner by mutableStateOf("")

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                decodeFromGalleryUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        val clashId = intent.getIntExtra(EXTRA_CLASH_ID, 0)

        setContent {
            QrScannerScreen(
                clashId = clashId,
                torchEnabled = torchEnabled,
                statusHint = statusHint,
                errorBanner = errorBanner,
                onBindPreview = { previewView -> bindCamera(previewView) },
                onToggleTorch = { toggleTorch() },
                onPickImage = { pickImageLauncher.launch("image/*") },
                onClose = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val reader = createQrReader()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image, reader)
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                activeCamera = cam
            } catch (e: Exception) {
                errorBanner = "无法打开摄像头：${e.javaClass.simpleName}，可点击下方「相册选图」"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = activeCamera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            errorBanner = "当前设备摄像头不支持闪光灯"
            return
        }
        val next = !torchEnabled
        cam.cameraControl.enableTorch(next)
        torchEnabled = next
    }

    private fun analyzeFrame(image: ImageProxy, reader: MultiFormatReader) {
        try {
            if (delivered.get()) return
            val plane = image.planes.firstOrNull() ?: return
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            // Copy Y plane into contiguous width*height buffer when rowStride has padding.
            val yData = if (rowStride == width) {
                bytes
            } else {
                val packed = ByteArray(width * height)
                for (row in 0 until height) {
                    val srcOffset = row * rowStride
                    val len = minOf(width, bytes.size - srcOffset)
                    if (len > 0) System.arraycopy(bytes, srcOffset, packed, row * width, len)
                }
                packed
            }

            val source = PlanarYUVLuminanceSource(
                yData, width, height, 0, 0, width, height, false
            )
            val decoded = decodeLuminance(reader, source)
            if (decoded != null) {
                handleDecodedText(decoded)
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun decodeFromGalleryUri(uri: Uri) {
        errorBanner = ""
        statusHint = "正在识别相册中的二维码…"
        cameraExecutor.execute {
            try {
                val bitmap = decodeSampledBitmap(uri, 1600)
                if (bitmap == null) {
                    runOnUiThread {
                        errorBanner = "无法读取所选图片"
                        statusHint = "将 Clash 订阅二维码放入取景框内"
                    }
                    return@execute
                }
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                val source = RGBLuminanceSource(w, h, pixels)
                val decoded = decodeLuminance(createQrReader(), source)
                if (decoded != null) {
                    handleDecodedText(decoded)
                } else {
                    runOnUiThread {
                        errorBanner = "未在图片中识别到二维码"
                        statusHint = "将 Clash 订阅二维码放入取景框内"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    errorBanner = "识别图片失败：${e.javaClass.simpleName}"
                    statusHint = "将 Clash 订阅二维码放入取景框内"
                }
            }
        }
    }

    private fun decodeSampledBitmap(uri: Uri, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val realOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, realOpts)
        }
    }

    private fun handleDecodedText(raw: String) {
        val url = SubscriptionQrParser.extractSubscriptionUrl(raw)
        if (url == null) {
            runOnUiThread {
                errorBanner = "二维码中未包含有效的 HTTP/HTTPS 或 Clash 订阅链接"
            }
            return
        }
        if (delivered.compareAndSet(false, true)) {
            runOnUiThread {
                val data = Intent()
                    .putExtra(EXTRA_SCANNED_URL, url)
                    .putExtra(EXTRA_RAW_TEXT, raw)
                setResult(RESULT_OK, data)
                finish()
            }
        }
    }

    private fun createQrReader(): MultiFormatReader {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.ALSO_INVERTED] = true
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        return MultiFormatReader().apply { setHints(hints) }
    }

    private fun decodeLuminance(
        reader: MultiFormatReader,
        source: com.google.zxing.LuminanceSource
    ): String? {
        try {
            reader.reset()
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
        }
        try {
            reader.reset()
            return reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
        } catch (_: Exception) {
        }
        return null
    }
}

@Composable
private fun QrScannerScreen(
    clashId: Int,
    torchEnabled: Boolean,
    statusHint: String,
    errorBanner: String,
    onBindPreview: (PreviewView) -> Unit,
    onToggleTorch: () -> Unit,
    onPickImage: () -> Unit,
    onClose: () -> Unit
) {
    val accent = if (clashId == 0) Color(0xFF0A84FF) else Color(0xFFBF5AF2)
    val tunName = if (clashId == 0) "wfctun0" else "wfctun1"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    post { onBindPreview(this) }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Dimmed mask with crisp translucent rounded viewfinder cutout + corner brackets
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val boxSize = minOf(size.width, size.height) * 0.66f
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f - 40f
            val radius = 24.dp.toPx()

            drawRect(color = Color.Black.copy(alpha = 0.56f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(radius, radius),
                blendMode = BlendMode.Clear
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Corner accents
            val arm = 28.dp.toPx()
            val stroke = 4.dp.toPx()
            val right = left + boxSize
            val bottom = top + boxSize
            // Top-left
            drawLine(accent, Offset(left, top + arm), Offset(left, top + radius * 0.4f), stroke, StrokeCap.Round)
            drawLine(accent, Offset(left + radius * 0.4f, top), Offset(left + arm, top), stroke, StrokeCap.Round)
            // Top-right
            drawLine(accent, Offset(right - arm, top), Offset(right - radius * 0.4f, top), stroke, StrokeCap.Round)
            drawLine(accent, Offset(right, top + radius * 0.4f), Offset(right, top + arm), stroke, StrokeCap.Round)
            // Bottom-left
            drawLine(accent, Offset(left, bottom - arm), Offset(left, bottom - radius * 0.4f), stroke, StrokeCap.Round)
            drawLine(accent, Offset(left + radius * 0.4f, bottom), Offset(left + arm, bottom), stroke, StrokeCap.Round)
            // Bottom-right
            drawLine(accent, Offset(right - arm, bottom), Offset(right - radius * 0.4f, bottom), stroke, StrokeCap.Round)
            drawLine(accent, Offset(right, bottom - radius * 0.4f), Offset(right, bottom - arm), stroke, StrokeCap.Round)
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "扫码添加订阅",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "内核 ${clashId + 1} · $tunName",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Bottom hint & action buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (errorBanner.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFF453A).copy(alpha = 0.22f))
                        .border(0.75.dp, Color(0xFFFF453A).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = errorBanner,
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = statusHint,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "支持 HTTP/HTTPS 订阅、clash:// 与 sub:// 二维码",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (torchEnabled) accent.copy(alpha = 0.85f)
                            else Color.White.copy(alpha = 0.16f)
                        )
                        .border(0.75.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onToggleTorch)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (torchEnabled) "关闭手电筒" else "打开手电筒",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(0.75.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .clickable(onClick = onPickImage)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "相册选图",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
