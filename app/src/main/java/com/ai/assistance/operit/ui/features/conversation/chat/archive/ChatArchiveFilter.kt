package com.ai.assistance.operit.ui.features.conversation.chat.archive

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

/**
 * 侧栏与聊天选择器共用的归档入口。
 * 选中态由单个滑块承担：滑块只用一个无阴影的纯色层绘制，避免切换时旧选中项阴影先消失再出现。
 */
@Composable
internal fun ChatArchiveFilter(
    archived: Boolean,
    archivedCount: Int,
    onChange: (Boolean) -> Unit,
    contentColor: Color,
    selectedContainerColor: Color,
    selectedContentColor: Color,
) {
    val labels =
        listOf(
            stringResource(R.string.chat_history),
            stringResource(R.string.chat_archived_count, archivedCount),
        )
    val selectedIndex = if (archived) 1 else 0
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(40.dp)
                .clip(shape)
                .background(contentColor.copy(alpha = 0.06f)),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            val segmentWidth = maxWidth / labels.size
            val thumbOffset by
                animateDpAsState(
                    targetValue = segmentWidth * selectedIndex,
                    animationSpec = tween(durationMillis = 220),
                    label = "archiveThumb",
                )
            // 滑块不设 elevation/shadow：阴影会让动画中的位置与绘制分离，出现闪烁。
            Box(
                modifier =
                    Modifier
                        .offset(x = thumbOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(selectedContainerColor),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onChange(index == 1) },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) selectedContentColor else contentColor,
                    )
                }
            }
        }
    }
}
