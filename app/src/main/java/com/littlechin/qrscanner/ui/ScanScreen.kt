package com.littlechin.qrscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.littlechin.qrscanner.data.CodeItem
import com.littlechin.qrscanner.data.ScanDatabase
import com.littlechin.qrscanner.data.ScanRecord
import com.littlechin.qrscanner.scanner.BarcodeAnalyzer
import com.littlechin.qrscanner.scanner.DetectedCode
import com.littlechin.qrscanner.scanner.formatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** 連續幾幀偵測到相同數量的條碼後觸發自動擷取，避免晃動時拍到模糊畫面。 */
private const val STABLE_FRAMES = 5

/** 自動變焦最多拉到幾倍；再往上多半是數位變焦，畫面晃動反而更難解。手動雙指縮放不受此限。 */
private const val MAX_AUTO_ZOOM = 4f

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

/**
 * [continuous] 為 true 時是連續掃描：相機一直開著，每掃到一個沒看過的條碼就截圖存檔，
 * 直到按停止（[onSaved] 帶其中一筆紀錄的 id）或返回（[onBack]）。
 */
@Composable
fun ScanScreen(
    continuous: Boolean = false,
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
        CameraContent(continuous = continuous, onSaved = onSaved, onBack = onBack)
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
    continuous: Boolean,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dao = remember { ScanDatabase.get(context).scanDao() }

    var boxes by remember { mutableStateOf<List<android.graphics.Rect>>(emptyList()) }
    // ML Kit 看到但解不出來（也還沒被二次增強救回來）的條碼位置
    var undecodedBoxes by remember { mutableStateOf<List<android.graphics.Rect>>(emptyList()) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    val gate = remember { CaptureGate() }

    // 連續掃描：這次掃描中已存過的條碼（格式＋內容），用來避免重複
    val haptic = LocalHapticFeedback.current
    val sessionId = remember { System.currentTimeMillis() }
    val seenKeys = remember { HashSet<String>() }
    val saveJobs = remember { ArrayList<Job>() }
    var sessionCount by remember { mutableIntStateOf(0) }
    var lastValue by remember { mutableStateOf<String?>(null) }
    var firstRecordId by remember { mutableStateOf<Long?>(null) }
    var stopping by remember { mutableStateOf(false) }

    // 結束連續掃描：等還在寫入的截圖都存完再離開，才不會漏掉最後幾個
    val finishSession: (Boolean) -> Unit = { showResult ->
        if (!stopping) {
            stopping = true
            scope.launch {
                saveJobs.toList().joinAll()
                val id = firstRecordId
                if (showResult && id != null) onSaved(id) else onBack()
            }
        }
    }
    val leave: () -> Unit = { if (continuous) finishSession(false) else onBack() }
    BackHandler(enabled = continuous) { finishSession(false) }

    val view = LocalView.current
    DisposableEffect(continuous) {
        if (continuous) view.keepScreenOn = true
        onDispose { if (continuous) view.keepScreenOn = false }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // 自動變焦的上限跟鏡頭有關，所以 analyzer 在每次綁定鏡頭時重建
    val analyzerHolder = remember { arrayOfNulls<BarcodeAnalyzer>(1) }

    DisposableEffect(Unit) {
        onDispose {
            analyzerHolder[0]?.close()
            analysisExecutor.shutdown()
        }
    }

    val previewView = remember { PreviewView(context) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraInfos by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var cameraIndex by remember { mutableStateOf(0) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var torchOn by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // 對焦指示圈顯示一小段時間後消失
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }

    LaunchedEffect(Unit) {
        val p = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }
        val infos = p.availableCameraInfos
        val backIndex = infos.indexOfFirst { info ->
            CameraSelector.DEFAULT_BACK_CAMERA.safeFilter(infos).contains(info)
        }
        cameraIndex = if (backIndex >= 0) backIndex else 0
        cameraInfos = infos
        provider = p
    }

    // 鏡頭切換時重新綁定
    LaunchedEffect(provider, cameraIndex, cameraInfos) {
        val p = provider ?: return@LaunchedEffect
        val target = cameraInfos.getOrNull(cameraIndex) ?: return@LaunchedEffect
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { it == target } }
            .build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        // 預設分析解析度只有 640x480，扭曲（如圓柱面）或較小的條碼會解不出來。
        // 拉高到 1600x1200 並維持 4:3，讓偵測框座標與預覽對齊。
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1600, 1200),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        p.unbindAll()
        analyzerHolder[0]?.close()
        val analyzer = BarcodeAnalyzer(
            maxZoomRatio = min(target.zoomState.value?.maxZoomRatio ?: 1f, MAX_AUTO_ZOOM),
            onZoomSuggested = { ratio ->
                // 條碼太小解不出來時 ML Kit 會建議拉近
                val cam = camera
                if (cam == null || gate.captured) {
                    false
                } else {
                    cam.cameraControl.setZoomRatio(ratio)
                    zoomRatio = ratio
                    true
                }
            },
            onFrame = { codes, undecoded, w, h ->
                boxes = codes.mapNotNull { it.box }
                undecodedBoxes = undecoded
                imageSize = IntSize(w, h)
                if (continuous) {
                    // 這一幀有沒看過的條碼就截圖
                    !stopping && codes.any { it.key() !in seenKeys }
                } else {
                    gate.onFrame(codes.size)
                }
            },
            onCapture = { bitmap, codes ->
                if (continuous) {
                    // 只記這一幀新出現的條碼，已存過的不重複
                    val fresh = codes.filter { seenKeys.add(it.key()) }
                    if (fresh.isNotEmpty()) {
                        sessionCount += fresh.size
                        lastValue = fresh.last().value
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        saveJobs += scope.launch(Dispatchers.IO) {
                            val id = saveScan(context, dao, bitmap, fresh, sessionId)
                            if (firstRecordId == null) firstRecordId = id
                        }
                    }
                } else {
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val id = saveScan(context, dao, bitmap, codes, null)
                        launch(Dispatchers.Main) { onSaved(id) }
                    }
                }
            },
        )
        analyzerHolder[0] = analyzer
        analysis.setAnalyzer(analysisExecutor, analyzer)
        camera = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        zoomRatio = 1f
        torchOn = false
        isFrontCamera = CameraSelector.DEFAULT_FRONT_CAMERA.safeFilter(cameraInfos).contains(target)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        // 偵測框 overlay：把分析影像座標換算到 PreviewView（FILL_CENTER）座標
        // 前鏡頭預覽是鏡像的，x 座標要左右翻轉；同時在這層接收雙指縮放手勢
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        // 把畫面座標轉成感光元件座標，對該點做 AF/AE
                        val point = previewView.meteringPointFactory
                            .createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction
                            .Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                            )
                            .setAutoCancelDuration(4, TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                        focusPoint = offset
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value
                        val newRatio = ((state?.zoomRatio ?: 1f) * zoom).coerceIn(
                            state?.minZoomRatio ?: 1f,
                            state?.maxZoomRatio ?: 1f,
                        )
                        cam.cameraControl.setZoomRatio(newRatio)
                        zoomRatio = newRatio
                    }
                },
        ) {
            if (imageSize.width == 0 || imageSize.height == 0) return@Canvas
            val scale = max(
                size.width / imageSize.width,
                size.height / imageSize.height,
            )
            val dx = (size.width - imageSize.width * scale) / 2f
            val dy = (size.height - imageSize.height * scale) / 2f
            // 綠框：已解出內容；橘框：看到了但還解不出來
            listOf(
                boxes to Color(0xFF4CAF50),
                undecodedBoxes to Color(0xFFFF9800),
            ).forEach { (rects, color) ->
                rects.forEach { r ->
                    val left = if (isFrontCamera) {
                        size.width - (r.right * scale + dx)
                    } else {
                        r.left * scale + dx
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(left, r.top * scale + dy),
                        size = Size(r.width() * scale, r.height() * scale),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }
            focusPoint?.let { p ->
                drawCircle(
                    color = Color.White,
                    radius = 36.dp.toPx(),
                    center = p,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        IconButton(
            onClick = leave,
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
                .padding(top = 16.dp, start = 56.dp, end = 56.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.6f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        saving || stopping -> "儲存中…"
                        continuous -> "連續掃描中 · 已掃到 $sessionCount 個"
                        boxes.isEmpty() && undecodedBoxes.isNotEmpty() ->
                            "看到條碼但讀不出來，試著靠近、點一下對焦或開手電筒"
                        boxes.isEmpty() -> "將鏡頭對準 QR Code / 條碼"
                        else -> "偵測到 ${boxes.size} 個條碼，保持穩定自動拍攝…"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 連續掃描的第二行：讀不出來時給提示，否則顯示最新掃到的內容
                val detail = when {
                    !continuous -> null
                    boxes.isEmpty() && undecodedBoxes.isNotEmpty() -> "看到條碼但讀不出來，試著靠近或對焦"
                    else -> lastValue?.let { "最新：$it" }
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            IconButton(
                onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                    contentDescription = if (torchOn) "關閉手電筒" else "開啟手電筒",
                    tint = Color.White,
                )
            }
        }

        if (zoomRatio > 1.05f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    text = String.format(Locale.US, "%.1fx", zoomRatio),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (continuous) {
            FloatingActionButton(
                onClick = { finishSession(true) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(72.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Icon(Icons.Default.Stop, contentDescription = "停止")
            }
        } else {
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

        if (cameraInfos.size > 1) {
            FloatingActionButton(
                onClick = {
                    val next = (cameraIndex + 1) % cameraInfos.size
                    cameraIndex = next
                    Toast.makeText(
                        context,
                        "切換到${cameraLabel(cameraInfos, next)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 56.dp)
                    .size(56.dp),
                shape = CircleShape,
                containerColor = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "切換鏡頭")
            }
        }
    }
}

private fun CameraSelector.safeFilter(infos: List<CameraInfo>): List<CameraInfo> =
    try {
        filter(infos)
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

private fun cameraLabel(infos: List<CameraInfo>, index: Int): String {
    val target = infos.getOrNull(index) ?: return "鏡頭"
    val backs = CameraSelector.DEFAULT_BACK_CAMERA.safeFilter(infos)
    val fronts = CameraSelector.DEFAULT_FRONT_CAMERA.safeFilter(infos)
    return when {
        target in backs -> "後鏡頭 ${backs.indexOf(target) + 1}/${backs.size}"
        target in fronts -> "前鏡頭 ${fronts.indexOf(target) + 1}/${fronts.size}"
        else -> "鏡頭 ${index + 1}/${infos.size}"
    }
}

/** 連續掃描判斷「是不是同一個條碼」用的鍵：格式＋內容。 */
private fun DetectedCode.key(): String = "$format|$value"

private suspend fun saveScan(
    context: android.content.Context,
    dao: com.littlechin.qrscanner.data.ScanDao,
    bitmap: Bitmap,
    codes: List<DetectedCode>,
    sessionId: Long?,
): Long {
    val dir = File(context.filesDir, "scans").apply { mkdirs() }
    val file = File(dir, "scan_${System.currentTimeMillis()}_${System.nanoTime()}.jpg")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    val items = codes.map { c ->
        val box = c.box
        CodeItem(
            value = c.value,
            format = formatName(c.format),
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
            codesJson = CodeItem.listToJson(items),
            sessionId = sessionId,
        )
    )
}
