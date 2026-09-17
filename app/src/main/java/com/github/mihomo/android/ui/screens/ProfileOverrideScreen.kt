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
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.ConfigManager
import com.github.mihomo.android.data.ProfileItem
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.data.SettingsManager
import com.github.mihomo.android.ui.components.bounceOverscroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.mihomo.android.ui.theme.*

@Composable
fun ProfileOverrideScreen(
    profile: ProfileItem,
    onBack: () -> Unit,
    onNavigateToScripts: () -> Unit,
    onProfileUpdated: (ProfileItem) -> Unit
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var allScripts by remember { mutableStateOf(emptyList<ScriptItem>()) }

    LaunchedEffect(Unit) {
        allScripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }
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
                    contentDescription = AppStrings.get("back", lang),
                    tint = LoonTextPrimary
                )
            }
            Text(
                text = AppStrings.get("profile_override_title", lang),
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
                            text = if (profile.url != null) AppStrings.get("override_remote", lang)
                            else AppStrings.get("override_local", lang),
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
                    text = AppStrings.get("override_desc", lang),
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
                            text = AppStrings.get("override_go_scripts", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = LoonTextPrimary
                        )
                        Text(
                            text = AppStrings.get("override_scripts_hint", lang),
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
            text = AppStrings.get("override_select_scripts", lang),
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
                            text = AppStrings.get("override_no_scripts", lang),
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
                modifier = Modifier
                    .weight(1f)
                    .bounceOverscroll(),
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
                ProfileParser.clearCache()
                onProfileUpdated(updated)
                scope.launch(Dispatchers.IO) {
                    val settings = SettingsManager(context)
                    val profiles = ConfigManager.getProfiles(context)
                    val activeProfile = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()
                    if (activeProfile?.id == profile.id && ClashCore.isCoreLoaded) {
                        try {
                            val configFile = ConfigManager.prepareConfig(context, updated.file)
                            ClashCore.load(configFile)
                        } catch (e: Exception) {
                            android.util.Log.e("ProfileOverride", "Failed to reload config", e)
                        }
                    }
                }
                Toast.makeText(context, AppStrings.get("override_saved", lang), Toast.LENGTH_SHORT).show()
                onBack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(AppStrings.get("override_save", lang), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
