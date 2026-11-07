package com.littlechin.qrscanner.scanner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 每一幀都用 ML Kit 偵測條碼（支援同畫面多個 QR code / barcode）。
 * [onFrame] 回傳這一幀偵測到的條碼與旋轉後的影像尺寸；
 * 回傳 true 表示要擷取這一幀 —— 此時會把影像轉成轉正後的 Bitmap 交給 [onCapture]。
 */
class BarcodeAnalyzer(
    private val onFrame: (barcodes: List<Barcode>, width: Int, height: Int) -> Boolean,
    private val onCapture: (bitmap: Bitmap, barcodes: List<Barcode>) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val rotated = rotation % 180 != 0
                val width = if (rotated) imageProxy.height else imageProxy.width
                val height = if (rotated) imageProxy.width else imageProxy.height
                val shouldCapture = onFrame(barcodes, width, height)
                if (shouldCapture) {
                    val bitmap = imageProxy.toBitmap().rotate(rotation)
                    onCapture(bitmap, barcodes)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
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
