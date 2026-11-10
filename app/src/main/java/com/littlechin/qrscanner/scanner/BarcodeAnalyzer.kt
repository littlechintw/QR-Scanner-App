package com.littlechin.qrscanner.scanner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min

/** 一個已解出內容的條碼。[box] 是在轉正後整幀影像上的位置。 */
data class DetectedCode(
    val value: String,
    val format: Int,
    val box: Rect?,
)

/** 救援解碼最短間隔，避免每一幀都做增強拖慢預覽。 */
private const val RESCUE_INTERVAL_MS = 300L

/** 救回來的內容沿用多久；過期後會重新救一次再確認。 */
private const val RESCUE_TTL_MS = 2000L

/** 每輪最多處理幾個解不出來的條碼（面積大的優先）。 */
private const val RESCUE_MAX_BOXES = 2

/** 裁切時四周多留的比例，確保 QR code 的留白區（quiet zone）還在。 */
private const val RESCUE_PADDING = 0.2f

/** 裁切後放大到短邊約這個像素數。 */
private const val RESCUE_TARGET_SIDE = 600f

/** 裁切後長邊上限（[RescueEnhancer] 的積分圖以此為前提）。 */
private const val RESCUE_MAX_SIDE = 1024f

/** 每個裁切嘗試的版本數：放大原圖、拉對比＋銳化、自適應二值化。 */
private const val RESCUE_VARIANTS = 3

private const val TAG = "BarcodeRescue"

/**
 * 每一幀都用 ML Kit 偵測條碼（支援同畫面多個 QR code / barcode）。
 *
 * 除了一般解碼，還做兩件事來救模糊、太小、低對比的條碼：
 * - 條碼太小時，ML Kit 會透過 [onZoomSuggested] 建議拉近（回傳 true 表示已套用）。
 * - ML Kit「看到但解不出來」的條碼，會裁切、放大、增強後再解一次；
 *   救回來的內容會跟著該條碼的位置沿用一小段時間，當成一般結果回報。
 *
 * [onFrame] 回傳這一幀已解出的條碼、仍解不出來的位置，以及轉正後的影像尺寸；
 * 回傳 true 表示要擷取這一幀 —— 此時會把影像轉成轉正後的 Bitmap 交給 [onCapture]。
 */
