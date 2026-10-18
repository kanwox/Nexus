package com.github.mihomo.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.LogEntry
import com.github.mihomo.android.data.LogRepository
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val clipboardManager = LocalClipboardManager.current
    val logs by LogRepository.logsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = rememberIosHaptic()

    var selectedLevel by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var isStreamingPaused by remember { mutableStateOf(false) }
    var activeDetailEntry by remember { mutableStateOf<LogEntry?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    val filteredLogs = remember(logs, selectedLevel, searchQuery) {
        logs.filter { entry ->
            val matchLevel = if (selectedLevel == "ALL") true else entry.level.equals(selectedLevel, ignoreCase = true)
            val matchSearch = if (searchQuery.isBlank()) true else entry.message.contains(searchQuery, ignoreCase = true)
            matchLevel && matchSearch
        }
    }

    LaunchedEffect(filteredLogs.size, isStreamingPaused) {
        if (!isStreamingPaused && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
    ) {
        // Cupertino Navigation Bar
        CupertinoNavBar(
            title = AppStrings.get("logs_title", lang),
            subtitle = "${filteredLogs.size} 条记录",
            showBackButton = true,
            onBack = onBack,
            actions = {
                // Play / Pause stream toggle
                IconButton(
                    onClick = {
                        haptic.selection()
                        isStreamingPaused = !isStreamingPaused
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (isStreamingPaused) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                        contentDescription = if (isStreamingPaused) "恢复流式输出" else "暂停流式输出",
                        tint = if (isStreamingPaused) IosOrange else IosBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Search toggle
                IconButton(
                    onClick = {
                        haptic.selection()
                        isSearchVisible = !isSearchVisible
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = if (isSearchVisible) IosBlue else LoonTextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Share / Export
                IconButton(
                    onClick = {
                        haptic.selection()
                        if (filteredLogs.isNotEmpty()) {
                            val allText = filteredLogs.joinToString("\n") {
                                "[${timeFormat.format(Date(it.timestamp))}] [${it.level.uppercase()}] ${it.message}"
                            }
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, allText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "导出日志"))
                        } else {
                            Toast.makeText(context, "当前无日志可导出", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "导出",
                        tint = LoonTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Clear logs
                IconButton(
                    onClick = {
                        haptic.selection()
                        showClearConfirmDialog = true
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "清空",
                        tint = LoonTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )

        // Expandable search bar
        AnimatedVisibility(
            visible = isSearchVisible,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索日志关键字...", fontSize = 13.5.sp, color = LoonTextSecondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LoonCard,
                        unfocusedContainerColor = LoonCard,
                        focusedBorderColor = IosBlue,
                        unfocusedBorderColor = LoonCardBorder
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除", tint = LoonTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )
            }
        }

        // Cupertino Filter Segment Chips Bar
        val levels = listOf(
            "ALL" to "全部",
            "INFO" to "信息",
            "WARNING" to "警告",
            "ERROR" to "错误",
            "DEBUG" to "调试"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEach { (lvlKey, label) ->
                val isSelected = selectedLevel == lvlKey
                val chipColor = when (lvlKey) {
                    "INFO" -> IosBlue
                    "WARNING" -> IosOrange
                    "ERROR" -> IosRed
                    "DEBUG" -> LoonTextSecondary
                    else -> IosBlue
                }

                Surface(
                    shape = CircleShape,
                    color = if (isSelected) chipColor else LoonCard,
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            haptic.selection()
                            selectedLevel = lvlKey
                        }
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else LoonTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Main Console Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(LoonCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = LoonTextSecondary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (logs.isEmpty()) "暂无核心日志" else "无匹配日志",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (logs.isEmpty()) "启动 VPN 后，底层核心输出将实时记录在此" else "请尝试调整筛选级别或搜索关键词",
                            fontSize = 12.5.sp,
                            color = LoonTextSecondary
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                    color = LoonCard,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .bounceOverscroll()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredLogs,
                            key = { it.id }
                        ) { entry ->
                            val levelColor = when (entry.level.lowercase()) {
                                "warning", "warn" -> IosOrange
                                "error" -> IosRed
                                "debug" -> LoonTextSecondary
                                else -> IosBlue
                            }
                            val formattedTime = timeFormat.format(Date(entry.timestamp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptic.selection()
                                        activeDetailEntry = entry
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = formattedTime,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = LoonTextMuted,
                                    modifier = Modifier.width(76.dp)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                // Clean typography for log level - NO square box!
                                Text(
                                    text = entry.level.uppercase().take(4),
                                    color = levelColor,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(36.dp)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                Text(
                                    text = entry.message,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                    color = LoonTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Floating stream pause indicator
            if (isStreamingPaused) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptic.selection()
                            isStreamingPaused = false
                            scope.launch {
                                if (filteredLogs.isNotEmpty()) {
                                    listState.animateScrollToItem(filteredLogs.size - 1)
                                }
                            }
                        },
                    color = LoonCard,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, IosBlue),
                    shape = CircleShape,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = IosBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "已暂停 • 滚动到底部并继续",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IosBlue
                        )
                    }
                }
            }
        }
    }

    // Detail BottomSheet for tapped entry
    if (activeDetailEntry != null) {
        val entry = activeDetailEntry!!
        val formattedTime = timeFormat.format(Date(entry.timestamp))
        val levelColor = when (entry.level.lowercase()) {
            "warning", "warn" -> IosOrange
            "error" -> IosRed
            "debug" -> LoonTextSecondary
            else -> IosBlue
        }

        ModalBottomSheet(
            onDismissRequest = { activeDetailEntry = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.level.uppercase(),
                            color = levelColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = formattedTime,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = LoonTextSecondary
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(entry.message))
                            Toast.makeText(context, "已复制单条日志", Toast.LENGTH_SHORT).show()
                            activeDetailEntry = null
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", tint = IosBlue, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LoonBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = entry.message,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                        color = LoonTextPrimary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }

    // Clear logs confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "清空核心日志",
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary
                )
            },
            text = {
                Text(
                    text = "确定要清空当前所有运行日志记录吗？",
                    color = LoonTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        LogRepository.clear()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("清空", color = IosRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消", color = LoonTextSecondary)
                }
            },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
