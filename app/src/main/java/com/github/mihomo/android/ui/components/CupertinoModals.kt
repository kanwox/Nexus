package com.github.mihomo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.mihomo.android.i18n.AppStrings
import com.github.mihomo.android.i18n.LocalAppLanguage
import com.github.mihomo.android.ui.theme.LocalWindowWidthClass
import com.github.mihomo.android.ui.theme.LoonCard
import com.github.mihomo.android.ui.theme.LoonCardBorder
import com.github.mihomo.android.ui.theme.LoonDangerText
import com.github.mihomo.android.ui.theme.LoonInfoText
import com.github.mihomo.android.ui.theme.LoonRadii
import com.github.mihomo.android.ui.theme.LoonSeparator
import com.github.mihomo.android.ui.theme.LoonSizes
import com.github.mihomo.android.ui.theme.LoonSpacing
import com.github.mihomo.android.ui.theme.LoonTextMuted
import com.github.mihomo.android.ui.theme.LoonTextPrimary
import com.github.mihomo.android.ui.theme.LoonTextSecondary
import com.github.mihomo.android.ui.theme.LoonTextStyles
import com.github.mihomo.android.ui.theme.RoundedCornerShape
import com.github.mihomo.android.ui.theme.WindowWidthClass

// =============================================================================
// Cupertino Action Sheet, Alert Dialog & Modal Bottom Sheets
// =============================================================================

data class CupertinoActionSheetItem(
    val text: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

data class CupertinoSingleSelectOption<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CupertinoActionSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String? = null,
    actions: List<CupertinoActionSheetItem>,
    cancelText: String = AppStrings.get("cancel", LocalAppLanguage.current)
) {
    val haptic = rememberIosHaptic()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxWidth = if (LocalWindowWidthClass.current == WindowWidthClass.COMPACT) 520.dp else 480.dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = LoonTextPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(LoonRadii.card),
        dragHandle = null
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
                    .semantics { paneTitle = title ?: cancelText },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionSheetOptionsCard(
                    title = title,
                    message = message,
                    actions = actions,
                    haptic = haptic,
                    onDismissRequest = onDismissRequest
                )
                ActionSheetCancelCard(
                    cancelText = cancelText,
                    haptic = haptic,
                    onDismissRequest = onDismissRequest
                )
            }
        }
    }
}

@Composable
private fun ActionSheetOptionsCard(
    title: String?,
    message: String?,
    actions: List<CupertinoActionSheetItem>,
    haptic: IosHapticHelper,
    onDismissRequest: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(LoonRadii.card),
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!title.isNullOrBlank() || !message.isNullOrBlank()) {
                ActionSheetHeader(title = title, message = message)
                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
            }

            actions.forEachIndexed { index, action ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .iosHighlightPressable {
                            haptic.selection()
                            onDismissRequest()
                            action.onClick()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = action.text,
                        style = LoonTextStyles.actionLabel(),
                        color = if (action.isDestructive) LoonDangerText else LoonInfoText,
                        textAlign = TextAlign.Center
                    )
                }
                if (index < actions.lastIndex) {
                    HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun ActionSheetHeader(title: String?, message: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = LoonTextStyles.actionSheetTitle(),
                color = LoonTextSecondary,
                textAlign = TextAlign.Center
            )
        }
        if (!message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = LoonTextStyles.caption(),
                color = LoonTextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionSheetCancelCard(
    cancelText: String,
    haptic: IosHapticHelper,
    onDismissRequest: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(LoonRadii.card),
        color = LoonCard,
        border = BorderStroke(0.5.dp, LoonCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .iosHighlightPressable {
                haptic.lightTick()
                onDismissRequest()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cancelText,
                style = LoonTextStyles.actionLabelStrong(),
                color = LoonInfoText,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =============================================================================
// Authentic Cupertino Alert & Confirm Dialog (270dp-400dp Responsive Modal Alert)
// =============================================================================

@Composable
fun CupertinoAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    message: String? = null,
    confirmText: String = AppStrings.get("confirm", LocalAppLanguage.current),
    dismissText: String? = AppStrings.get("cancel", LocalAppLanguage.current),
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = onDismissRequest,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val haptic = rememberIosHaptic()
    val maxWidth = if (LocalWindowWidthClass.current == WindowWidthClass.COMPACT) 310.dp else 400.dp

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(LoonRadii.dialog),
            color = LoonCard,
            border = BorderStroke(0.5.dp, LoonCardBorder),
            modifier = Modifier
                .widthIn(min = 270.dp, max = maxWidth)
                .fillMaxWidth(0.85f)
                .semantics { paneTitle = title }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlertDialogBody(title = title, message = message, content = content)
                HorizontalDivider(color = LoonSeparator, thickness = 0.5.dp)
                AlertDialogButtons(
                    confirmText = confirmText,
                    dismissText = dismissText,
                    isDestructive = isDestructive,
                    haptic = haptic,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun AlertDialogBody(
    title: String,
    message: String?,
    content: (@Composable ColumnScope.() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = LoonSpacing.xl,
                bottom = if (content != null) LoonSpacing.md else 18.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = LoonTextPrimary,
            style = LoonTextStyles.dialogTitle(),
            textAlign = TextAlign.Center
        )

        if (!message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(LoonSpacing.xs))
            Text(
                text = message,
                color = LoonTextSecondary,
                style = LoonTextStyles.dialogMessage(),
                textAlign = TextAlign.Center
            )
        }

        if (content != null) {
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AlertDialogButtons(
    confirmText: String,
    dismissText: String?,
    isDestructive: Boolean,
    haptic: IosHapticHelper,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    val confirmColor = if (isDestructive) LoonDangerText else LoonInfoText
    if (dismissText != null && onDismiss != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = LoonSizes.touchTargetMin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlertDialogButton(
                text = dismissText,
                color = LoonInfoText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = {
                    haptic.lightTick()
                    onDismiss()
                }
            )
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .fillMaxHeight()
                    .background(LoonSeparator)
            )
            AlertDialogButton(
                text = confirmText,
                color = confirmColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onClick = {
                    haptic.lightTick()
                    onConfirm()
                }
            )
        }
    } else {
        AlertDialogButton(
            text = confirmText,
            color = confirmColor,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                haptic.lightTick()
                onConfirm()
            }
        )
    }
}

@Composable
private fun AlertDialogButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .heightIn(min = LoonSizes.touchTargetMin)
            .iosHighlightPressable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            style = LoonTextStyles.button(),
            textAlign = TextAlign.Center
        )
    }
}

