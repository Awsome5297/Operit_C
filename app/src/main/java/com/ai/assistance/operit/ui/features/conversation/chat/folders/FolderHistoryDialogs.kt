package com.ai.assistance.operit.ui.features.conversation.chat.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.chat.folders.ChatFolder
import com.ai.assistance.operit.data.chat.folders.ChatFolderBinding
import com.ai.assistance.operit.data.chat.folders.ChatFolderNode
import com.ai.assistance.operit.data.chat.folders.canMoveFolder
import com.ai.assistance.operit.data.chat.folders.folderAncestors
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.ui.features.conversation.chat.viewmodel.ChatHistoryDisplayMode

internal data class HistoryAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val action: () -> Unit,
)
internal data class HistoryBindingOption(val label: String, val binding: ChatFolderBinding)

/**
 * 紧凑浮层菜单，锚定在触发按钮旁，避免居中弹窗脱离点击位置。
 * 删除类操作以错误色单独分隔。
 */
@Composable
internal fun HistoryActionsMenu(anchor: Rect, actions: List<HistoryAction>, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val positionProvider =
        remember(anchor, density) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val margin = with(density) { 8.dp.roundToPx() }
                    val anchorLeft = anchor.left.roundToInt()
                    val anchorRight = anchor.right.roundToInt()
                    val anchorTop = anchor.top.roundToInt()
                    val anchorBottom = anchor.bottom.roundToInt()
                    // 菜单左边缘接在触发按钮右侧；右侧空间不足时翻到按钮左侧。
                    val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                    val x =
                        if (anchorRight + margin + popupContentSize.width <= windowSize.width - margin) {
                            anchorRight + margin
                        } else {
                            (anchorLeft - margin - popupContentSize.width).coerceIn(margin, maxX)
                        }

                    val spaceBelow = windowSize.height - anchorBottom - margin
                    val fitsBelow = spaceBelow >= popupContentSize.height
                    val above = anchorTop - popupContentSize.height - margin
                    val y =
                        when {
                            fitsBelow -> anchorBottom + margin
                            above >= margin -> above
                            else -> (anchorBottom + margin)
                                .coerceAtMost(windowSize.height - popupContentSize.height - margin)
                        }
                    return IntOffset(x, y.coerceAtLeast(margin))
                }
            }
        }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.width(240.dp),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                var separated = false
                actions.forEach { option ->
                    val isDestructive = option.destructive
                    if (isDestructive && !separated) {
                        separated = true
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    val contentColor =
                        when {
                            !option.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            isDestructive -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = option.enabled, onClick = option.action)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(option.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option.label,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderNameDialog(
    title: String, initialName: String, location: String, onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    // AlertDialog 使用窗口居中布局，创建子文件夹也显示完整创建位置。
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.folder_creation_location), style = MaterialTheme.typography.labelMedium)
                Text(location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(if (initialName.isEmpty()) R.string.create else R.string.save))
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun HistoryChatEditDialog(
    chat: ChatHistory, options: List<HistoryBindingOption>, bindingLabel: (ChatFolderBinding) -> String,
    onDismiss: () -> Unit, onSave: (String, ChatFolderBinding) -> Unit,
) {
    var title by remember(chat.id) { mutableStateOf(chat.title) }
    var binding by remember(chat.id) { mutableStateOf(ChatFolderBinding(chat.characterCardName, chat.characterGroupId)) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.new_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedTextField(bindingLabel(binding), {}, readOnly = true,
                        label = { Text(stringResource(R.string.bind_character_card)) }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.matchParentSize().clickable { expanded = true })
                    DropdownMenu(expanded, { expanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) },
                                onClick = { binding = option.binding; expanded = false })
                        }
                    }
                }
                Text(stringResource(R.string.chat_binding_scope_hint), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(title.trim(), binding) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.save)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HistorySettingChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FolderHistorySettingsDialog(
    displayMode: ChatHistoryDisplayMode, onDisplayMode: (ChatHistoryDisplayMode) -> Unit,
    sort: ChatHistorySortMode, onSort: (ChatHistorySortMode) -> Unit,
    autoCharacter: Boolean, onAutoCharacter: (Boolean) -> Unit, autoChat: Boolean, onAutoChat: (Boolean) -> Unit,
    onExpandAll: () -> Unit, onCollapseAll: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.chat_history_settings)) },
        text = {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
                .verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chat_display_mode), style = MaterialTheme.typography.labelLarge)
                listOf(
                    Triple(ChatHistoryDisplayMode.BY_CHARACTER_CARD, R.string.history_filter_role_card, R.string.history_nested_role_desc),
                    Triple(ChatHistoryDisplayMode.BY_FOLDER, R.string.history_filter_folder, R.string.history_nested_folder_desc),
                    Triple(ChatHistoryDisplayMode.CURRENT_CHARACTER_ONLY, R.string.history_filter_current_card, R.string.history_nested_current_desc),
                ).forEach { (mode, title, description) ->
                    HistorySettingChoice(stringResource(title), stringResource(description), displayMode == mode) { onDisplayMode(mode) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.history_sort), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                HistorySettingChoice(stringResource(R.string.history_sort_default), stringResource(R.string.history_sort_default_desc),
                    sort == ChatHistorySortMode.DEFAULT) { onSort(ChatHistorySortMode.DEFAULT) }
                HistorySettingChoice(stringResource(R.string.history_sort_recent), stringResource(R.string.history_sort_recent_desc),
                    sort == ChatHistorySortMode.RECENT) { onSort(ChatHistorySortMode.RECENT) }
                Text(stringResource(R.string.history_sort_drag_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onExpandAll) { Text(stringResource(R.string.folder_expand_all)) }
                    TextButton(onClick = onCollapseAll) { Text(stringResource(R.string.folder_collapse_all)) }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(stringResource(R.string.history_auto_switch_character), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.history_auto_switch_character_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(autoCharacter, onAutoCharacter)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(stringResource(R.string.history_auto_switch_chat), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.history_auto_switch_chat_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(autoChat, onAutoChat)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
