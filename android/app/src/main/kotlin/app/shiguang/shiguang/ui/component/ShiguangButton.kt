package app.shiguang.shiguang.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.shiguang.core.tokens.StScale

/**
 * 原语按钮（P4①-c）——内建：① 48dp 最小触控区显式（丢失点 #5，M3
 * minimumInteractiveComponentEnforcement 强制开启）；② 圆角取 :core:tokens
 * （胶囊 999 = stadium，语义禁他用）。间距/排印由调用方取 token（零魔法值）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiguangButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(StScale.radiusCapsule.dp),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides true) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            content = content,
        )
    }
}
