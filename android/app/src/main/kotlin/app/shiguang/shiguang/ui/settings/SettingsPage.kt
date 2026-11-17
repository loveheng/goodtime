package app.shiguang.shiguang.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.nav.ShiguangRoute
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 设置页（ui-pages §1 子页化拍板；behavior/settings_page.md §A–§C 已过目转正）：
 * 目录形态——高频项直改（作息边界/外观三档）+ AI 建议决断面 + 编辑器簇入口行
 * + 更新/协同/数据出口/关于（整卡长按调试菜单=MCP 隐藏入口，goodshare 范本）。
 * 标题栏由壳三 Tab 提供（无自有 AppBar）；数据/命令全经 SettingsViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    vm: SettingsViewModel,
    onNavigate: (ShiguangRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.uiState.collectAsState()
    val mcpRunning by vm.mcpRunning.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showDebugSheet by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var timePickKey by remember { mutableStateOf<String?>(null) }
    val palette = LocalStColors.current

    val settings = ui.settings
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StScale.insetLg.dp),
    ) {
        if (ui.loading) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(StScale.insetXl.dp))
                CircularProgressIndicator()
            }
            return@Column
        }
        // ── AI 建议区（非空显现；§B 采纳=✓ 写入/忽略=✕ 移除） ──
        if (ui.suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(Modifier.padding(StScale.insetMd.dp)) {
                    SectionTitle("AI 建议（待你决断）")
                    Spacer(Modifier.height(StScale.insetSm.dp))
                    for (it in ui.suggestions) {
                        ListItem(
                            headlineContent = { Text("建议修改「${it.key}」") },
                            supportingContent = {
                                Text(
                                    if (it.suggestedValue != null) "建议值：${it.suggestedValue}\n原因：${it.reason}"
                                    else "原因：${it.reason}",
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
                            trailingContent = {
                                Row {
                                    if (it.suggestedValue != null) {
                                        androidx.compose.material3.IconButton(
                                            onClick = { vm.applySuggestion(it) },
                                        ) {
                                            Icon(
                                                Icons.Filled.CheckCircleOutline,
                                                contentDescription = "采纳",
                                            )
                                        }
                                    }
                                    androidx.compose.material3.IconButton(onClick = { vm.dismissSuggestion(it) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "忽略")
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(StScale.insetLg.dp))
        }
        // ── 作息边界（高频项直改；引导①必填项的日常微调口） ──
        if (settings != null) {
            SettingsCard {
                SectionTitle("作息边界")
                Row {
                    TimeTile(
                        label = "起床",
                        minutes = settings.intOf("wake_time") ?: 420,
                        modifier = Modifier.weight(1f),
                        onPick = { timePickKey = "wake_time" },
                    )
                    TimeTile(
                        label = "睡觉",
                        minutes = settings.intOf("sleep_time") ?: 1380,
                        modifier = Modifier.weight(1f),
                        onPick = { timePickKey = "sleep_time" },
                    )
                }
            }
            Spacer(Modifier.height(StScale.insetLg.dp))
        }
        // ── 编辑器簇入口行（§B 三枚+协同/更新） ──
        EntryRow(Icons.Outlined.ViewWeek, "一周节奏", "有固定安排的时段") { onNavigate(ShiguangRoute.RhythmSettings) }
        EntryRow(Icons.Outlined.BeachAccess, "假期与出行", "例外日：期间挂起工作节奏") { onNavigate(ShiguangRoute.ExceptionsSettings) }
        EntryRow(Icons.Outlined.Tune, "排程偏好", "粒度/排量/填充率/我的偏好") { onNavigate(ShiguangRoute.PrefsSettings) }
        Spacer(Modifier.height(StScale.insetLg.dp))
        // ── 外观三档（uiOnly 键仅人可写） ──
        if (settings != null) {
            SettingsCard {
                SectionTitle("外观")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    for ((i, mode) in listOf("system", "light", "dark").withIndex()) {
                        val label = listOf("跟随系统", "浅色", "深色")[i]
                        SegmentedButton(
                            selected = settings.strOf("theme_mode") ?: "system" == mode,
                            onClick = { vm.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                        ) { Text(label) }
                    }
                }
            }
            Spacer(Modifier.height(StScale.insetLg.dp))
        }
        // ── 更新区（公告行 N8c 接线：RemoteConfig 快照随 reload 刷新） ──
        SettingsCard {
            SectionTitle("应用更新")
            ListItem(
                modifier = Modifier.clickableRow { onNavigate(ShiguangRoute.Update) },
                headlineContent = { Text("检查更新") },
                supportingContent = { Text("应用内自更新 + 远程公告/MCP 指令热更") },
                leadingContent = { Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
            )
            ui.announcement?.let { announcement ->
                ListItem(
                    headlineContent = { Text(announcement, fontSize = StUi.plansMetaTextSp.sp) },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Campaign,
                            contentDescription = null,
                            modifier = Modifier.size(StUi.mcpCopyIconDp.dp),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        // ── 协同（账号摘要静态面挂账随协作轮——AccountSession 未装配） ──
        SectionTitle("协同")
        EntryRow(Icons.Outlined.AccountCircle, "协同账号", "登录后与好友协同日程") { onNavigate(ShiguangRoute.Account) }
        Spacer(Modifier.height(StScale.insetLg.dp))
        // ── 数据出口（exportAll 六键弹框复制） ──
        SettingsCard {
            SectionTitle("数据出口")
            ListItem(
                modifier = Modifier.clickableRow {
                    scope.launch { exportJson = vm.exportAllJson() }
                },
                headlineContent = { Text("导出 JSON（全量）") },
                supportingContent = { Text("计划/一周节奏/日程块/设置，本地持有随导随走") },
                leadingContent = { Icon(Icons.Filled.IosShare, contentDescription = null) },
            )
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        // ── 关于（整卡长按=调试菜单；真机容错：整卡 opaque 抢手不丢） ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showDebugSheet = true },
                ),
        ) {
            Column(Modifier.padding(StScale.insetMd.dp)) {
                SectionTitle("关于")
                Text(
                    "用户本地持有的日程台账。",
                    fontSize = StUi.plansMetaTextSp.sp,
                    color = Color(palette.textSecondary),
                )
            }
        }
    }
    // 调试菜单（长按「关于」整卡唤出；MCP 隐藏入口）
    if (showDebugSheet) {
        ShiguangBottomSheet(onDismissRequest = { showDebugSheet = false }) {
            Text(
                "调试菜单",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = StScale.insetMd.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.W600,
            )
            ListItem(
                modifier = Modifier.clickableRow {
                    showDebugSheet = false
                    onNavigate(ShiguangRoute.McpDebug)
                },
                headlineContent = { Text("MCP 调试服务") },
                supportingContent = {
                    Text(
                        if (mcpRunning) "运行中 · 端口 8765" else "未运行（仅 loopback，内部调试用）",
                    )
                },
                leadingContent = { Icon(Icons.Outlined.DeveloperMode, contentDescription = null) },
            )
        }
    }
    // 全量导出弹框（Selectable→纯 Text；复制全部=剪贴板）
    exportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJson = null },
            title = { Text("全量导出") },
            text = {
                Column(Modifier.height(StUi.exportDialogMaxHeightDp.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            json,
                            fontSize = StUi.plansMetaTextSp.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json))
                    exportJson = null
                    vm.notifyExportCopied()
                }) { Text("复制全部") }
            },
            dismissButton = {
                TextButton(onClick = { exportJson = null }) { Text("关闭") }
            },
        )
    }
    // 作息边界 TimePicker 弹框（TimePicker→分钟数直写）
    timePickKey?.let { key ->
        val minutes = settings?.intOf(key) ?: (if (key == "wake_time") 420 else 1380)
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        TimePickerDialog(
            onDismissRequest = { timePickKey = null },
            title = { Text("选择时间") },
            confirmButton = {
                TextButton(onClick = {
                    vm.setTime(key, state.hour * 60 + state.minute)
                    timePickKey = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { timePickKey = null }) { Text("取消") }
            },
        ) {
            TimePicker(state = state)
        }
    }
}

private fun JsonObject.intOf(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

private fun JsonObject.strOf(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(StScale.insetMd.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = StScale.insetXs.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

/** 子页入口行（§B 目录形态）：Card+ListTile leading 22+chevron 20 textSecondary。 */
@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
    val palette = LocalStColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = StScale.insetXs.dp)
            .combinedClickable(onClick = onOpen),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(StUi.settingsEntryIconDp.dp)) },
            trailingContent = {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(StUi.smallIconDp.dp),
                    tint = Color(palette.textSecondary),
                )
            },
        )
    }
}

/** 作息边界单格（§B：subtitle clockOf 20pt→TimePicker）。 */
@Composable
private fun TimeTile(
    label: String,
    minutes: Int,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickableRow(onPick),
        headlineContent = { Text(label) },
        supportingContent = {
            Text(ScheduleDay.clockOf(minutes), fontSize = StUi.settingsClockTextSp.sp)
        },
    )
}
