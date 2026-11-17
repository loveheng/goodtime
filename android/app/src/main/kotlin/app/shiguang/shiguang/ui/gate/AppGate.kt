package app.shiguang.shiguang.ui.gate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.shiguang.ui.onboarding.OnboardingPage

/**
 * 入口闸门（behavior/app_gate.md §A 转正清单，D23 2026-11-15）：settings 未初始化
 * （缺作息边界）→ 首启引导；完成即进主框架。引导是 propose 硬拒（§6 校验铁律）的
 * UI 前置（ui-pages §2）。决策响应式=revision 驱动重查（丢失点 #4 同款，LaunchedEffect 承载）；分享转投消费=首帧后 [GateViewModel.consumePendingShare]
 * （Dart postFrameCallback 等义，D23 裁决 §D.2）。决策重查面=GateViewModel 内部
 * collect 仓储 revision 流（Dart ListenableBuilder 对位）。
 */
@Composable
fun AppGate(
    /** 已初始化的主框架内容（AppShell/NavHost 由宿主注入——本件只承载闸门分派）。 */
    shell: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: GateViewModel = viewModel(factory = GateViewModel.factory)
    val initialized by vm.initialized.collectAsState()
    val pendingShare by vm.pendingShare.collectAsState()

    // 分享消费：挂载即排一次（初始值非空直接消费）+ 监听兜新消息（§B 双通道）
    LaunchedEffect(vm) { vm.consumePendingShare() }
    LaunchedEffect(pendingShare) { vm.consumePendingShare() }

    when (initialized) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        false -> OnboardingPage(modifier = modifier)
        else -> Column(modifier) { shell() }
    }
}
