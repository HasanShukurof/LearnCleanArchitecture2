package com.example.learncleanarchitecture2

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    var statusText by remember { mutableStateOf("Çeki kameraya yönəldin...") }
    var stableCount by remember { mutableIntStateOf(0) }
    var lastText by remember { mutableStateOf("") }

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
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        if (isCaptured.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )

                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val detected = visionText.text.trim()
                                if (detected.isNotEmpty()) {
                                    if (detected == lastText) {
                                        stableCount++
                                        statusText = "Fokuslanır... ($stableCount/3)"
                                    } else {
                                        lastText = detected
                                        stableCount = 1
                                        statusText = "Mətn axtarılır..."
                                    }

                                    if (stableCount >= 3 && isCaptured.compareAndSet(false, true)) {
                                        statusText = "Mətn tapıldı!"
                                        onTextRecognized(detected)
                                    }
                                } else {
                                    stableCount = 0
                                    lastText = ""
                                    statusText = "Çeki kameraya yönəldin..."
                                }
                            }
                            .addOnCompleteListener {
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

        // Kənar qarartma + scan çərçivəsi + hərəkətli xətt
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padH = size.width * 0.06f
            val rectTop = size.height * 0.18f
            val rectBottom = size.height * 0.82f
            val rectW = size.width - padH * 2
            val rectH = rectBottom - rectTop

            // Yuxarı qarartma
            drawRect(
                color = Color(0xAA000000),
                topLeft = Offset(0f, 0f),
                size = ComposeSize(size.width, rectTop)
            )
            // Aşağı qarartma
            drawRect(
                color = Color(0xAA000000),
                topLeft = Offset(0f, rectBottom),
                size = ComposeSize(size.width, size.height - rectBottom)
            )
            // Sol qarartma
            drawRect(
                color = Color(0xAA000000),
                topLeft = Offset(0f, rectTop),
                size = ComposeSize(padH, rectH)
            )
            // Sağ qarartma
            drawRect(
                color = Color(0xAA000000),
                topLeft = Offset(size.width - padH, rectTop),
                size = ComposeSize(padH, rectH)
            )

            // Scan çərçivəsi
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(padH, rectTop),
                size = ComposeSize(rectW, rectH),
                style = Stroke(width = 3f)
            )

            // Yuxarıdan aşağıya hərəkət edən scan xətti
            val lineY = rectTop + rectH * scanProgress
            drawLine(
                color = Color(0xFF00E676).copy(alpha = 0.9f),
                start = Offset(padH, lineY),
                end = Offset(padH + rectW, lineY),
                strokeWidth = 4f
            )
        }

        // Status mətn + Geri düyməsi
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(text = "Geri")
            }
        }
    }
}
