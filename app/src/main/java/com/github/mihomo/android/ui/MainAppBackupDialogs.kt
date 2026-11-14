@file:Suppress("InjectDispatcher")

package com.github.mihomo.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.mihomo.android.core.ClashCore
import com.github.mihomo.android.data.BackupManager
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.components.CupertinoAlertDialog
import com.github.mihomo.android.ui.components.CupertinoTextField
import com.github.mihomo.android.ui.components.CupertinoToast
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupImportRequest(
    val uri: Uri,
    val password: String?,
    val lang: String,
    val coreActive: Boolean
)

@Composable
internal fun MainAppBackupDialogs(
    state: MainAppState,
    vm: ProxiesViewModel,
    coreActive: Boolean,
    activity: Context,
    coroutineScope: CoroutineScope
) {
    val lang = LocalAppLanguage.current
    val dialogs = state.backupDialogs

    fun launchExport(uri: Uri, password: String?) {
        exportBackupAsync(
            scope = coroutineScope,
            context = activity,
            uri = uri,
            password = password,
            lang = lang
        )
    }

    fun launchImport(uri: Uri, password: String?) {
        importBackupAsync(
            scope = coroutineScope,
            context = activity,
            request = BackupImportRequest(
                uri = uri,
                password = password,
                lang = lang,
                coreActive = coreActive
            ),
            state = state,
            vm = vm
        )
    }

    if (dialogs.showPasswordDialog) {
        BackupExportPasswordDialog(
            dialogs = dialogs,
            lang = lang,
            onExportEncrypted = ::launchExport
        )
    }

    if (dialogs.showPlainConfirmDialog) {
        BackupPlainConfirmDialog(
            dialogs = dialogs,
            lang = lang,
            onExportPlain = { uri -> launchExport(uri, null) }
        )
    }

    if (dialogs.showImportPasswordDialog) {
        BackupImportPasswordDialog(
            dialogs = dialogs,
            lang = lang,
            onImportEncrypted = ::launchImport
        )
    }

    if (dialogs.importFailures.isNotEmpty()) {
        CupertinoAlertDialog(
            title = AppStrings.get("settings_backup_import", lang),
            message = dialogs.importFailures.joinToString("\n• ", prefix = "• "),
            confirmText = AppStrings.get("dialog_ok", lang),
            dismissText = null,
            onConfirm = { dialogs.importFailures = emptyList() },
            onDismissRequest = { dialogs.importFailures = emptyList() }
        )
    }
}