// =============================================================================
// Authentic Cupertino Modal Bottom Sheet & Shared Single-Select Picker
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CupertinoModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    title: String? = null,
    doneText: String? = AppStrings.get("action_done", LocalAppLanguage.current),
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxWidth = if (LocalWindowWidthClass.current == WindowWidthClass.COMPACT) 640.dp else 600.dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = LoonCard,
        shape = RoundedCornerShape(topStart = LoonRadii.sheet, topEnd = LoonRadii.sheet),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.5.dp)
                    .clip(CircleShape)
                    .background(LoonTextMuted.copy(alpha = 0.28f))
            )
        }
    ) {
        Column(
            modifier = modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
                .semantics { if (title != null) paneTitle = title }
        ) {
            val hasHeader = listOf(title, doneText, leadingAction, trailingAction).any { item -> item != null }
            if (hasHeader) {
                ModalSheetHeader(
                    title = title,
                    doneText = doneText,
                    leadingAction = leadingAction,
                    trailingAction = trailingAction,
                    onDone = onDismissRequest
                )
            }
            content()
        }
    }
}

@Composable
private fun ModalSheetHeader(
    title: String?,
    doneText: String?,
    leadingAction: (@Composable () -> Unit)?,
    trailingAction: (@Composable () -> Unit)?,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LoonSizes.touchTargetMin)
            .padding(horizontal = LoonSpacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.widthIn(min = LoonSizes.touchTargetMin),
            contentAlignment = Alignment.CenterStart
        ) {
            leadingAction?.invoke()
        }

        if (title != null) {
            Text(
                text = title,
                style = LoonTextStyles.navTitle(),
                color = LoonTextPrimary,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier.widthIn(min = LoonSizes.touchTargetMin),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (trailingAction != null) {
                trailingAction()
            } else if (doneText != null) {
                val haptic = rememberIosHaptic()
                Box(
                    modifier = Modifier
                        .height(LoonSizes.touchTargetMin)
                        .clip(RoundedCornerShape(LoonRadii.control))
                        .iosFocusRing(RoundedCornerShape(LoonRadii.control))
                        .iosPressable {
                            haptic.lightTick()
                            onDone()
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = doneText,
                        color = LoonInfoText,
                        style = LoonTextStyles.button()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CupertinoSingleSelectSheet(
    title: String,
    options: List<CupertinoSingleSelectOption<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberIosHaptic()
    CupertinoModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option.value == selectedValue
                SingleSelectOptionRow(
                    option = option,
                    isSelected = isSelected,
                    onClick = {
                        haptic.selection()
                        onSelect(option.value)
                        onDismissRequest()
                    }
                )
                if (index < options.lastIndex) {
                    CupertinoDivider()
                }
            }
        }
    }
}

@Composable
private fun <T> SingleSelectOptionRow(
    option: CupertinoSingleSelectOption<T>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .iosFocusRing(RoundedCornerShape(LoonRadii.control))
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                color = if (isSelected) LoonInfoText else LoonTextPrimary,
                style = LoonTextStyles.cellTitle()
            )
            if (!option.subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(LoonSpacing.xxs))
                Text(
                    text = option.subtitle,
                    color = LoonTextSecondary,
                    style = LoonTextStyles.subtitle()
                )
            }
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(LoonSpacing.md))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = LoonInfoText,
                modifier = Modifier.size(LoonSizes.icon)
            )
        }
    }
}
