package app.shiguang.shiguang.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 原语浮层（P4①-c）——内建（丢失点 #6）：① `sheetMaxWidth = Dp.Infinity`——Compose
 * 默认 640dp 与移动端全宽不符，显式放开；② `imePadding` 统一内建——含输入 sheet 的
 * 键盘避让（快记抽屉 Z40 键盘吸附语义；WindowInsets.ime 联动由 M3 内部处理）；
 * ③ 返回键逐层消费由 M3 ModalBottomSheet 内建 predictive back 承载（丢失点 #8 浮层面；
 * 屏幕边缘 24dp 手势保留区属页面级手势竞技场，随 BlockRenderer 手势轮落地）。
 * 浮层 Z 序（ui-design §3.2）：sheet Z30 / 快记抽屉 Z40；分级确认卡=内联件不进浮层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiguangBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.imePadding(),
        sheetState = sheetState,
        sheetMaxWidth = Dp.Infinity,
        containerColor = containerColor,
        content = content,
    )
}
