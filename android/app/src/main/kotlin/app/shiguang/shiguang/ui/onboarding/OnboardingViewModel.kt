package app.shiguang.shiguang.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.FixedSlotRef
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.model.SettingsKeys
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 首启引导轻 ViewModel（behavior/onboarding.md §A/§C 转正清单，D23 2026-11-15）：
 * 步进与表单为页内本地态（UI-local remember）；本 VM 只承载两写口——②预设 slots
 * 推进口与④一次性提交口（①②③仅本地暂存不落库，D23 过目照抄）。
 */
class OnboardingViewModel(
    private val repo: BridgeRepositoryPort,
) : ViewModel() {

    private val handler = CommandHandler(repo)

    /** ② 推进：非空且非「自由职业」才写预设（整单批量替换；自由职业=空表合法直接过）。 */
    fun commitPreset(presetName: String?) {
        if (presetName == null || presetName == FREELANCE) return
        viewModelScope.launch {
            handler.execute(ScheduleCommand.UpdateFixedSlots(slots = presetSlots(presetName)), actor = CommandActor.HUMAN)
        }
    }

    /**
     * ④ 一次性提交：恒写四数值键；身份画像/我的偏好 trim 后非空才写（空值不写，
     * human 通道不受 uiOnly 限制）；随后 revision 翻转驱动闸门重查（无需 onDone 回调
     * ——D23 裁决：闸门以 initialized StateFlow 驱动）。
     */
    fun commitFinal(wake: Int, sleep: Int, minBlock: Int, dailyLimit: Int, identity: String, rules: String) {
        viewModelScope.launch {
            val values = buildMap {
                put(SettingsKeys.WAKE_TIME, JsonPrimitive(wake))
                put(SettingsKeys.SLEEP_TIME, JsonPrimitive(sleep))
                put(SettingsKeys.MIN_BLOCK_MINUTES, JsonPrimitive(minBlock))
                put(SettingsKeys.DAILY_NEW_BLOCKS_LIMIT, JsonPrimitive(dailyLimit))
                val trimmedRules = rules.trim()
                if (trimmedRules.isNotEmpty()) put(SettingsKeys.USER_RULES, JsonPrimitive(trimmedRules))
                val trimmedIdentity = identity.trim()
                if (trimmedIdentity.isNotEmpty()) put(SettingsKeys.IDENTITY_PROMPT, JsonPrimitive(trimmedIdentity))
            }
            handler.execute(ScheduleCommand.UpdateSettings(values = values), actor = CommandActor.HUMAN)
        }
    }

    companion object {
        const val FREELANCE = "自由职业"

        /** 一周节奏预设（onboarding.md §A ②：工作日白天两段固定占用，逐字）。 */
        fun presetSlots(preset: String): List<FixedSlotRef> = when (preset) {
            "上班族" -> listOf(
                FixedSlotRef(id = null, name = "上班", weekdays = listOf(1, 2, 3, 4, 5), startMin = 9 * 60, endMin = 12 * 60),
                FixedSlotRef(id = null, name = "上班（下午）", weekdays = listOf(1, 2, 3, 4, 5), startMin = 14 * 60, endMin = 18 * 60),
            )
            "学生" -> listOf(
                FixedSlotRef(id = null, name = "上课", weekdays = listOf(1, 2, 3, 4, 5), startMin = 8 * 60, endMin = 12 * 60),
                FixedSlotRef(id = null, name = "上课（下午）", weekdays = listOf(1, 2, 3, 4, 5), startMin = 14 * 60, endMin = 17 * 60),
            )
            else -> emptyList()
        }

        /** Manual DI Factory（AppContainer 纪律同 GateViewModel）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                OnboardingViewModel(app.container.repo)
            }
        }
    }
}