@Composable
private fun BackupExportPasswordDialog(
    dialogs: BackupDialogState,
    lang: String,
    onExportEncrypted: (Uri, String) -> Unit
) {
    CupertinoAlertDialog(
        title = AppStrings.get("backup_password_title", lang),
        message = AppStrings.get("backup_password_warning", lang),
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            dialogs.showPasswordDialog = false
            val uri = dialogs.pendingExportUri
            dialogs.pendingExportUri = null
            if (uri == null) return@CupertinoAlertDialog
            val password = dialogs.password
            dialogs.password = ""
            if (password.isBlank()) {
                dialogs.showPlainConfirmDialog = true
                dialogs.pendingExportUri = uri
            } else {
                onExportEncrypted(uri, password)
            }
        },
        onDismiss = {
            dialogs.showPasswordDialog = false
            dialogs.pendingExportUri = null
            dialogs.password = ""
        },
        onDismissRequest = {
            dialogs.showPasswordDialog = false
            dialogs.pendingExportUri = null
            dialogs.password = ""
        }
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = AppStrings.get("backup_password_hint", lang),
            color = LoonTextSecondary,
            style = LoonTextStyles.subtitle()
        )
        Spacer(modifier = Modifier.height(LoonSpacing.sm))
        CupertinoTextField(
            value = dialogs.password,
            onValueChange = { value -> dialogs.password = value },
            placeholder = AppStrings.get("backup_password_hint", lang),
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

@Composable
private fun BackupPlainConfirmDialog(
    dialogs: BackupDialogState,
    lang: String,
    onExportPlain: (Uri) -> Unit
) {
    CupertinoAlertDialog(
        title = AppStrings.get("backup_password_title", lang),
        message = AppStrings.get("backup_password_plain_warning", lang),
        confirmText = AppStrings.get("action_save", lang),
        dismissText = AppStrings.get("cancel", lang),
        isDestructive = true,
        onConfirm = {
            dialogs.showPlainConfirmDialog = false
            val uri = dialogs.pendingExportUri
            dialogs.pendingExportUri = null
            if (uri != null) onExportPlain(uri)
        },
        onDismiss = {
            dialogs.showPlainConfirmDialog = false
            dialogs.pendingExportUri = null
        },
        onDismissRequest = {
            dialogs.showPlainConfirmDialog = false
            dialogs.pendingExportUri = null
        }
    )
}

@Composable
private fun BackupImportPasswordDialog(
    dialogs: BackupDialogState,
    lang: String,
    onImportEncrypted: (Uri, String) -> Unit
) {
    CupertinoAlertDialog(
        title = AppStrings.get("backup_password_import_title", lang),
        message = AppStrings.get("backup_password_import_hint", lang),
        confirmText = AppStrings.get("action_ok", lang),
        dismissText = AppStrings.get("cancel", lang),
        onConfirm = {
            dialogs.showImportPasswordDialog = false
            val uri = dialogs.pendingImportUri
            dialogs.pendingImportUri = null
            val password = dialogs.password
            dialogs.password = ""
            if (uri != null) onImportEncrypted(uri, password)
        },
        onDismiss = {
            dialogs.showImportPasswordDialog = false
            dialogs.pendingImportUri = null
            dialogs.password = ""
        },
        onDismissRequest = {
            dialogs.showImportPasswordDialog = false
            dialogs.pendingImportUri = null
            dialogs.password = ""
        }
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        CupertinoTextField(
            value = dialogs.password,
            onValueChange = { value -> dialogs.password = value },
            placeholder = AppStrings.get("backup_password_import_hint", lang),
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

internal fun exportBackupAsync(
    scope: CoroutineScope,
    context: Context,
    uri: Uri,
    password: String?,
    lang: String
) {
    scope.launch(Dispatchers.IO) {
        try {
            val inner = context.contentResolver.openOutputStream(uri)?.use { output ->
                BackupManager.exportBackup(context, output, password)
            } ?: error(AppStrings.get("backup_write_failed", lang))
            inner.getOrThrow()
            withContext(Dispatchers.Main) {
                CupertinoToast.show(AppStrings.get("settings_backup_done", lang))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                CupertinoToast.show(
                    AppStrings.get("settings_backup_failed", lang)
                        .replace("{reason}", e.message ?: AppStrings.get("unknown_error", lang)),
                    isDestructive = true
                )
            }
        }
    }
}

internal fun importBackupAsync(
    scope: CoroutineScope,
    context: Context,
    request: BackupImportRequest,
    state: MainAppState,
    vm: ProxiesViewModel
) {
    scope.launch(Dispatchers.IO) {
        try {
            val inner = context.contentResolver.openInputStream(request.uri)?.use { input ->
                BackupManager.importBackupDetailed(
                    context,
                    input,
                    AppStrings.get("backup_too_large", request.lang),
                    request.password
                )
            } ?: error(AppStrings.get("backup_read_failed", request.lang))
            val importRes = inner.getOrThrow()
            val refreshedLanPassword = state.settingsManager.allowLanPassword
            withContext(Dispatchers.Main) {
                state.reloadNonSensitiveSettings()
                state.allowLanPassword = refreshedLanPassword
                state.lanPasswordLoaded = true
                vm.reloadProfiles()
                if (request.coreActive) {
                    scope.launch(Dispatchers.IO) {
                        ClashCore.reloadActiveConfig(context)
                        vm.refreshProxiesOnline()
                    }
                }
                if (importRes.failures.isNotEmpty()) {
                    state.backupDialogs.importFailures = importRes.failures
                }
                CupertinoToast.show(
                    AppStrings.get("settings_backup_restored", request.lang)
                        .replace("{n}", importRes.imported.toString())
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                CupertinoToast.show(
                    AppStrings.get("settings_backup_failed", request.lang)
                        .replace("{reason}", e.message ?: AppStrings.get("unknown_error", request.lang)),
                    isDestructive = true
                )
            }
        }
    }
}
