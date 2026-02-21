package com.faceanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivity.MainContent() {
    var hasCameraPermission by remember { mutableStateOf(false) }
    var faceResult by remember { mutableStateOf<FaceAnalysisResult?>(null) }
    var enableMesh by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val faceAnalyzer = remember(enableMesh) {
        FaceAnalyzer(context, onFaceDetected = { result ->
            faceResult = result
        }, enableMesh = enableMesh)
    }

    DisposableEffect(Unit) {
        onDispose {
            faceAnalyzer.close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E1B4B)
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(enableMesh = enableMesh, onMeshToggle = { enableMesh = it })
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (hasCameraPermission) {
                    CameraPreview(
                        faceAnalyzer = faceAnalyzer,
                        faceResult = faceResult,
                        enableMesh = enableMesh
                    )
                } else {
                    PermissionDenied()
                }
            }
            
            faceResult?.let { result ->
                FaceAnalysisPanel(result = result)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(enableMesh: Boolean, onMeshToggle: (Boolean) -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "Face Analyzer",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mesh",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Switch(
                    checked = enableMesh,
                    onCheckedChange = onMeshToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF6366F1),
                        checkedTrackColor = Color(0xFF6366F1).copy(alpha = 0.5f)
                    )
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
    )
}

@Composable
fun CameraPreview(
    faceAnalyzer: FaceAnalyzer,
    faceResult: FaceAnalysisResult?,
    enableMesh: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )

    LaunchedEffect(enableMesh) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = cameraProviderFuture.get()
        
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(Executors.newSingleThreadExecutor(), faceAnalyzer)
            }
        
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    faceResult?.let { result ->
        FaceOverlay(
            result = result,
            previewWidth = previewView.width.toFloat(),
            previewHeight = previewView.height.toFloat(),
            enableMesh = enableMesh
        )
    }
}

@Composable
fun FaceOverlay(
    result: FaceAnalysisResult,
    previewWidth: Float,
    previewHeight: Float,
    enableMesh: Boolean
) {
    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val scaleX = size.width / previewWidth.coerceAtLeast(1f)
        val scaleY = size.height / previewHeight.coerceAtLeast(1f)
        val scale = min(scaleX, scaleY)
        
        val rect = result.faceRect
        val left = size.width - (rect.right * scale)
        val top = rect.top * scale
        val right = size.width - (rect.left * scale)
        val bottom = rect.bottom * scale
        
        val boxWidth = right - left
        val boxHeight = bottom - top
        
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = ComposeSize(boxWidth, boxHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )
        
        drawCircle(
            color = Color(0xFF6366F1),
            radius = 8.dp.toPx(),
            center = Offset(left, top)
        )
        drawCircle(
            color = Color(0xFF6366F1),
            radius = 8.dp.toPx(),
            center = Offset(right, top)
        )
        drawCircle(
            color = Color(0xFFEC4899),
            radius = 8.dp.toPx(),
            center = Offset(left, bottom)
        )
        drawCircle(
            color = Color(0xFFEC4899),
            radius = 8.dp.toPx(),
            center = Offset(right, bottom)
        )
        
        if (enableMesh && result.meshPoints != null) {
            result.meshPoints.forEach { point ->
                val mirroredX = size.width - (point.x * scale)
                val y = point.y * scale
                drawCircle(
                    color = Color(0xFF14B8A6).copy(alpha = 0.5f),
                    radius = 2.dp.toPx(),
                    center = Offset(mirroredX, y)
                )
            }
        }
        
        result.landmarks?.forEach { point ->
            val mirroredX = size.width - (point.x * scale)
            val y = point.y * scale
            drawCircle(
                color = Color(0xFFEC4899),
                radius = 5.dp.toPx(),
                center = Offset(mirroredX, y)
            )
        }
    }
}

@Composable
fun FaceAnalysisPanel(result: FaceAnalysisResult) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF0F172A).copy(alpha = 0.95f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmotionCard(
                emotion = result.emotion,
                confidence = result.emotionConfidence,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            PerfectionCard(
                perfection = result.facePerfection,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        QualityMetricsRow(result = result)
    }
}

@Composable
fun EmotionCard(
    emotion: Emotion,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = emotion.emoji,
                fontSize = 36.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
            
            Column {
                Text(
                    text = emotion.displayName,
                    color = Color(emotion.color),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${(confidence * 100).toInt()}% confident",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun PerfectionCard(
    perfection: Float,
    modifier: Modifier = Modifier
) {
    val color = when {
        perfection >= 0.8f -> Color(0xFF22C55E)
        perfection >= 0.6f -> Color(0xFF14B8A6)
        perfection >= 0.4f -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${(perfection * 100).toInt()}%",
                color = color,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Face Score",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinearProgressIndicator(
                progress = perfection,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = color,
                trackColor = Color(0xFF334155)
            )
        }
    }
}

@Composable
fun QualityMetricsRow(result: FaceAnalysisResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            title = "Quality",
            value = result.faceQuality.qualityLevel.displayName,
            progress = result.faceQuality.score,
            color = Color(result.faceQuality.qualityLevel.color),
            modifier = Modifier.weight(1f)
        )
        
        MetricCard(
            title = "Symmetry",
            value = "${(result.symmetryScore * 100).toInt()}%",
            progress = result.symmetryScore,
            color = Color(0xFF6366F1),
            modifier = Modifier.weight(1f)
        )
        
        MetricCard(
            title = "Skin",
            value = result.skinCondition.condition,
            progress = result.skinCondition.overallScore,
            color = Color(0xFFEC4899),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = color,
                trackColor = Color(0xFF334155)
            )
        }
    }
}

@Composable
fun PermissionDenied() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please grant camera access to use face analysis",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp
            )
        }
    }
}
