package com.littlechin.qrscanner.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.littlechin.qrscanner.data.CodeItem
import com.littlechin.qrscanner.data.ScanDatabase
import com.littlechin.qrscanner.data.ScanRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onContinuousScan: () -> Unit,
    onOpenRecord: (Long) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { ScanDatabase.get(context).scanDao() }
    val records = dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val entries = remember(records.value) { groupEntries(records.value) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("QR 掃描器") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("開始掃描", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedButton(
                onClick = onContinuousScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Repeat, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("連續掃描", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                "掃描紀錄",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    "還沒有任何紀錄，按上面的按鈕開始掃描吧！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        SwipeToDeleteContainer(
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    entry.records.forEach { File(it.imagePath).delete() }
                                    dao.deleteAll(entry.records)
                                }
                            },
                        ) {
                            RecordCard(
                                entry = entry,
                                onClick = { onOpenRecord(entry.records.first().id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 首頁的一筆紀錄：單次掃描是一張截圖，連續掃描是同一個 session 的多張截圖（依時間先後）。 */
private class HomeEntry(val key: String, val records: List<ScanRecord>) {
    val continuous: Boolean get() = records.first().sessionId != null
    val codes: List<CodeItem> = records.flatMap { it.codes }
}

/** [records] 依時間新到舊；連續掃描的截圖併成一筆，位置以最新的那張為準。 */
private fun groupEntries(records: List<ScanRecord>): List<HomeEntry> {
    val groups = LinkedHashMap<String, MutableList<ScanRecord>>()
    for (r in records) {
        val key = r.sessionId?.let { "s$it" } ?: "r${r.id}"
        groups.getOrPut(key) { ArrayList() } += r
    }
    return groups.map { (key, list) -> HomeEntry(key, list.sortedWith(compareBy({ it.timestamp }, { it.id }))) }
}

private val timeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

@Composable
private fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
    )
    // 滑超過一半（放開就會刪除）時背景變成鮮紅色，否則是灰色
    val armed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    val backgroundColor by animateColorAsState(
        targetValue = if (armed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "swipeDeleteBackground",
    )
    val iconColor = if (armed) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "刪除",
                    tint = iconColor,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun RecordCard(
    entry: HomeEntry,
    onClick: () -> Unit,
) {
    val record = entry.records.first()
    val codes = entry.codes
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = File(record.imagePath),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    timeFormat.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (entry.continuous) "連續掃描 · ${codes.size} 個條碼" else "${codes.size} 個條碼",
                    style = MaterialTheme.typography.titleSmall,
                )
                codes.firstOrNull()?.let {
                    Text(
                        it.value,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
