package com.github.mihomo.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.LogEntry
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by LogRepository.logsFlow.collectAsState()
    var selectedLevel by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    val filteredLogs = remember(logs, selectedLevel) {
        if (selectedLevel == "ALL") logs
        else logs.filter { it.level.equals(selectedLevel, ignoreCase = true) }
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = LoonTextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "核心实时日志",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoonTextPrimary
                    )
                    Text(
                        text = "共 ${filteredLogs.size} 条记录",
                        fontSize = 12.sp,
                        color = LoonTextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val text = filteredLogs.joinToString("\n") {
                            "[${timeFormat.format(Date(it.timestamp))}] [${it.level.uppercase()}] ${it.message}"
                        }
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制日志",
                        tint = LoonTextSecondary
                    )
                }

                IconButton(
                    onClick = {
                        val text = filteredLogs.joinToString("\n") {
                            "[${timeFormat.format(Date(it.timestamp))}] [${it.level.uppercase()}] ${it.message}"
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, "导出日志"))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "导出日志",
                        tint = LoonTextSecondary
                    )
                }

                IconButton(
                    onClick = { LogRepository.clear() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空日志",
                        tint = LoonTextSecondary
                    )
                }
            }
        }

        // Level Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "全部", "INFO" to "信息", "WARNING" to "警告", "ERROR" to "错误", "DEBUG" to "调试").forEach { (lvl, title) ->
                val isSelected = selectedLevel == lvl
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) LoonBlue else LoonCard,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (isSelected) LoonBlue else LoonCardBorder, RoundedCornerShape(12.dp))
                        .clickable { selectedLevel = lvl }
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.White else LoonTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Logs Output Console
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E2E), // Modern dark console surface
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF313244))
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无日志输出\n连接 VPN 后，Mihomo 内核实时输出将显示在此处",
                        color = Color(0xFFA6ADC8),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogItemView(log = log, timeStr = timeFormat.format(Date(log.timestamp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItemView(log: LogEntry, timeStr: String) {
    val (badgeColor, textColor) = when (log.level.lowercase()) {
        "error" -> Color(0xFFF38BA8) to Color(0xFFF38BA8)
        "warning", "warn" -> Color(0xFFF9E2AF) to Color(0xFFF9E2AF)
        "debug" -> Color(0xFFCBA6F7) to Color(0xFFCDD6F4)
        else -> Color(0xFF89B4FA) to Color(0xFFCDD6F4)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            color = Color(0xFF6C7086),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            color = badgeColor.copy(alpha = 0.2f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = log.level.uppercase(),
                color = badgeColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = log.message,
            color = textColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
