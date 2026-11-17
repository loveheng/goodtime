package app.shiguang.shiguang.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.shiguang.core.tokens.StScale

/**
 * 原语卡片（P4①-c）——内建：① 圆角 16dp（StScale.radiusCard）；② **margin 显式**：
 * 本组件不内置任何外边距（Flutter Card 默认 margin 4 对照，丢失点 #5——禁隐式依赖，
 * 调用方以 inset token 传 padding）；③ 容器色走 M3 scheme 语义（色彩即语义，§1.3）。
 */
@Composable
fun ShiguangCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(StScale.radiusCard.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            content = content,
        )
    }
}
