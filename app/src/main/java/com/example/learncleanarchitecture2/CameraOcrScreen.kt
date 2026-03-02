package com.example.learncleanarchitecture2

import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private const val MIN_TEXT_LINES = 4

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraOcrScreen(
    onTextRecognized: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val isCaptured = remember { AtomicBoolean(false) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    var bestText by remember { mutableStateOf("") }
    var isFullyInFrame by remember { mutableStateOf(false) }
    var captureCountdown = 3
    var statusText by remember { mutableStateOf("Çeki çərçivəyə tam daxil edin") }

    // Çek çərçivəyə girəndə 3 saniiyə geri sayım başlayır.
    // Çek çıxarılsa LaunchedEffect ləğv olur və sayım sıfırlanır.
    LaunchedEffect(isFullyInFrame) {
        if (!isFullyInFrame) return@LaunchedEffect
        delay(1000L)
        delay(1000L)
        delay(1000L)
        if (isCaptured.compareAndSet(false, true)) {
            onTextRecognized(bestText)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef.value?.unbindAll()
            recognizer.close()
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef.value = cameraProvider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1920, 1080))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        if (isCaptured.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        // Bitmap-ə çevir və preprocessing tətbiq et
                        val rawBitmap = imageProxy.toBitmap()
                        val enhanced = enhanceBitmapForOcr(rawBitmap)

                        val image = InputImage.fromBitmap(
                            enhanced,
                            imageProxy.imageInfo.rotationDegrees
                        )

                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val detected = visionText.text.trim()

                                if (detected.isEmpty()) {
                                    isFullyInFrame = false
                                    bestText = ""
                                    statusText = "Çeki çərçivəyə tam daxil edin"
                                    return@addOnSuccessListener
                                }

                                val lineCount = visionText.textBlocks.sumOf { it.lines.size }
                                val fullyInFrame = isReceiptFullyInFrame(visionText, imageProxy)

                                // bestText-i hər zaman güncəllə (ən tam oxunmuş mətni saxla)
                                if (detected.length > bestText.length) bestText = detected

                                isFullyInFrame = fullyInFrame

                                statusText = if (fullyInFrame) {
                                    "Çek tapıldı, gözləyin..."
                                } else {
                                    "Çekin hamısını çərçivəyə daxil edin ($lineCount/$MIN_TEXT_LINES sətir)"
                                }
                            }
                            .addOnCompleteListener {
                                enhanced.recycle()
                                imageProxy.close()
                            }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: kənarlaşdırma + künclər + scan xətti
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padH = size.width * 0.05f
            val rectTop = size.height * 0.05f
            val rectBottom = size.height * 0.92f
            val rectW = size.width - padH * 2
            val rectH = rectBottom - rectTop
            val cornerLen = 50f
            val cornerThickness = 6f

            drawRect(Color(0xBB000000), Offset(0f, 0f), ComposeSize(size.width, rectTop))
            drawRect(Color(0xBB000000), Offset(0f, rectBottom), ComposeSize(size.width, size.height - rectBottom))
            drawRect(Color(0xBB000000), Offset(0f, rectTop), ComposeSize(padH, rectH))
            drawRect(Color(0xBB000000), Offset(size.width - padH, rectTop), ComposeSize(padH, rectH))

            if (isFullyInFrame) {
                val lineY = rectTop + rectH * scanProgress
                drawLine(
                    color = Color(0xFF00E676).copy(alpha = 0.85f),
                    start = Offset(padH, lineY),
                    end = Offset(padH + rectW, lineY),
                    strokeWidth = 4f
                )
            }

            val cornerColor = if (isFullyInFrame) Color(0xFF00E676) else Color.White

            // Sol-yuxarı
            drawLine(cornerColor, Offset(padH, rectTop), Offset(padH + cornerLen, rectTop), cornerThickness, StrokeCap.Round)
            drawLine(cornerColor, Offset(padH, rectTop), Offset(padH, rectTop + cornerLen), cornerThickness, StrokeCap.Round)
            // Sağ-yuxarı
            drawLine(cornerColor, Offset(padH + rectW, rectTop), Offset(padH + rectW - cornerLen, rectTop), cornerThickness, StrokeCap.Round)
            drawLine(cornerColor, Offset(padH + rectW, rectTop), Offset(padH + rectW, rectTop + cornerLen), cornerThickness, StrokeCap.Round)
            // Sol-aşağı
            drawLine(cornerColor, Offset(padH, rectBottom), Offset(padH + cornerLen, rectBottom), cornerThickness, StrokeCap.Round)
            drawLine(cornerColor, Offset(padH, rectBottom), Offset(padH, rectBottom - cornerLen), cornerThickness, StrokeCap.Round)
            // Sağ-aşağı
            drawLine(cornerColor, Offset(padH + rectW, rectBottom), Offset(padH + rectW - cornerLen, rectBottom), cornerThickness, StrokeCap.Round)
            drawLine(cornerColor, Offset(padH + rectW, rectBottom), Offset(padH + rectW, rectBottom - cornerLen), cornerThickness, StrokeCap.Round)
        }

        // Status mətn + Geri düyməsi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = statusText,
                    color = if (isFullyInFrame) Color(0xFF00E676) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(text = "Geri")
            }
        }
    }
}

private fun isReceiptFullyInFrame(
    visionText: com.google.mlkit.vision.text.Text,
    imageProxy: ImageProxy
): Boolean {
    val allLines = visionText.textBlocks.flatMap { it.lines }
    if (allLines.size < MIN_TEXT_LINES) return false

    val boxes = allLines.mapNotNull { it.boundingBox }
    if (boxes.isEmpty()) return false

    val spanX = (boxes.maxOf { it.right } - boxes.minOf { it.left }).toFloat()
    val spanY = (boxes.maxOf { it.bottom } - boxes.minOf { it.top }).toFloat()

    val rotation = imageProxy.imageInfo.rotationDegrees
    val displayW = if (rotation == 90 || rotation == 270) imageProxy.height.toFloat() else imageProxy.width.toFloat()
    val displayH = if (rotation == 90 || rotation == 270) imageProxy.width.toFloat() else imageProxy.height.toFloat()

    return spanX >= displayW * 0.30f && spanY >= displayH * 0.40f
}
