package app.shiguang.shiguang.ui.nav

import kotlinx.serialization.Serializable

/**
 * 类型安全路由契约（P4①-d；behavior/routing_map.md 转正版，2026-11-15 过目裁决）：
 * 目的地一律 @Serializable sealed，**严禁手写字符串路由**；21 条 1:1 对位 30 UI 单元
 * （主干 4 + 详情族 3 + 设置族 4 + 账号 1 + 协作 9）。OP1+OP4 裁决 = 三顶层
 * destination（Today/Plans/Settings，AppShell 仅作 bottomBar+FAB 宿主组件名）；
 * OP3 裁决 = plan 三独立路由。周视图/月历 = 今日页内 segment 视图态，非路由。
 *
 * 浮层族**不进 NavHost**（sheet/dialog 承载）：FactSheet/BlockSheet/GlobalActionSheet/
 * RejectPanel/ActionPassBar/QuickNoteFab/逆向记账·新建事项两 sheet——返回键逐层消费
 * （丢失点 #8）。返回结果走 SavedStateHandle 回传，不引入全局事件总线；无 DeepLink
 * 需求（仅登记契约，不预建 deep link 基建）。核心编辑暂存态收敛 SavedStateHandle、
 * 滚动偏移/当前 Tab/浮层展开态 rememberSaveable（丢失点 #9）。
 */
@Serializable
sealed interface ShiguangRoute {
    /** ui-pages §2 首启引导（第 1 步必填不可跳）。 */
    @Serializable data object Onboarding : ShiguangRoute

    /** 底部三 Tab（OP1 裁决：三顶层 destination）。 */
    @Serializable data object Today : ShiguangRoute
    @Serializable data object Plans : ShiguangRoute
    @Serializable data object Settings : ShiguangRoute

    /** 详情族（OP3 裁决：三独立路由；背景区/事实页从详情页进入）。N7 参数化：详情页按 planId 加载（Dart PlanDetailPage(planId:) 对位）；D24 轮：背景/事实两路由参数化 planId（子页按 planId 聚合装配）。 */
    @Serializable data class PlanDetail(val planId: String) : ShiguangRoute
    @Serializable data class PlanBackground(val planId: String) : ShiguangRoute
    @Serializable data class PlanFacts(val planId: String) : ShiguangRoute

    /** 设置族（MCP 调试 = 隐藏入口：设置页「关于」长按唤调试菜单）。N8a：+PrefsSettings（D21/P8 裁决——候选 21→22，排程偏好子页）。 */
    @Serializable data object McpDebug : ShiguangRoute
    @Serializable data object ExceptionsSettings : ShiguangRoute
    @Serializable data object RhythmSettings : ShiguangRoute
    @Serializable data object PrefsSettings : ShiguangRoute
    @Serializable data object Update : ShiguangRoute

    /** 协同族账号页。 */
    @Serializable data object Account : ShiguangRoute

    /** 协作九页（ui-pages §8.3 B 档）。D27 裁决参数化：包域三路由按 bundleId 定位（Canvas 另带窗口）。 */
    @Serializable data class CollabAuditTimeline(val bundleId: String) : ShiguangRoute
    @Serializable data class CollabCanvas(val bundleId: String, val windowStartMs: Long, val windowEndMs: Long) : ShiguangRoute
    @Serializable data object CollabComposeBundle : ShiguangRoute
    @Serializable data class CollabDecisionMatrix(val bundleId: String) : ShiguangRoute
    @Serializable data object CollabFriends : ShiguangRoute
    @Serializable data object CollabMemberCalendar : ShiguangRoute
    @Serializable data object CollabMyQr : ShiguangRoute

    /** D27 裁决参数化（安全码核对按 friend.userId 定位——禁整对象路由序列化）。 */
    @Serializable data class CollabSafetyNumber(val friendUserId: String) : ShiguangRoute
    @Serializable data object CollabScan : ShiguangRoute
}
