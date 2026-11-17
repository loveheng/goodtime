package app.shiguang.shiguang.ui.schedule

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.shiguang.core.tokens.StScale
import androidx.compose.ui.unit.dp

/**
 * 否决原因可选输入（ui-pages §3.4 拍板回归；§D 转正清单）：弹框留空=不带原因
 * 直接否决；取消=null（不动）；非空=带原因（RejectBlock.reason，AI 下轮排程参考）。
 */
@Composable
fun RejectReasonDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .focusRequester(focus)
                    .padding(bottom = StScale.insetSm.dp),
                maxLines = 2,
                placeholder = { Text("为什么不想做？（可选，AI 下轮排程会参考）") },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }) { Text("否决") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
