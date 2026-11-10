package com.littlechin.qrscanner.scanner

import android.graphics.Bitmap

/**
 * 給「偵測到但解不出來」的條碼做影像增強用的運算。
 * 全部在灰階 IntArray（每個元素 0..255）上操作。
 *
 * 影像邊長上限由呼叫端控制在 1024 以內，積分圖才不會超出 Int 範圍。
 */
internal object RescueEnhancer {

    fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return px
    }

    fun toBitmap(gray: IntArray, w: Int, h: Int): Bitmap {
        val px = IntArray(gray.size)
        for (i in gray.indices) {
            val g = gray[i]
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 把 2%～98% 的亮度範圍拉開到 0..255，救低對比（褪色、反光、陰影）的條碼。 */
    fun stretchContrast(gray: IntArray) {
        if (gray.isEmpty()) return
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        val cut = gray.size / 50
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) acc += hist[lo++]
        var hi = 255
        acc = 0
        while (hi > 0 && acc + hist[hi] <= cut) acc += hist[hi--]
        val range = hi - lo
        if (range < 8) return
        for (i in gray.indices) {
            gray[i] = ((gray[i] - lo) * 255 / range).coerceIn(0, 255)
        }
    }

    /** 反銳化遮罩：原圖加上「原圖 − 模糊圖」的 1.5 倍，把糊掉的格子邊緣拉回來。 */
    fun unsharp(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val integral = integral(gray, w, h)
        val out = IntArray(gray.size)
        forEachWindow(integral, w, h, radius) { i, sum, count ->
            val g = gray[i]
            out[i] = (g + (g - sum / count) * 3 / 2).coerceIn(0, 255)
        }
        return out
    }

    /** 區域自適應二值化（Bradley）：每個像素跟周圍平均比，不受整體明暗不均影響。 */
    fun adaptiveThreshold(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val integral = integral(gray, w, h)
        val out = IntArray(gray.size)
        forEachWindow(integral, w, h, radius) { i, sum, count ->
            out[i] = if (gray[i] * count * 100 < sum * 85) 0 else 255
        }
        return out
    }

    private fun integral(gray: IntArray, w: Int, h: Int): IntArray {
        val stride = w + 1
        val out = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                out[(y + 1) * stride + x + 1] = out[y * stride + x + 1] + rowSum
            }
        }
        return out
    }

    private inline fun forEachWindow(
        integral: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        block: (index: Int, sum: Int, count: Int) -> Unit,
    ) {
        val stride = w + 1
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(h - 1, y + radius) + 1
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(w - 1, x + radius) + 1
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] + integral[y0 * stride + x0]
                block(y * w + x, sum, (x1 - x0) * (y1 - y0))
            }
        }
    }
}
