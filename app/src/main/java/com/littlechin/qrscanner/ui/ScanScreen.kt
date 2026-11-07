package com.littlechin.qrscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.littlechin.qrscanner.data.CodeItem
import com.littlechin.qrscanner.data.ScanDatabase
import com.littlechin.qrscanner.data.ScanRecord
import com.littlechin.qrscanner.scanner.BarcodeAnalyzer
import com.littlechin.qrscanner.scanner.formatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/** 連續幾幀偵測到相同數量的條碼後觸發自動擷取，避免晃動時拍到模糊畫面。 */
private const val STABLE_FRAMES = 5

private class CaptureGate {
    private var consecutive = 0
    private var lastCount = 0

    @Volatile
    var captured = false

    @Volatile
    var manualRequest = false

    /** 回傳 true 表示要擷取這一幀。 */
    fun onFrame(count: Int): Boolean {
        if (captured) return false
        if (manualRequest) {
            manualRequest = false
            if (count > 0) {
                captured = true
                return true
            }
            return false
        }
        if (count > 0 && count == lastCount) consecutive++ else consecutive = if (count > 0) 1 else 0
        lastCount = count
        if (consecutive >= STABLE_FRAMES) {
            captured = true
            return true
        }
        return false
    }
}

@Composable
fun ScanScreen(
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraContent(onSaved = onSaved, onBack = onBack)
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要相機權限才能掃描條碼")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("授予權限")
            }
        }
    }
}

@Composable
private fun CameraContent(
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dao = remember { ScanDatabase.get(context).scanDao() }

    var boxes by remember { mutableStateOf<List<android.graphics.Rect>>(emptyList()) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    val gate = remember { CaptureGate() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val analyzer = remember {
        BarcodeAnalyzer(
            onFrame = { barcodes, w, h ->
                boxes = barcodes.mapNotNull { it.boundingBox }
                imageSize = IntSize(w, h)
                gate.onFrame(barcodes.size)
            },
            onCapture = { bitmap, barcodes ->
                saving = true
                scope.launch(Dispatchers.IO) {
                    val id = saveScan(context, dao, bitmap, barcodes)
                    launch(Dispatchers.Main) { onSaved(id) }
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
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
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // 偵測框 overlay：把分析影像座標換算到 PreviewView（FILL_CENTER）座標
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (imageSize.width == 0 || imageSize.height == 0) return@Canvas
            val scale = max(
                size.width / imageSize.width,
                size.height / imageSize.height,
            )
            val dx = (size.width - imageSize.width * scale) / 2f
            val dy = (size.height - imageSize.height * scale) / 2f
            boxes.forEach { r ->
                drawRect(
                    color = Color(0xFF4CAF50),
                    topLeft = Offset(r.left * scale + dx, r.top * scale + dy),
                    size = Size(r.width() * scale, r.height() * scale),
                    style = Stroke(width = 4.dp.toPx()),
                )
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.6f),
        ) {
            Text(
                text = when {
                    saving -> "儲存中…"
                    boxes.isEmpty() -> "將鏡頭對準 QR Code / 條碼"
                    else -> "偵測到 ${boxes.size} 個條碼，保持穩定自動拍攝…"
                },
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        FloatingActionButton(
            onClick = {
                if (boxes.isEmpty()) {
                    Toast.makeText(context, "畫面中沒有偵測到條碼", Toast.LENGTH_SHORT).show()
                } else {
                    gate.manualRequest = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(72.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = "拍攝")
        }
    }
}

private suspend fun saveScan(
    context: android.content.Context,
    dao: com.littlechin.qrscanner.data.ScanDao,
    bitmap: Bitmap,
    barcodes: List<Barcode>,
): Long {
    val dir = File(context.filesDir, "scans").apply { mkdirs() }
    val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    val codes = barcodes.map { b ->
        val box = b.boundingBox
        CodeItem(
            value = b.rawValue ?: b.displayValue ?: "",
            format = formatName(b.format),
            left = box?.left ?: 0,
            top = box?.top ?: 0,
            right = box?.right ?: 0,
            bottom = box?.bottom ?: 0,
        )
    }
    return dao.insert(
        ScanRecord(
            timestamp = System.currentTimeMillis(),
            imagePath = file.absolutePath,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            codesJson = CodeItem.listToJson(codes),
        )
    )
}
