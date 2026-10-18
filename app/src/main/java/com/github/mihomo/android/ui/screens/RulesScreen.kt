package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.github.mihomo.android.core.model.TunnelMode
import com.github.mihomo.android.data.ParsedRule
import com.github.mihomo.android.ui.components.*
import com.github.mihomo.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    rules: List<ParsedRule>,
    totalRules: Int,
    tunnelMode: TunnelMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lang = LocalAppLanguage.current
    val haptic = rememberIosHaptic()

    var searchQuery by remember { mutableStateOf("") }
    var selectedBehavior by remember { mutableStateOf("ALL") }
    var inspectedRule by remember { mutableStateOf<Pair<Int, ParsedRule>?>(null) }

    val behaviorTabs = listOf("ALL", "PROXY", "DIRECT", "REJECT")

    val filteredRules = remember(rules, searchQuery, selectedBehavior) {
        rules.filter { rule ->
            val matchBehavior = when (selectedBehavior) {
                "DIRECT" -> rule.target.equals("DIRECT", ignoreCase = true)
                "REJECT" -> rule.target.contains("REJECT", ignoreCase = true)
                "PROXY" -> !rule.target.equals("DIRECT", ignoreCase = true) && !rule.target.contains("REJECT", ignoreCase = true)
                else -> true
            }
            val matchQuery = searchQuery.isBlank() ||
                rule.payload.contains(searchQuery, ignoreCase = true) ||
                rule.target.contains(searchQuery, ignoreCase = true) ||
                rule.type.contains(searchQuery, ignoreCase = true)
            matchBehavior && matchQuery
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
            title = AppStrings.get("rules_screen_title", lang),
            subtitle = "${tunnelMode.displayName} · 共 $totalRules 条规则",
            showBackButton = true,
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Cupertino Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索规则类型、域名、目标策略...", fontSize = 13.sp, color = LoonTextMuted) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = LoonTextMuted, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "清除", tint = LoonTextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LoonCard,
                    unfocusedContainerColor = LoonCard,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = LoonTextPrimary,
                    unfocusedTextColor = LoonTextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // iOS Segmented Behavior Filter
            CupertinoSegmentedControl(
                items = behaviorTabs,
                selectedItem = selectedBehavior,
                onItemSelected = { selectedBehavior = it },
                labelProvider = { tab ->
                    when (tab) {
                        "ALL" -> "全部 (${rules.size})"
                        "PROXY" -> "代理"
                        "DIRECT" -> "直连"
                        "REJECT" -> "拦截"
                        else -> tab
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )

            // Rules List
            if (filteredRules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .bounceOverscroll(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            tint = LoonTextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (rules.isEmpty()) "当前配置未配置任何分流规则\n所有流量将采用默认出站策略" else "未找到符合条件的规则",
                            color = LoonTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .bounceOverscroll(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filteredRules) { index, rule ->
                        val isDirect = rule.target.equals("DIRECT", ignoreCase = true)
                        val isReject = rule.target.contains("REJECT", ignoreCase = true)
                        val targetColor = when {
                            isDirect -> IosGreen
                            isReject -> IosRed
                            else -> IosBlue
                        }

                        val typeColor = when {
                            rule.type.contains("DOMAIN", ignoreCase = true) -> IosBlue
                            rule.type.contains("GEOIP", ignoreCase = true) -> IosOrange
                            rule.type.contains("IP-CIDR", ignoreCase = true) -> Color(0xFF5856D6)
                            rule.type.contains("RULE-SET", ignoreCase = true) -> IosGreen
                            else -> LoonTextSecondary
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = LoonCard,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .iosPressable {
                                    haptic.selection()
                                    inspectedRule = Pair(index + 1, rule)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        fontSize = 11.sp,
                                        color = LoonTextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(34.dp)
                                    )

                                    // Clean text typography for rule type - NO square box!
                                    Text(
                                        text = rule.type,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = typeColor
                                    )

                                    Spacer(modifier = Modifier.width(10.dp))

                                    if (rule.payload.isNotBlank()) {
                                        Text(
                                            text = rule.payload,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = LoonTextPrimary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Text(
                                            text = "MATCH ALL",
                                            fontSize = 12.sp,
                                            color = LoonTextMuted,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (rule.noResolve) {
                                        Text(
                                            text = "no-resolve",
                                            fontSize = 10.sp,
                                            color = LoonTextMuted,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }

                                    // Round pill badge strictly for highlight target
                                    Surface(
                                        shape = CircleShape,
                                        color = targetColor.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = rule.target.ifBlank { "DIRECT" },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = targetColor,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Rule Inspection Bottom Sheet (Flat iOS layout without nested boxes)
    // =========================================================================
    if (inspectedRule != null) {
        val (idx, rule) = inspectedRule!!
        val fullRuleText = if (rule.payload.isNotBlank()) {
            if (rule.noResolve) "${rule.type},${rule.payload},${rule.target},no-resolve"
            else "${rule.type},${rule.payload},${rule.target}"
        } else {
            "${rule.type},${rule.target}"
        }

        ModalBottomSheet(
            onDismissRequest = { inspectedRule = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "规则明细 (#$idx)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LoonTextPrimary
                )
                Text(
                    text = "底层路由分流匹配链条",
                    fontSize = 12.sp,
                    color = LoonTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                )

                // Flat detail rows with dividers
                val details = listOf(
                    "匹配模式" to rule.type,
                    "匹配载荷" to rule.payload.ifBlank { "MATCH (匹配全部)" },
                    "出站策略" to rule.target,
                    "跳过 DNS 解析" to if (rule.noResolve) "是 (no-resolve)" else "否"
                )

                details.forEachIndexed { i, (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, fontSize = 14.sp, color = LoonTextSecondary)
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LoonTextPrimary)
                    }
                    if (i < details.size - 1) {
                        HorizontalDivider(color = LoonCardBorder, thickness = 0.5.dp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("规则表达式", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LoonTextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = fullRuleText,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LoonTextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LoonBg)
                        .padding(12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(fullRuleText))
                            Toast.makeText(context, "已复制完整规则", Toast.LENGTH_SHORT).show()
                            inspectedRule = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IosBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("复制规则", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { inspectedRule = null },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, LoonCardBorder),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("关闭", color = LoonTextPrimary)
                    }
                }
            }
        }
    }
}
