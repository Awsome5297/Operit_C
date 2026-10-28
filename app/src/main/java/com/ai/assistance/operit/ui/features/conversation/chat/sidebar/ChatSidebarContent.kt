package com.ai.assistance.operit.ui.features.conversation.chat.sidebar

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.features.conversation.chat.components.CharacterSelectorPanel
import com.ai.assistance.operit.ui.features.conversation.chat.components.CharacterSelectorTarget
import com.ai.assistance.operit.ui.features.conversation.chat.folders.FolderChatHistoryPanel
import com.ai.assistance.operit.ui.main.components.NavigationDrawerAppearance
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.screens.GestureStateHolder
import com.ai.assistance.operit.ui.theme.getTextColorForBackground
import kotlinx.coroutines.flow.flowOf

/** 角色和历史优先，插件使用可折叠的大按钮区域，底部保持固定。 */
@Composable
fun ChatSidebarContent(
    modifier: Modifier = Modifier,
    brandName: String,
    appearance: NavigationDrawerAppearance,
    pluginEntries: List<NavigationEntrySpec>,
    selectedItem: NavItem?,
    selectedRouteId: String,
    permissionBadgeText: String,
    onNavItemClick: (NavItem) -> Unit,
    onNavigationEntryClick: (NavigationEntrySpec) -> Unit,
    onOpenCharacterSettings: () -> Unit,
    onCloseSidebar: () -> Unit,
    onRunChatAction: (() -> Unit) -> Unit,
    onCreateChat: (() -> Unit) -> Unit,
    onSelectCharacter: (CharacterSelectorTarget) -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = LocalMainChatViewModel.current
    val state = LocalMainChatSidebarState.current
    val currentChatId by viewModel.currentChatId.collectAsState()
    val deletionProgress by viewModel.chatListMotion.deletions.collectAsState()
    val batchProgress by viewModel.chatBatchProgress.collectAsState()
    val streamingIds by viewModel.activeStreamingChatIds.collectAsState()
    val searchQuery by viewModel.chatHistorySearchQuery.collectAsState()
    val userPreferences = remember(context) { UserPreferencesManager.getInstance(context) }
    val cardId = state.activeCharacterCard?.id
    val groupId = state.activeCharacterGroup?.id
    val cardAvatar by remember(cardId) {
        if (cardId == null) flowOf(null) else userPreferences.getAiAvatarForCharacterCardFlow(cardId)
    }.collectAsState(initial = null)
    val groupAvatar by remember(groupId) {
        if (groupId == null) flowOf(null) else userPreferences.getAiAvatarForCharacterGroupFlow(groupId)
    }.collectAsState(initial = null)
    val memberCardId = state.activeCharacterGroup?.members?.minByOrNull { it.orderIndex }?.characterCardId
    val memberAvatar by remember(memberCardId) {
        if (memberCardId == null) flowOf(null) else userPreferences.getAiAvatarForCharacterCardFlow(memberCardId)
    }.collectAsState(initial = null)
    val avatar = when (state.activePrompt) {
        is ActivePrompt.CharacterCard -> cardAvatar
        is ActivePrompt.CharacterGroup -> groupAvatar ?: memberAvatar
    }
    val characterName = when (state.activePrompt) {
        is ActivePrompt.CharacterCard -> state.activeCharacterCard?.name.orEmpty()
        is ActivePrompt.CharacterGroup -> state.activeCharacterGroup?.name.orEmpty()
    }
    val textColor = getTextColorForBackground(appearance.containerColor)
    var showCharacterSelector by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { GestureStateHolder.isChatScreenGestureConsumed = false }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(brandName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = appearance.titleColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onCloseSidebar, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.app_content_collapse_sidebar),
                    tint = appearance.itemColor, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SidebarNavigationItem(Modifier.weight(1f), Icons.Default.AdminPanelSettings,
                    stringResource(R.string.sidebar_permission_short), appearance,
                    selected = selectedItem == NavItem.ShizukuCommands, detail = permissionBadgeText,
                    onClick = { onNavItemClick(NavItem.ShizukuCommands) })
                SidebarNavigationItem(Modifier.weight(1f), NavItem.Workflow.icon,
                    stringResource(NavItem.Workflow.titleResId), appearance,
                    selected = selectedItem == NavItem.Workflow, detail = stringResource(R.string.sidebar_workflow_description),
                    onClick = { onNavItemClick(NavItem.Workflow) })
        }
        var characterAnchor by remember { mutableStateOf<Rect?>(null) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(44.dp)
                .onGloballyPositioned { characterAnchor = it.boundsInWindow() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showCharacterSelector = true }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatar.isNullOrBlank()) {
                    Surface(Modifier.size(28.dp), shape = CircleShape, color = appearance.buttonContainerColor) {
                        Icon(Icons.Default.Person, null, tint = textColor, modifier = Modifier.padding(5.dp))
                    }
                } else {
                    Image(rememberAsyncImagePainter(avatar), contentDescription = null,
                        modifier = Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                Text(characterName, Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = appearance.itemColor, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onOpenCharacterSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Settings, stringResource(R.string.sidebar_character_settings),
                    tint = appearance.itemColor, modifier = Modifier.size(18.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pluginListMaxHeight = maxHeight * 0.3f
            Column(Modifier.fillMaxSize()) {
                FolderChatHistoryPanel(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onNewChat = { cardName, group, folder -> onCreateChat {
                        viewModel.showChatPage()
                        viewModel.createNewChat(cardName, group, folder, inheritGroupFromCurrent = false)
                    } },
                    onSelectChat = { id -> onRunChatAction { viewModel.showChatPage(); viewModel.switchChat(id) } },
                    onDeleteChat = viewModel::deleteChatHistory,
                    onUpdateChatTitle = viewModel::updateChatTitle,
                    onSummarizeChatTitle = viewModel::summarizeChatTitle,
                    onUpdateChatBinding = viewModel::updateChatCharacterBinding,
                    chatHistories = state.displayedChatHistories,
                    currentId = currentChatId,
                    activeStreamingChatIds = streamingIds,
                    lazyListState = null,
                    onBack = null,
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::onChatHistorySearchQueryChange,
                    historyDisplayMode = state.historyDisplayMode,
                    onDisplayModeChange = state.onDisplayModeChange,
                    autoSwitchCharacterCard = state.autoSwitchCharacterCard,
                    onAutoSwitchCharacterCardChange = state.onAutoSwitchCharacterCardChange,
                    autoSwitchChatOnCharacterSelect = state.autoSwitchChatOnCharacterSelect,
                    onAutoSwitchChatOnCharacterSelectChange = state.onAutoSwitchChatOnCharacterSelectChange,
                    onQuickScrollInteractionChange = { GestureStateHolder.isChatScreenGestureConsumed = it },
                    activePrompt = state.activePrompt,
                    embeddedInSidebar = true,
                    deletionProgress = deletionProgress,
                    batchProgress = batchProgress,
                    onRunBatch = viewModel::runChatBatch,
                    contentColor = textColor,
                    selectedContainerColor = appearance.selectedContainerColor,
                    selectedContentColor = getTextColorForBackground(appearance.selectedContainerColor),
                )
                SidebarPluginSection(
                    entries = pluginEntries,
                    selectedRouteId = selectedRouteId,
                    appearance = appearance,
                    maxListHeight = pluginListMaxHeight,
                    onEntryClick = onNavigationEntryClick,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        bottomContent()
    }
    val anchor = characterAnchor
    if (showCharacterSelector && anchor != null) {
        CharacterSelectorPanel(
            anchor = anchor,
            onDismiss = { showCharacterSelector = false },
            onSelectCharacter = onSelectCharacter,
            onOpenCharacterSettings = { showCharacterSelector = false; onOpenCharacterSettings() },
        )
    }
}

@Composable
private fun SidebarNavigationItem(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    appearance: NavigationDrawerAppearance,
    selected: Boolean = false,
    detail: String? = null,
    onClick: () -> Unit,
) {
    val color = if (selected) appearance.selectedContentColor else appearance.itemColor
    SidebarButtonSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        appearance = appearance,
        containerColor = if (selected) appearance.selectedContainerColor
            else if (appearance.buttonLiquidGlassEnabled) appearance.buttonContainerColor else Color.Transparent,
        contentColor = color,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
