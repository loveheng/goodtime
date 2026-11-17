package app.shiguang.shiguang.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 首启引导 4 步（ui-pages §2；behavior/onboarding.md §A/§B 转正清单，D23 2026-11-15）：
 * ①作息边界（预填常规值，必填=不可跳）→②一周节奏（可跳）→③排程数值偏好（可跳）
 * →④关于你（身份画像+我的偏好，可跳但推荐）。完成即写 settings+fixed_slots；
 * 未完成无法进主框架。步进与表单=页内本地态（③暂存不落库随④合并提交——D23 过目
 * 照抄；无回退步照抄）；①预填使主按钮恒可用（D23 拍板：规范对齐实现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingPage(
    vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory),
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    // ① 作息边界（预填 420/1380——必填恒满足，D23 拍板）
    var wake by rememberSaveable { mutableIntStateOf(420) }
    var sleep by rememberSaveable { mutableIntStateOf(1380) }
    // ② 一周节奏（上班族/学生/自由职业）
    var preset by rememberSaveable { mutableStateOf<String?>(null) }
    // ③ 排程偏好
    var minBlock by rememberSaveable { mutableIntStateOf(30) }
    var dailyLimit by rememberSaveable { mutableIntStateOf(8) }
    // ④ 关于你（human 通道写入，不受 uiOnly 限制）
    var identityPrompt by rememberSaveable { mutableStateOf("") }
    var userRules by rememberSaveable { mutableStateOf("") }
    var timePickKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StUi.onboardingInsetDp.dp),
    ) {
        Text("拾光", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(StScale.insetXs.dp))
        Text("第 ${step + 1} 步，共 4 步", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        Column(Modifier.weight(1f)) {
            when (step) {
                0 -> StepSchedule(wake, sleep, palette.textSecondary) { timePickKey = it }
                1 -> StepRhythm(preset) { preset = it }
                2 -> StepPreference(
                    minBlock, dailyLimit, palette.textSecondary,
                    onMinBlock = { minBlock = it },
                    onDailyLimit = { dailyLimit = it },
                )
                else -> StepProfile(identityPrompt, userRules, palette.textSecondary,
                    onIdentity = { identityPrompt = it },
                    onRules = { userRules = it },
                )
            }
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        Button(
            onClick = {
                when (step) {
                    1 -> vm.commitPreset(preset)
                    3 -> vm.commitFinal(wake, sleep, minBlock, dailyLimit, identityPrompt, userRules)
                }
                step += 1
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (step < 3) "下一步" else "开始使用")
        }
        if (step > 0 && step < 3) {
            TextButton(onClick = { step += 1 }, modifier = Modifier.fillMaxWidth()) {
                Text("跳过")
            }
        }
    }
    // ① TimePicker 弹框（分钟数直存——引导时仅本地态，随④合并提交）
    timePickKey?.let { key ->
        val minutes = if (key == "wake") wake else sleep
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        TimePickerDialog(
            onDismissRequest = { timePickKey = null },
            title = { Text("选择时间") },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.hour * 60 + state.minute
                    if (key == "wake") wake = picked else sleep = picked
                    timePickKey = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { timePickKey = null }) { Text("取消") } },
        ) {
            TimePicker(state = state)
        }
    }
}

/** ① 作息边界：「几点起床？几点睡觉？」+「一切安排以此为界」；双时间卡。 */
@Composable
private fun StepSchedule(wake: Int, sleep: Int, textSecondary: Long, onPick: (String) -> Unit) {
    Column {
        Text(
            "几点起床？几点睡觉？",
            fontSize = StUi.stepTitleTextSp.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("一切安排以此为界", color = Color(textSecondary))
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        TimeCard("起床", wake) { onPick("wake") }
        Spacer(Modifier.height(StUi.onboardingGapMdDp.dp))
        TimeCard("睡觉", sleep) { onPick("sleep") }
    }
}

@Composable
private fun TimeCard(label: String, minutes: Int, onPick: () -> Unit) {
    // 卡面整体可点（Dart ListTile onTap 同形）
    Card(Modifier.fillMaxWidth().clickable { onPick() }) {
        ListItem(
            headlineContent = { Text(label) },
            trailingContent = {
                Text(
                    ScheduleDay.clockOf(minutes),
                    fontSize = StUi.onboardingClockTextSp.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
        )
    }
}

/** ② 一周节奏：三预设单选卡；自由职业注「没有固定安排是完全正常的」。 */
@Composable
private fun StepRhythm(selected: String?, onSelect: (String) -> Unit) {
    Column {
        Text(
            "一周里哪些时间是有固定安排的？",
            fontSize = StUi.stepTitleTextSp.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        for ((name, note) in listOf(
            "上班族" to "工作日白天在班上",
            "学生" to "工作日有课",
            "自由职业" to "没有固定安排是完全正常的",
        )) {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { RadioButton(selected = selected == name, onClick = { onSelect(name) }) },
                    headlineContent = { Text(name) },
                    supportingContent = { Text(note) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** ③ 排程偏好：粒度三档 + 单日上限滑杆（注「上限是休息权的模型支撑，不是效率指标」）。 */
@Composable
private fun StepPreference(
    minBlock: Int,
    dailyLimit: Int,
    textSecondary: Long,
    onMinBlock: (Int) -> Unit,
    onDailyLimit: (Int) -> Unit,
) {
    Column {
        Text("排程偏好", fontSize = StUi.stepTitleTextSp.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        Text("最小块粒度")
        Spacer(Modifier.height(StScale.insetSm.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for ((i, v) in listOf(15, 30, 60).withIndex()) {
                SegmentedButton(
                    selected = minBlock == v,
                    onClick = { onMinBlock(v) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                ) { Text("$v 分钟") }
            }
        }
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        Text("单日新增排量上限：$dailyLimit 项")
        Slider(
            value = dailyLimit.toFloat(),
            onValueChange = { onDailyLimit(it.toInt()) },
            valueRange = 3f..12f,
            steps = 8,
        )
        Text("上限是休息权的模型支撑，不是效率指标", color = Color(textSecondary))
    }
}

/**
 * ④ 关于你：身份画像+我的偏好两多行域（带示例占位）；内容包可滚动（小屏/键盘
 * 挤压不溢出——测试视口 800×600 即溢出，行为清单回归钉所在）。
 */
@Composable
private fun StepProfile(
    identity: String,
    rules: String,
    textSecondary: Long,
    onIdentity: (String) -> Unit,
    onRules: (String) -> Unit,
) {
    Column {
        Text("关于你", fontSize = StUi.stepTitleTextSp.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(StScale.insetXs.dp))
        Text(
            "这两项是 AI 排程的地基，建议填一下；可稍后在「设置」页随时修改。",
            color = Color(textSecondary),
        )
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        Text("身份画像（系统提示词）", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(StUi.onboardingGapXsDp.dp))
        TextField(
            value = identity,
            onValueChange = onIdentity,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 4,
            placeholder = {
                Text(
                    "例如：自由职业者，家有两岁宝宝，上午精力最好、晚上 9 点后不处理工作；" +
                        "硬约束是周三下午要陪诊。",
                )
            },
        )
        Spacer(Modifier.height(StUi.onboardingGapLgDp.dp))
        Text("我的偏好（自然语言）", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(StUi.onboardingGapXsDp.dp))
        TextField(
            value = rules,
            onValueChange = onRules,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 4,
            placeholder = {
                Text(
                    "例如：周五晚上不排深度工作；午饭后留 30 分钟散步；" +
                        "deadline 前两天不排新事务。",
                )
            },
        )
    }
}
