package app.shiguang.shiguang.ui.mcp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.service.RelayKeys
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * MCP 服务设置页（behavior/mcp_settings.md §A 转正清单，D23 2026-11-15）：隐藏入口
 * （设置页长按「关于」→调试菜单）；服务开关（运行态圆点）/连接地址（回环）/访问
 * 令牌/relay 区。仅内部调试用——生产通道走 Binder 宿主进程内嵌入不经本页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsPage(modifier: Modifier = Modifier) {
    val vm: McpViewModel = viewModel(factory = McpViewModel.factory)
    val running by vm.running.collectAsState()
    val token by vm.token.collectAsState()
    val relay by vm.relay.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val palette = LocalStColors.current

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            snackbarHostState.showShiguangSnackbar(e.message, ShiguangSnackbarDuration.fromMs(e.durationMs))
        }
    }

    fun copyFeedback(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch {
            snackbarHostState.showShiguangSnackbar("已复制", ShiguangSnackbarDuration.SEC1)
        }
    }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("MCP 服务") }) },
        content = { padding ->
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(StScale.insetLg.dp),
            ) {
                // 服务开关：10dp 圆点运行态（绿 0xFF81C784/停止 safelineOff）+「运行中/已停止」
                item {
                    ListItem(
                        headlineContent = {
                            Row {
                                Canvas(Modifier.size(StUi.mcpStatusDotDp.dp)) {
                                    drawCircle(
                                        color = if (running) Color(0xFF81C784) else Color(palette.safelineOff),
                                    )
                                }
                                Spacer(Modifier.width(StScale.insetSm.dp))
                                Text(if (running) "运行中" else "已停止")
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = running,
                                onCheckedChange = { vm.setRunning(it) },
                                modifier = Modifier.semantics { contentDescription = "MCP 服务开关" },
                            )
                        },
                    )
                }
                item {
                    // 连接地址（回环常量）+复制 1s
                    ListItem(
                        headlineContent = { Text("连接地址") },
                        supportingContent = { Text(vm.endpoint) },
                        trailingContent = {
                            IconButton(onClick = { copyFeedback(vm.endpoint) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制",
                                    modifier = Modifier.size(StUi.mcpCopyIconDp.dp),
                                )
                            }
                        },
                    )
                }
                item {
                    // 访问令牌：复制（token null 禁用）+重新生成
                    val tk = token
                    ListItem(
                        headlineContent = { Text("访问令牌") },
                        supportingContent = { Text(tk ?: "…") },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { tk?.let { copyFeedback(it) } },
                                    enabled = tk != null,
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "复制",
                                        modifier = Modifier.size(StUi.mcpCopyIconDp.dp),
                                    )
                                }
                                TextButton(onClick = { vm.regenerateToken() }) { Text("重新生成") }
                            }
                        },
                    )
                    Spacer(Modifier.height(StScale.insetSm.dp))
                    Text(
                        "仅内部调试：服务只监听本机回环，不暴露局域网。桌面 AI 先执行 adb reverse tcp:8765 tcp:8765，再用上方地址接入。",
                        fontSize = StUi.plansMetaTextSp.sp,
                        color = Color(palette.textSecondary),
                    )
                    Spacer(Modifier.height(StScale.insetLg.dp))
                }
                if (relay.loaded) {
                    // relay 区：四键 KV 回填；开关即存；三域 onSubmitted 保存（endpoint/username
                    // trim、password 原文——D23 过目照抄）
                    item {
                        ListItem(
                            headlineContent = { Text("relay 中转（外网可达）") },
                            supportingContent = { Text("需与桌面端互为 relay 好友；保存后下次打开服务生效") },
                            trailingContent = {
                                Switch(checked = relay.enabled, onCheckedChange = { vm.setRelayEnabled(it) })
                            },
                        )
                        if (relay.enabled) {
                            Spacer(Modifier.height(StScale.insetSm.dp))
                            RelayField(
                                label = "relay 服务器地址",
                                hint = "https://relay.example.com",
                                initial = relay.endpoint,
                                onDone = { vm.saveRelay(RelayKeys.ENDPOINT, it) },
                            )
                            Spacer(Modifier.height(StScale.insetSm.dp))
                            RelayField(
                                label = "relay 用户名",
                                initial = relay.username,
                                onDone = { vm.saveRelay(RelayKeys.USERNAME, it) },
                            )
                            Spacer(Modifier.height(StScale.insetSm.dp))
                            RelayField(
                                label = "relay 密码",
                                initial = relay.password,
                                obscure = true,
                                onDone = { vm.saveRelay(RelayKeys.PASSWORD, it) },
                            )
                            Spacer(Modifier.height(StScale.insetSm.dp))
                            Text(
                                "MCP 请求门禁：relay 好友 ACL + 本页访问令牌双保险；桌面端用 relay-bridge.mjs 接入（手机不在线即失败）。",
                                fontSize = StUi.plansMetaTextSp.sp,
                                color = Color(palette.textSecondary),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** relay 单域（本地编辑态 + IME Done 保存通道——Dart onSubmitted 同形）。 */
@Composable
private fun RelayField(
    label: String,
    initial: String,
    onDone: (String) -> Unit,
    hint: String? = null,
    obscure: Boolean = false,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = hint?.let { h -> { Text(h) } },
        visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = if (obscure) KeyboardType.Password else KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
    )
}
