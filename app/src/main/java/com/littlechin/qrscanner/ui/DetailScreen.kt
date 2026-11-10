package com.littlechin.qrscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.littlechin.qrscanner.data.CodeItem
import com.littlechin.qrscanner.data.ScanDatabase
import com.littlechin.qrscanner.data.ScanRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val timeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 單次掃描是一張截圖；連續掃描是同一個 session 的所有截圖
    var frames by remember { mutableStateOf<List<ScanRecord>>(emptyList()) }

    LaunchedEffect(recordId) {
        val dao = ScanDatabase.get(context).scanDao()
        val record = dao.byId(recordId)
        val sessionId = record?.sessionId
        frames = when {
            record == null -> emptyList()
            sessionId != null -> dao.bySession(sessionId)
            else -> listOf(record)
        }
    }

    val continuous = frames.firstOrNull()?.sessionId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (continuous) "連續掃描結果" else "掃描結果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val first = frames.firstOrNull() ?: return@Scaffold
        val frameCodes = remember(frames) { frames.map { it.codes } }
        val total = frameCodes.sumOf { it.size }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    timeFormat.format(Date(first.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "共 $total 個條碼",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            // 編號跨截圖連續：第二張圖的條碼接著第一張往下編
            var offset = 0
            frames.forEachIndexed { frameIndex, frame ->
                val codes = frameCodes[frameIndex]
                val startIndex = offset
                offset += codes.size
                item(key = "frame-${frame.id}") {
                    FrameImage(frame = frame, codes = codes, startIndex = startIndex)
                    Spacer(Modifier.padding(4.dp))
                }
                itemsIndexed(codes) { index, code ->
                    CodeCard(index = startIndex + index, value = code.value, format = code.format)
                    Spacer(Modifier.padding(4.dp))
                }
                if (frameIndex < frames.lastIndex) {
                    item { Spacer(Modifier.padding(8.dp)) }
                }
            }
        }
    }
}

/** 截圖 + 編號框 overlay。 */
@Composable
private fun FrameImage(frame: ScanRecord, codes: List<CodeItem>, startIndex: Int) {
    if (frame.imageWidth <= 0 || frame.imageHeight <= 0) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(frame.imageWidth.toFloat() / frame.imageHeight)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        AsyncImage(
            model = File(frame.imagePath),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = min(
                size.width / frame.imageWidth,
                size.height / frame.imageHeight,
            )
            val dx = (size.width - frame.imageWidth * scale) / 2f
            val dy = (size.height - frame.imageHeight * scale) / 2f
            codes.forEachIndexed { index, c ->
                val left = c.left * scale + dx
                val top = c.top * scale + dy
                drawRect(
                    color = Color(0xFF4CAF50),
                    topLeft = Offset(left, top),
                    size = Size((c.right - c.left) * scale, (c.bottom - c.top) * scale),
                    style = Stroke(width = 3.dp.toPx()),
                )
                // 編號圓點
                val radius = 12.dp.toPx()
                drawCircle(
                    color = Color(0xFF4CAF50),
                    radius = radius,
                    center = Offset(left, top),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${startIndex + index + 1}",
                    left,
                    top + radius / 2.5f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 13.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                        isAntiAlias = true
                    },
                )
            }
        }
    }
}

@Composable
private fun CodeCard(index: Int, value: String, format: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isUrl = value.startsWith("http://") || value.startsWith("https://")

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    format,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "複製")
            }
            if (isUrl) {
                IconButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "開啟連結")
                }
            }
        }
    }
}