class BarcodeAnalyzer(
    maxZoomRatio: Float,
    private val onZoomSuggested: (Float) -> Boolean,
    private val onFrame: (codes: List<DetectedCode>, undecoded: List<Rect>, width: Int, height: Int) -> Boolean,
    private val onCapture: (bitmap: Bitmap, codes: List<DetectedCode>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAllPotentialBarcodes()
            .apply {
                if (maxZoomRatio > 1f) {
                    setZoomSuggestionOptions(
                        ZoomSuggestionOptions.Builder { ratio -> onZoomSuggested(ratio) }
                            .setMaxSupportedZoomRatio(maxZoomRatio)
                            .build()
                    )
                }
            }
            .build()
    )

    /** 救援用的掃描器不開自動變焦：它看到的是放大過的裁切圖，變焦建議沒有意義。 */
    private val rescueScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rescueThread = Executors.newSingleThreadExecutor()

    /** close() 之後還在路上的工作直接丟掉，不要讓 RejectedExecutionException 冒出去。 */
    private val rescueExecutor = Executor { task ->
        try {
            rescueThread.execute(task)
        } catch (e: RejectedExecutionException) {
            // analyzer 已關閉
        }
    }

    private class Rescued(val value: String, val format: Int, var box: Rect, val decodedAt: Long)

    // 以下狀態只在主執行緒讀寫（ML Kit 的 listener 預設跑在主執行緒）
    private val rescued = ArrayList<Rescued>()
    private var lastRescueAt = 0L

    @Volatile
    private var rescueInFlight = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        // 交給救援執行緒後，由它負責 close
        var handedOff = false
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val now = SystemClock.elapsedRealtime()
                val codes = ArrayList<DetectedCode>()
                val undecoded = ArrayList<Rect>()
                for (b in barcodes) {
                    if (b.isDecoded()) {
                        codes += b.toDetectedCode(b.boundingBox)
                    } else {
                        b.boundingBox?.let { undecoded += it }
                    }
                }
                val pending = applyRescued(undecoded, codes, now)

                val rotated = rotation % 180 != 0
                val width = if (rotated) imageProxy.height else imageProxy.width
                val height = if (rotated) imageProxy.width else imageProxy.height
                val shouldCapture = onFrame(codes, pending, width, height)
                if (shouldCapture) {
                    val bitmap = imageProxy.toBitmap().rotate(rotation)
                    onCapture(bitmap, codes)
                } else if (
                    pending.isNotEmpty() &&
                    !rescueInFlight &&
                    now - lastRescueAt >= RESCUE_INTERVAL_MS
                ) {
                    lastRescueAt = now
                    handedOff = startRescue(imageProxy, rotation, pending)
                }
            }
            .addOnCompleteListener { if (!handedOff) imageProxy.close() }
    }

    fun close() {
        scanner.close()
        rescueScanner.close()
        rescueThread.shutdown()
    }

    /**
     * 把先前救回來的內容套到這一幀仍解不出來的位置上（以中心點落在框內來對應），
     * 對得上的加進 [codes]；回傳還沒有內容的位置。
     */
    private fun applyRescued(undecoded: List<Rect>, codes: MutableList<DetectedCode>, now: Long): List<Rect> {
        rescued.removeAll { now - it.decodedAt > RESCUE_TTL_MS }
        if (undecoded.isEmpty()) return emptyList()
        val pending = ArrayList<Rect>()
        val used = HashSet<Rescued>()
        for (box in undecoded) {
            val match = rescued.firstOrNull {
                it !in used && box.contains(it.box.centerX(), it.box.centerY())
            }
            if (match != null) {
                used += match
                match.box = box
                codes += DetectedCode(match.value, match.format, box)
            } else {
                pending += box
            }
        }
        return pending
    }

    /** 回傳 true 表示 [imageProxy] 已交給救援執行緒負責關閉。 */
    private fun startRescue(imageProxy: ImageProxy, rotation: Int, boxes: List<Rect>): Boolean {
        val targets = boxes.sortedByDescending { it.width() * it.height() }.take(RESCUE_MAX_BOXES)
        rescueInFlight = true
        return try {
            rescueThread.execute {
                val jobs = try {
                    val frame = imageProxy.toBitmap()
                    targets.mapNotNull { box -> cropUpright(frame, rotation, box)?.let { RescueJob(box, it) } }
                } catch (e: Exception) {
                    Log.w(TAG, "crop failed", e)
                    emptyList()
                } finally {
                    imageProxy.close()
                }
                rescueNext(jobs, 0, 0)
            }
            true
        } catch (e: RejectedExecutionException) {
            rescueInFlight = false
            false
        }
    }

    /** 在救援執行緒上依序嘗試每個裁切的每個增強版本，解出來就換下一個裁切。 */
    private fun rescueNext(jobs: List<RescueJob>, jobIndex: Int, variant: Int) {
        val job = jobs.getOrNull(jobIndex)
        if (job == null) {
            rescueInFlight = false
            return
        }
        val image = try {
            job.variant(variant)
        } catch (e: Exception) {
            Log.w(TAG, "enhance failed", e)
            rescueNext(jobs, jobIndex + 1, 0)
            return
        }
        try {
            rescueScanner.process(InputImage.fromBitmap(image, 0))
                .addOnCompleteListener(rescueExecutor) { task ->
                    val hit = if (task.isSuccessful) {
                        nearestToCenter(task.result.filter { it.isDecoded() }, image.width, image.height)
                    } else {
                        null
                    }
                    when {
                        hit != null -> {
                            Log.d(TAG, "decoded with variant $variant")
                            val code = hit.toDetectedCode(job.box)
                            mainHandler.post { rememberRescued(code) }
                            rescueNext(jobs, jobIndex + 1, 0)
                        }
                        variant + 1 < RESCUE_VARIANTS -> rescueNext(jobs, jobIndex, variant + 1)
                        else -> rescueNext(jobs, jobIndex + 1, 0)
                    }
                }
        } catch (e: Exception) {
            // 掃描器已關閉
            rescueInFlight = false
        }
    }

    private fun rememberRescued(code: DetectedCode) {
        val box = code.box ?: return
        rescued.removeAll { Rect.intersects(it.box, box) }
        rescued += Rescued(code.value, code.format, box, SystemClock.elapsedRealtime())
    }

    private class RescueJob(val box: Rect, private val crop: Bitmap) {
        private val sharpened: IntArray by lazy {
            val gray = RescueEnhancer.toGray(crop)
            RescueEnhancer.stretchContrast(gray)
            RescueEnhancer.unsharp(gray, crop.width, crop.height, max(1, minSide / 160))
        }

        private val minSide get() = min(crop.width, crop.height)

        fun variant(index: Int): Bitmap = when (index) {
            0 -> crop
            1 -> RescueEnhancer.toBitmap(sharpened, crop.width, crop.height)
            else -> RescueEnhancer.toBitmap(
                RescueEnhancer.adaptiveThreshold(sharpened, crop.width, crop.height, max(8, minSide / 12)),
                crop.width,
                crop.height,
            )
        }
    }
}

