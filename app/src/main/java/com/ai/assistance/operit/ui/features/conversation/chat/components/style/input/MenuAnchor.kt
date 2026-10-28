package com.ai.assistance.operit.ui.features.conversation.chat.components.style.input

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 输入区上方的弹层需要以输入区顶边为基准定位，否则固定内边距会压住输入框。
 * 传入输入区在窗口中的顶边 y（像素），返回弹层底边应留出的内边距。
 */
@Composable
fun menuBottomPadding(anchorTopInWindowPx: Float): Dp {
    if (anchorTopInWindowPx.isNaN()) return 44.dp
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val insetPx = windowHeightPx - anchorTopInWindowPx
    if (insetPx <= 0f) return 44.dp
    return with(density) { insetPx.toDp() }
}
