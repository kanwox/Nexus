package com.github.mihomo.android.ui.screens

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.github.mihomo.android.data.ProfileParser
import com.github.mihomo.android.data.ScriptItem
import com.github.mihomo.android.data.ScriptManager
import com.github.mihomo.android.ui.components.bounceOverscroll
import com.github.mihomo.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    onBack: () -> Unit,
    onScriptsChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(emptyList<ScriptItem>()) }

    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { ScriptManager.getScripts(context) }
    }

    fun refresh() {
        scripts = ScriptManager.getScripts(context)
        onScriptsChanged()
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var editingScript by remember { mutableStateOf<ScriptItem?>(null) }
    var scriptCodeInput by remember { mutableStateOf("") }
    var deleteConfirmScript by remember { mutableStateOf<ScriptItem?>(null) }

    val localScriptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                var fileName = "local_script.js"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                }
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (content.isNotBlank()) {
                    val scriptName = fileName.removeSuffix(".js").removeSuffix(".txt")
                    ScriptManager.saveLocalScript(
                        context = context,
                        name = scriptName,
                        pattern = "",
                        type = "profile-modify",
                        code = content
                    )
                    refresh()
                    Toast.makeText(
                        context,
                        AppStrings.get("scripts_imported_toast", lang).replace("{name}", scriptName),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(context, AppStrings.get("scripts_empty_content", lang), Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(
                    context,
                    AppStrings.get("scripts_read_failed", lang).replace("{msg}", it.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LoonBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Top Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    text = AppStrings.get("scripts_title", lang),
                    color = LoonTextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = { showImportUrlDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = AppStrings.get("scripts_import_network", lang),
                    tint = LoonTextSecondary
                )
            }
        }

        // Action Buttons: Distinct colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Button 1: 本地导入 (Solid Brand Blue)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { localScriptLauncher.launch("*/*") },
                color = LoonBlue,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = AppStrings.get("scripts_import_local", lang),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Button 2: 新建脚本 (Emerald Green, clearly distinguished from Blue)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showCreateDialog = true },
                color = Color(0xFF10B981),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = AppStrings.get("scripts_new", lang),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Script list
        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Javascript,
                        contentDescription = null,
                        tint = LoonTextMuted,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = AppStrings.get("scripts_empty", lang),
                        color = LoonTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .bounceOverscroll(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, LoonCardBorder, RoundedCornerShape(16.dp)),
                        color = LoonCard,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Badge with uniform soft gray LoonEditBlueBg background
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = LoonEditBlueBg,
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = "JS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LoonTextPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = script.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = LoonTextPrimary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = script.enabled,
                                        onCheckedChange = { isChecked ->
                                            ScriptManager.toggleScript(context, script.id, isChecked)
                                            ProfileParser.clearCache()
                                            refresh()
                                        },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    IconButton(
                                        onClick = { deleteConfirmScript = script },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = AppStrings.get("scripts_delete_title", lang),
                                            tint = RedDanger,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            if (script.url.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = script.url,
                                    fontSize = 11.sp,
                                    color = LoonTextMuted,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = AppStrings.get("scripts_bind_hint", lang),
                                    fontSize = 11.sp,
                                    color = LoonTextMuted
                                )

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = LoonEditBlueBg,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            scriptCodeInput = ScriptManager.readScriptCode(context, script)
                                            editingScript = script
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = LoonBlue,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = AppStrings.get("scripts_edit", lang),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LoonBlue
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

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var initialCode by remember {
            mutableStateOf(
                "/**\n * Nexus 配置复写脚本\n * @param {Object} config\n * @returns {Object}\n */\nfunction main(config) {\n  return config;\n}\n"
            )
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(AppStrings.get("scripts_new_title", lang), color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(AppStrings.get("scripts_name_label", lang)) },
                        placeholder = { Text(AppStrings.get("scripts_name_placeholder", lang)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            ScriptManager.saveLocalScript(
                                context = context,
                                name = newName.trim(),
                                pattern = "",
                                type = "profile-modify",
                                code = initialCode
                            )
                            refresh()
                            showCreateDialog = false
                            Toast.makeText(context, AppStrings.get("scripts_created", lang), Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(AppStrings.get("scripts_create", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(AppStrings.get("cancel", lang), color = LoonTextSecondary)
                }
            }
        )
    }

    if (showImportUrlDialog) {
        var urlName by remember { mutableStateOf("") }
        var scriptUrl by remember { mutableStateOf("") }
        var isDownloading by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isDownloading) showImportUrlDialog = false },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(AppStrings.get("scripts_import_url_title", lang), color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = urlName,
                        onValueChange = { urlName = it },
                        label = { Text(AppStrings.get("scripts_name_optional", lang)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = scriptUrl,
                        onValueChange = { scriptUrl = it },
                        label = { Text(AppStrings.get("scripts_url_label", lang)) },
                        placeholder = { Text("https://.../script.js") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LoonTextPrimary,
                            unfocusedTextColor = LoonTextPrimary,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (scriptUrl.isNotBlank()) {
                            isDownloading = true
                            scope.launch {
                                val res = ScriptManager.downloadScript(
                                    context = context,
                                    url = scriptUrl.trim(),
                                    name = urlName.trim(),
                                    pattern = "",
                                    type = "profile-modify"
                                )
                                isDownloading = false
                                if (res.isSuccess) {
                                    refresh()
                                    showImportUrlDialog = false
                                    Toast.makeText(context, AppStrings.get("scripts_downloaded", lang), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        AppStrings.get("scripts_download_failed", lang)
                                            .replace("{msg}", res.exceptionOrNull()?.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LoonBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isDownloading
                ) {
                    Text(
                        if (isDownloading) AppStrings.get("scripts_downloading", lang)
                        else AppStrings.get("scripts_import", lang),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportUrlDialog = false }, enabled = !isDownloading) {
                    Text(AppStrings.get("cancel", lang), color = LoonTextSecondary)
                }
            }
        )
    }

    if (editingScript != null) {
        val target = editingScript!!
        Dialog(onDismissRequest = { editingScript = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = LoonCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = AppStrings.get("scripts_edit_title", lang).replace("{name}", target.name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = LoonTextPrimary
                        )
                        IconButton(onClick = { editingScript = null }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = AppStrings.get("scripts_close", lang),
                                tint = LoonTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = scriptCodeInput,
                        onValueChange = { scriptCodeInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = LoonTextPrimary,
                            lineHeight = 17.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LoonBg,
                            unfocusedContainerColor = LoonBg,
                            focusedBorderColor = LoonBlue,
                            unfocusedBorderColor = LoonCardBorder
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { editingScript = null }) {
                            Text(AppStrings.get("cancel", lang), color = LoonTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                ScriptManager.updateScriptCode(context, target, scriptCodeInput)
                                refresh()
                                editingScript = null
                                Toast.makeText(context, AppStrings.get("scripts_saved", lang), Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LoonBlue),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(AppStrings.get("scripts_save_code", lang), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (deleteConfirmScript != null) {
        val target = deleteConfirmScript!!
        AlertDialog(
            onDismissRequest = { deleteConfirmScript = null },
            containerColor = LoonCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(AppStrings.get("scripts_delete_title", lang), color = LoonTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    AppStrings.get("scripts_delete_confirm", lang).replace("{name}", target.name),
                    color = LoonTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ScriptManager.deleteScript(context, target.id)
                        refresh()
                        deleteConfirmScript = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(AppStrings.get("scripts_confirm_delete", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmScript = null }) {
                    Text(AppStrings.get("cancel", lang), color = LoonTextSecondary)
                }
            }
        )
    }
}