private fun Barcode.isDecoded(): Boolean =
    !rawValue.isNullOrEmpty() || rawBytes?.isNotEmpty() == true

private fun Barcode.toDetectedCode(box: Rect?): DetectedCode =
    DetectedCode(rawValue ?: displayValue ?: "", format, box)

/** 裁切有留邊，可能把隔壁的條碼也框進來；取最靠近中心的那個。 */
private fun nearestToCenter(barcodes: List<Barcode>, width: Int, height: Int): Barcode? =
    barcodes.minByOrNull { b ->
        val box = b.boundingBox ?: return@minByOrNull Float.MAX_VALUE
        val dx = box.exactCenterX() - width / 2f
        val dy = box.exactCenterY() - height / 2f
        dx * dx + dy * dy
    }

/**
 * 從未旋轉的 [frame] 裁出 [box]（轉正後座標）加上留邊的區域，同時轉正並放大。
 */
private fun cropUpright(frame: Bitmap, rotation: Int, box: Rect): Bitmap? {
    val toUpright = Matrix().apply { postRotate(rotation.toFloat()) }
    val bounds = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
    toUpright.mapRect(bounds)
    toUpright.postTranslate(-bounds.left, -bounds.top)
    val toSource = Matrix()
    if (!toUpright.invert(toSource)) return null

    val padX = box.width() * RESCUE_PADDING
    val padY = box.height() * RESCUE_PADDING
    val src = RectF(box.left - padX, box.top - padY, box.right + padX, box.bottom + padY)
    toSource.mapRect(src)

    val left = src.left.toInt().coerceIn(0, frame.width)
    val top = src.top.toInt().coerceIn(0, frame.height)
    val right = src.right.toInt().coerceIn(0, frame.width)
    val bottom = src.bottom.toInt().coerceIn(0, frame.height)
    val w = right - left
    val h = bottom - top
    if (w < 16 || h < 16) return null

    val scale = min(
        (RESCUE_TARGET_SIDE / min(w, h)).coerceIn(1f, 4f),
        RESCUE_MAX_SIDE / max(w, h),
    )
    val transform = Matrix().apply {
        postRotate(rotation.toFloat())
        postScale(scale, scale)
    }
    return Bitmap.createBitmap(frame, left, top, w, h, transform, true)
}

private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun formatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR Code"
    Barcode.FORMAT_AZTEC -> "Aztec"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_CODABAR -> "Codabar"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    else -> "Barcode"
}
