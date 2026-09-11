package com.github.mihomo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.ui.theme.*

@Composable
fun ProfileOverrideScreen(
    profile: ProfileItem,
    onBack: () -> Unit,
    onNavigateToScripts: () -> Unit,
    onProfileUpdated: (ProfileItem) -> Unit
) {
    val context = LocalContext.current
    val allScripts = remember { ScriptManager.getScripts(context) }
    var scriptEnabled by remember(profile) { mutableStateOf(profile.scriptEnabled) }
    val selectedScriptIds = remember(profile) { profile.scriptIds.toMutableStateList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = LoonTextPrimary
                )
            }
            Text(
                text = "配置覆写",
                color = LoonTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Profile Info Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, LoonCardBorder, RoundedCornerShape(16.dp)),
            color = LoonCard,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = LoonTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (profile.url != null) "远程订阅配置" else "本地配置文件",
                            fontSize = 12.sp,
                            color = LoonTextSecondary
                        )
                    }

                    Switch(
                        checked = scriptEnabled,
                        onCheckedChange = { scriptEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "开启后，此配置将由选中的脚本进行动态规则与策略组复写。",
                    fontSize = 11.5.sp,
                    color = LoonTextMuted,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Direct Shortcut to Scripts Screen
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, LoonCardBorder, RoundedCornerShape(14.dp))
                .clickable { onNavigateToScripts() },
            color = LoonCard,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LoonEditBlueBg,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = LoonBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "前往脚本管理",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = LoonTextPrimary
                        )
                        Text(
                            text = "导入、新建或编辑脚本代码",
                            fontSize = 11.sp,
                            color = LoonTextMuted
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LoonTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "选择要绑定的脚本",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = LoonTextPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Script List
        if (allScripts.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, LoonCardBorder, RoundedCornerShape(16.dp)),
                color = LoonCard,
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Javascript,
                            contentDescription = null,
                            tint = LoonTextMuted,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "当前尚无任何脚本\n请点击上方“前往脚本管理”导入脚本",
                            color = LoonTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allScripts, key = { it.id }) { script ->
                    val isChecked = selectedScriptIds.contains(script.id)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                1.dp,
                                if (isChecked && scriptEnabled) LoonBlue else LoonCardBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = scriptEnabled) {
                                if (isChecked) {
                                    selectedScriptIds.remove(script.id)
                                } else {
                                    selectedScriptIds.add(script.id)
                                }
                            },
                        color = if (isChecked && scriptEnabled) LoonEditBlueBg else LoonCard,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = script.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (scriptEnabled) LoonTextPrimary else LoonTextMuted
                                )
                                if (script.url.isNotBlank()) {
                                    Text(
                                        text = script.url,
                                        fontSize = 10.5.sp,
                                        color = LoonTextMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Checkbox(
                                checked = isChecked,
                                enabled = scriptEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedScriptIds.add(script.id)
                                    } else {
                                        selectedScriptIds.remove(script.id)
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = LoonBlue,
                                    uncheckedColor = LoonTextMuted
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Save Button
        Button(
            onClick = {
                val updated = profile.copy(
                    scriptEnabled = scriptEnabled,
                    scriptIds = selectedScriptIds.toList()
                )
                ConfigManager.updateProfile(context, updated)
                onProfileUpdated(updated)
                Toast.makeText(context, "配置覆写已保存", Toast.LENGTH_SHORT).show()
                onBack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("保存覆写设置", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
