package com.littlechin.qrscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    var record by remember { mutableStateOf<ScanRecord?>(null) }

    LaunchedEffect(recordId) {
        record = ScanDatabase.get(context).scanDao().byId(recordId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("掃描結果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val r = record ?: return@Scaffold
        val codes = remember(r.codesJson) { r.codes }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                timeFormat.format(Date(r.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // 圖片 + 編號框 overlay
            if (r.imageWidth > 0 && r.imageHeight > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(r.imageWidth.toFloat() / r.imageHeight)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    AsyncImage(
                        model = File(r.imagePath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = min(
                            size.width / r.imageWidth,
                            size.height / r.imageHeight,
                        )
                        val dx = (size.width - r.imageWidth * scale) / 2f
                        val dy = (size.height - r.imageHeight * scale) / 2f
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
                                "${index + 1}",
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

            Text(
                "共 ${codes.size} 個條碼",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            codes.forEachIndexed { index, code ->
                CodeCard(index = index, value = code.value, format = code.format)
                Spacer(Modifier.padding(4.dp))
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
