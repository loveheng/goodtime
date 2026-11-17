package app.shiguang.shiguang.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shiguang.core.model.SettingsKeys
import app.shiguang.core.tokens.StScale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 排程偏好设置页（ui-pages §1 子页化拍板；behavior/settings_subpages.md §C 已过目
 * 转正）：六字段独立提交（update_settings 字段级，改一个存一个；非法值命令层拦截）。
 * 表单 key=label|initial——重载重置（Dart ValueKey 同构）。
 */
@Composable
fun PrefsSettingsPage(vm: SubpagesViewModel, modifier: Modifier = Modifier) {
    val ui by vm.uiState.collectAsState()
    val settings = ui.settings

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StScale.insetLg.dp),
    ) {
        if (ui.loading || settings == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(StScale.insetXl.dp))
                CircularProgressIndicator()
            }
            return@Column
        }
        fun strOf(k: String): String = (settings[k] as? JsonPrimitive)?.contentOrNull ?: ""
        fun intOf(k: String): Int? = (settings[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

        IntPrefField(
            label = "最小块粒度（分钟）",
            initial = intOf("min_block_minutes"),
            formKey = "min_block_minutes|${intOf("min_block_minutes")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.MIN_BLOCK_MINUTES to JsonPrimitive(it))) },
            vm = vm,
        )
        IntPrefField(
            label = "单日新增排量上限（块数）",
            initial = intOf("daily_new_blocks_limit"),
            formKey = "daily_new_blocks_limit|${intOf("daily_new_blocks_limit")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.DAILY_NEW_BLOCKS_LIMIT to JsonPrimitive(it))) },
            vm = vm,
        )
        IntPrefField(
            label = "填充率上限（%）",
            initial = intOf("fill_rate_limit"),
            hint = "默认 60，留白是吸收意外的护城河",
            formKey = "fill_rate_limit|${intOf("fill_rate_limit")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.FILL_RATE_LIMIT to JsonPrimitive(it))) },
            vm = vm,
        )
        TextPrefField(
            label = "天气城市（手动填，零定位权限）",
            initial = strOf("weather_location"),
            formKey = "weather_location|${strOf("weather_location")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.WEATHER_LOCATION to JsonPrimitive(it))) },
        )
        TextPrefField(
            label = "我的偏好（自然语言，如「周五晚上不排深度工作」）",
            initial = strOf("user_rules"),
            maxLines = 3,
            formKey = "user_rules|${strOf("user_rules")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.USER_RULES to JsonPrimitive(it))) },
        )
        TextPrefField(
            label = "身份画像（系统提示词）：描述你是谁、处境与硬约束，随 MCP 注入 AI 排程上下文",
            initial = strOf("identity_prompt"),
            maxLines = 4,
            formKey = "identity_prompt|${strOf("identity_prompt")}",
            onSaved = { vm.savePrefs(mapOf(SettingsKeys.IDENTITY_PROMPT to JsonPrimitive(it))) },
        )
    }
}

/** 整数字段（§C：Row 数字框+保存钮；tryParse null→「请填数字」1s）。 */
@Composable
private fun IntPrefField(
    label: String,
    initial: Int?,
    formKey: String,
    hint: String? = null,
    onSaved: (Int) -> Unit,
    vm: SubpagesViewModel,
) {
    key(formKey) {
        var text by remember(formKey) { mutableStateOf(initial?.toString() ?: "") }
        Column(Modifier.padding(bottom = StScale.insetMd.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(label) },
                    placeholder = hint?.let { h -> { Text(h) } },
                )
                Spacer(Modifier.width(StScale.insetSm.dp))
                TextButton(onClick = {
                    val v = text.trim().toIntOrNull()
                    if (v == null) {
                        vm.notifyNotNumber()
                        return@TextButton
                    }
                    onSaved(v)
                }) { Text("保存") }
            }
        }
    }
}

/** 文本字段（§C：Column 框+右对齐保存钮；trim 提交）。 */
@Composable
private fun TextPrefField(
    label: String,
    initial: String,
    formKey: String,
    maxLines: Int = 1,
    onSaved: (String) -> Unit,
) {
    key(formKey) {
        var text by remember(formKey) { mutableStateOf(initial) }
        Column(Modifier.padding(bottom = StScale.insetMd.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = maxLines,
                label = { Text(label) },
            )
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = { onSaved(text.trim()) },
            ) { Text("保存") }
        }
    }
}
