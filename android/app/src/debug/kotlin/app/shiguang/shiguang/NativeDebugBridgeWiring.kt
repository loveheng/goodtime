package app.shiguang.shiguang

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.FixedSlotRef
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.core.model.collab.relay.RelayFriendsException
import app.shiguang.core.model.collab.sealPayload
import app.shiguang.shiguang.data.AndroidSqlStore
import app.shiguang.shiguang.ui.nav.ShiguangRoute
import com.appdebugbridge.DebugBridge
import com.appdebugbridge.DebugLogEntry
import com.appdebugbridge.DebugLogSink
import com.appdebugbridge.DebugRouteRegistry
import com.appdebugbridge.DebugScreenshot
import com.appdebugbridge.ui.semanticsTreeProvider
import com.appdebugbridge.action.DebugActions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom

/**
 * 原生 Compose 宿主调试桥装配（SDK app-debug-bridge 0.5.0；N8c 真机走查自动化接入，
 * 2026-11-15）：与 DebugBridgeWiring（Flutter 宿主）并列——本件挂 [NativeMainActivity]
 * （无 Flutter engine，纯 Compose），语义树经 [ComposeSemanticsWalker] 全量注入，
 * tap_node/set_text/long_press/ui_contains 走真语义面。
 *
 * 宿主扩展动作（actionHandler 自定义段，走查断言/数据面）：
 * - nav {page}: 原生路由白名单 → container.navRequests（壳层消费导航）
 * - repo_read {scope}: settings|export_all → JSON 快照/断言面
 * - repo_set {values}: settingsSet KV 直写（走查还原面）
 * - gate_clear: 清 wake_time/sleep_time（闸门翻转触发面）
 * - slots_set {slots}: UpdateFixedSlots 整单还原面
 * - share_intake {text}: pendingShare 直投（ACTION_SEND intake 同代码路径）
 * - artifacts_query {title}: 分享入册断言面（第二句柄只读）
 * - artifacts_clean {title}: 走查产物清理面（第二句柄 DELETE）
 * - mcp_ping: 宿主进程内回环 HTTP ping（token 自动携带）→ 真听面断言
 * - relay_login/relay_logout/relay_status: B 档播种器第一版（双账号登录态切换）
 * - collab_seed_cipher: B 档播种器第二版（双账号好友关系 + 真实 K_bundle 密文事件）
 * - gesture_inject {x,y,dx,dy,hold_ms,steps,step_interval_ms}: S4 精确手势注入面（真实时序
 *   DOWN/MOVE/UP——长按呼吸态/吸附临界/系统手势抢夺对拍；SDK precise_gesture 缺口宿主侧补位）
 */
object NativeDebugBridgeWiring {

    private const val TAG = "debug-bridge"

    /** 宿主侧日志环形缓冲（500 条上限，与 DebugBridgeWiring 同口径）。 */
    private class Entry(val ts: Long, val level: String, val message: String)

    private val logBuf = ArrayDeque<Entry>()
    // 状态修订计数（0.5.2 StateRevisionCounter）：直连 DB 写动作（seed/clean 族）
    // 后 bump——义务在宿主状态持有层，SDK 不代 bump（会伪造 revisionChanged 落定证据）
    private val stateRevisionCounter = com.appdebugbridge.StateRevisionCounter()
    private var logRevision = 0

    private class NativeLogSink : DebugLogSink {
        override val length: Int
            get() = synchronized(logBuf) { logBuf.size }
        override val revision: Int
            get() = synchronized(logBuf) { logRevision }
        override fun entries(): List<DebugLogEntry> =
            synchronized(logBuf) { logBuf.map { DebugLogEntry(it.ts, it.level, it.message) } }
    }

    /** 桥内日志直写面（走查步骤留痕，logcat 之外的第二通道）。 */
    fun log(level: String, message: String) {
        synchronized(logBuf) {
            logBuf.addLast(Entry(System.currentTimeMillis(), level, message))
            if (logBuf.size > 500) logBuf.removeFirst()
            logRevision++
        }
    }

    /**
     * 精确手势注入（S4 交互对拍面，2026-11-17）：在 rootView 上按真实时序派发
     * DOWN → N×MOVE → UP，供长按 400ms 呼吸态 / 吸附临界 45%·35% 复现。
     * SDK KNOWN_GAPS.precise_gesture 的宿主侧补位（SDK 仓只读，不越界写入）。
     *
     * 真机实证（2026-11-17，walkthrough-s4）：dispatchTouchEvent 派生 MotionEvent 被
     * AndroidComposeView.pointerInput 消费——46% 右滑跟手位移、36% 左滑位移均在
     * mid-drag 截图中可见（/tmp/s4-walkthrough-074021），注入路径可用。
     */
    private fun injectPreciseGesture(
        activity: Activity,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        holdMs: Long,
        steps: Int,
        stepIntervalMs: Long,
    ): Map<String, Any?> {
        val root = activity.window?.decorView?.rootView
            ?: return mapOf("ok" to false, "error" to "root_unavailable")
        val done = java.util.concurrent.CountDownLatch(1)
        val timeline = mutableListOf<Map<String, Any?>>()
        val downTime = android.os.SystemClock.uptimeMillis()
        val safeSteps = steps.coerceIn(1, 60)
        val safeHold = holdMs.coerceIn(0L, 3000L)
        val safeInterval = stepIntervalMs.coerceIn(1L, 200L)
        val handler = Handler(Looper.getMainLooper())

        fun emit(action: Int, px: Float, py: Float, label: String) {
            val at = android.os.SystemClock.uptimeMillis()
            val ev = android.view.MotionEvent.obtain(downTime, at, action, px, py, 0)
            timeline += mapOf("t" to (at - downTime), "phase" to label, "x" to px, "y" to py)
            root.dispatchTouchEvent(ev)
            ev.recycle()
        }

        handler.post {
            emit(android.view.MotionEvent.ACTION_DOWN, x, y, "down")
            repeat(safeSteps) { i ->
                handler.postDelayed({
                    val f = (i + 1).toFloat() / safeSteps
                    emit(android.view.MotionEvent.ACTION_MOVE, x + dx * f, y + dy * f, "move${i + 1}")
                    if (i == safeSteps - 1) {
                        handler.postDelayed({
                            emit(android.view.MotionEvent.ACTION_UP, x + dx, y + dy, "up")
                            done.countDown()
                        }, safeInterval)
                    }
                }, safeHold + (i + 1) * safeInterval)
            }
        }

        val finished = done.await(12, java.util.concurrent.TimeUnit.SECONDS)
        return mapOf(
            "ok" to finished,
            "from" to listOf(x, y),
            "to" to listOf(x + dx, y + dy),
            "hold_ms" to safeHold,
            "steps" to safeSteps,
            "step_interval_ms" to safeInterval,
            "timeline" to timeline.toList(),
        )
    }

    /** Activity onCreate（Compose setContent 后）调用——桥生命周期随 Activity 重建重启。 */
    fun attach(activity: Activity) {
        val app = activity.applicationContext as ShiguangApplication
        val container = app.container
        val rootProvider = { activity.window?.decorView?.rootView }

        val routeRegistry = DebugRouteRegistry(
            mapOf(
                "today" to { container.navRequests.tryEmit(ShiguangRoute.Today) },
                "plans" to { container.navRequests.tryEmit(ShiguangRoute.Plans) },
                "settings" to { container.navRequests.tryEmit(ShiguangRoute.Settings) },
                // 基线采集页名对位（capture stage3/4）：schedule_page=今日页原生对位
                "schedule_page" to { container.navRequests.tryEmit(ShiguangRoute.Today) },
                "update" to { container.navRequests.tryEmit(ShiguangRoute.Update) },
                "mcp" to { container.navRequests.tryEmit(ShiguangRoute.McpDebug) },
                // 设置族采集页名（capture stage1）：Dart debugPages 白名单 → 原生路由对位
                "settings_page" to { container.navRequests.tryEmit(ShiguangRoute.Settings) },
                "plans_page" to { container.navRequests.tryEmit(ShiguangRoute.Plans) },
                "rhythm_settings" to { container.navRequests.tryEmit(ShiguangRoute.RhythmSettings) },
                "prefs_settings" to { container.navRequests.tryEmit(ShiguangRoute.PrefsSettings) },
                "exceptions_settings" to { container.navRequests.tryEmit(ShiguangRoute.ExceptionsSettings) },
                "update_page" to { container.navRequests.tryEmit(ShiguangRoute.Update) },
                "onboarding" to { container.navRequests.tryEmit(ShiguangRoute.Onboarding) },
                "account_page" to { container.navRequests.tryEmit(ShiguangRoute.Account) },
                // 浮层宿主采集页名（capture stage3/4）：Dart 宿主=Scaffold(SchedulePage)，
                // 浮层经 open_overlay 合成——原生对位今日页（浮层的产品唤起路径）
                "fact_sheet" to { container.navRequests.tryEmit(ShiguangRoute.Today) },
                "block_sheet" to { container.navRequests.tryEmit(ShiguangRoute.Today) },
                "action_pass_bar" to { container.navRequests.tryEmit(ShiguangRoute.Today) },
                // app_gate：Dart 侧别名=OnboardingPage，原生对位引导页
                "app_gate" to { container.navRequests.tryEmit(ShiguangRoute.Onboarding) },
                "plan_detail" to { /* planId 须路由参——走 navRequests 总线不适用，占位供基线扩展 */ },
                "collab_scan" to { container.navRequests.tryEmit(ShiguangRoute.CollabScan) },
                // 协作九页对象路由（T4.9 走查面；参数路由=nav 分支 when 直投）
                "collab_member_calendar" to { container.navRequests.tryEmit(ShiguangRoute.CollabMemberCalendar) },
                "collab_friends" to { container.navRequests.tryEmit(ShiguangRoute.CollabFriends) },
                "collab_my_qr" to { container.navRequests.tryEmit(ShiguangRoute.CollabMyQr) },
                "collab_compose_bundle" to { container.navRequests.tryEmit(ShiguangRoute.CollabComposeBundle) },
                "collab_account" to { container.navRequests.tryEmit(ShiguangRoute.Account) },
            ),
        )
        val nodeProvider = {
            val descriptors = rootProvider()?.let { ComposeSemanticsWalker.collect(it) } ?: emptyList()
            semanticsTreeProvider(descriptors, "compose")
        }
        val actions = DebugActions(
            rootProvider = { rootProvider() },
            routeRegistry = routeRegistry,
            nodeProvider = nodeProvider,
        )

        val bridge = DebugBridge(
            logSink = NativeLogSink(),
            // /debug/ui-tree 端点供给（Map 形；漏传即 not_configured，bounds 驱动
            // 走查面失效——采图轮实证补注入；DebugNode 形另走 nodeProvider）
            uiTreeProvider = {
                rootProvider()?.let { com.appdebugbridge.ui.collectDebugUiTree(it) } ?: emptyList()
            },
            // 统一节点源（S4 对拍轮补）：DebugActions 已消费 nodeProvider 语义树，桥端此前
            // 只注 uiTreeProvider(viewtree) 致 state.unifiedNodeSource=false、ui_contains
            // 对 Compose 文本命中不稳（S4 块 bounds 漂移根因）——同源双注，动作面与观测面一致
            debugNodeProvider = nodeProvider,
            nodeSource = "compose",
            actionDriver = com.appdebugbridge.ui.ActionDriver.COMPOSE_SEMANTICS,
            stateRevisionProvider = { stateRevisionCounter.current },
            // capabilityRegistry（0.5.2）：/debug/actions 响应面（actions[]+knownGaps
            // 声明性缺口——AI 客户端读到缺口直接走绕行，不盲试）；宿主动作经
            // actionHandler 分发不在注册表，注册表空构造即满足清单/缺口契约
            capabilityRegistry = com.appdebugbridge.capabilities.CapabilityRegistry(
                com.appdebugbridge.actions.ActionRegistry(),
            ),
            // token 持久化发现链（0.5.2）：logcat 轮转后 token/port 仍可从私有文件
            // 恢复——走查/采图高频 force-stop 不再丢桥（发现脚本已配套消费此文件）
            tokenListener = { token, port ->
                runCatching {
                    java.io.File(activity.filesDir, "debug-bridge-token.txt")
                        .writeText("$token $port")
                }
            },
            actionHandler = { action, args ->
                when (action) {
                    "nav" -> {
                        val page = args["page"] as? String
                        // 参数路由（T4 走查面：bundleId/friendUserId 经 args 注入 data class 路由）
                        val paramRoute: (() -> Unit)? = when (page) {
                            "collab_decision_matrix" -> (args["bundle_id"] as? String)?.let { bid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabDecisionMatrix(bid)) })
                            }
                            "collab_audit_timeline" -> (args["bundle_id"] as? String)?.let { bid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabAuditTimeline(bid)) })
                            }
                            "collab_canvas" -> (args["bundle_id"] as? String)?.let { bid ->
                                ({
                                    container.navRequests.tryEmit(
                                        ShiguangRoute.CollabCanvas(
                                            bid,
                                            (args["window_start_ms"] as? Number)?.toLong() ?: 0L,
                                            (args["window_end_ms"] as? Number)?.toLong() ?: 0L,
                                        ),
                                    )
                                })
                            }
                            "collab_safety_number" -> (args["friend_user_id"] as? String)?.let { uid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabSafetyNumber(uid)) })
                            }
                            else -> null
                        }
                        if (paramRoute != null) {
                            Handler(Looper.getMainLooper()).post { paramRoute() }
                            mapOf("ok" to true, "route" to page)
                        } else if (page != null && routeRegistry.contains(page)) {
                            // 主线程发射（壳层 collect 切换面）
                            Handler(Looper.getMainLooper()).post { routeRegistry.builderFor(page)?.invoke() }
                            mapOf("ok" to true, "route" to page)
                        } else {
                            mapOf("ok" to false, "error" to "unknown_page")
                        }
                    }
                    "navigate" -> {
                        // 像素采集器统一入口（capture.dart 发 navigate+state）：B 档页名映射原生路由；
                        // 状态由采集驱动预置（S1 播种 / S2 空账号），本动作只负责跳页与参数补齐。
                        val raw = args["page"] as? String
                        val page = when (raw) {
                            "friends" -> "collab_friends"
                            "member_calendar" -> "collab_member_calendar"
                            "my_qr" -> "collab_my_qr"
                            "safety_number" -> "collab_safety_number"
                            "compose_bundle" -> "collab_compose_bundle"
                            "audit_timeline" -> "collab_audit_timeline"
                            "decision_matrix" -> "collab_decision_matrix"
                            "collab_canvas" -> "collab_canvas"
                            "scan" -> "collab_scan"
                            else -> raw
                        }
                        val navArgs = args.toMutableMap()
                        navArgs.putIfAbsent("bundle_id", "walk-cipher")
                        if (page == "collab_safety_number" && navArgs["friend_user_id"] == null) {
                            navArgs["friend_user_id"] = kotlinx.coroutines.runBlocking {
                                runCatching {
                                    container.loadFriendsSession()?.listFriends()
                                        ?.firstOrNull { it.isAccepted }?.userId
                                }.getOrNull()
                            } ?: ""
                        }
                        if (page == "collab_canvas") {
                            val now = System.currentTimeMillis()
                            navArgs.putIfAbsent("window_start_ms", now - 21_600_000)
                            navArgs.putIfAbsent("window_end_ms", now + 21_600_000)
                        }
                        // plan 族基线页（stage4）：planId 无路由参透传——取种子仓库首个 plan
                        val seededPlanId: String? = if (page in setOf("plan_detail", "plan_facts", "plan_background")) {
                            kotlinx.coroutines.runBlocking {
                                runCatching {
                                    (container.repo.listPlans() as? kotlinx.serialization.json.JsonArray)
                                        ?.firstOrNull()
                                        ?.let { (it as? kotlinx.serialization.json.JsonObject)
                                            ?.get("id")?.jsonPrimitive?.contentOrNull }
                                }.getOrNull()
                            }
                        } else null
                        val paramRoute: (() -> Unit)? = when (page) {
                            "plan_detail" -> seededPlanId?.let { pid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.PlanDetail(pid)) })
                            }
                            "plan_facts" -> seededPlanId?.let { pid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.PlanFacts(pid)) })
                            }
                            "plan_background" -> seededPlanId?.let { pid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.PlanBackground(pid)) })
                            }
                            "collab_decision_matrix" -> (navArgs["bundle_id"] as? String)?.let { bid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabDecisionMatrix(bid)) })
                            }
                            "collab_audit_timeline" -> (navArgs["bundle_id"] as? String)?.let { bid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabAuditTimeline(bid)) })
                            }
                            "collab_canvas" -> (navArgs["bundle_id"] as? String)?.let { bid ->
                                ({
                                    container.navRequests.tryEmit(
                                        ShiguangRoute.CollabCanvas(
                                            bid,
                                            (navArgs["window_start_ms"] as? Number)?.toLong() ?: 0L,
                                            (navArgs["window_end_ms"] as? Number)?.toLong() ?: 0L,
                                        ),
                                    )
                                })
                            }
                            "collab_safety_number" -> (navArgs["friend_user_id"] as? String)?.let { uid ->
                                ({ container.navRequests.tryEmit(ShiguangRoute.CollabSafetyNumber(uid)) })
                            }
                            else -> null
                        }
                        if (paramRoute != null) {
                            Handler(Looper.getMainLooper()).post { paramRoute() }
                            mapOf("ok" to true, "route" to page)
                        } else if (page != null && routeRegistry.contains(page)) {
                            Handler(Looper.getMainLooper()).post { routeRegistry.builderFor(page)?.invoke() }
                            mapOf("ok" to true, "route" to page)
                        } else {
                            mapOf("ok" to false, "error" to "unknown_page", "raw" to (raw ?: ""))
                        }
                    }
                    "open_overlay" -> {
                        // 像素采集浮层合成（stage3/4）：name[:id] → 壳层 baselineOverlayBus
                        val name = args["name"] as? String
                        if (name.isNullOrBlank()) {
                            mapOf("ok" to false, "error" to "name 缺失")
                        } else {
                            val id = args["id"] as? String
                            Handler(Looper.getMainLooper()).post {
                                container.baselineOverlayBus.tryEmit(if (id != null) "$name:$id" else name)
                            }
                            mapOf("ok" to true, "overlay" to name)
                        }
                    }
                    "repo_read" -> {
                        val scope = args["scope"] as? String ?: "settings"
                        // 原始 JSON 字符串直传（SDK 对 JsonElement 值 toString 会二次编码
                        // 成带引号原语——2026-11-15 真机实证；驱动侧 json.loads 自解）
                        val payload = kotlinx.coroutines.runBlocking {
                            runCatching {
                                if (scope == "export_all") container.repo.exportAll().toString()
                                else container.repo.settingsAll().toString()
                            }.getOrElse { """{"error":"${it.message}"}""" }
                        }
                        mapOf("ok" to true, "data" to payload)
                    }
                    "repo_set" -> {
                        val values = args["values"] as? Map<*, *>
                        if (values == null) {
                            mapOf("ok" to false, "error" to "values 缺失")
                        } else {
                            kotlinx.coroutines.runBlocking {
                                container.repo.settingsSet(
                                    buildJsonObject {
                                        values.forEach { (k, v) -> put(k.toString(), JsonPrimitive(v.toString())) }
                                    },
                                )
                            }
                            mapOf("ok" to true)
                        }
                    }
                    "gate_clear" -> {
                        kotlinx.coroutines.runBlocking {
                            container.repo.settingsClear("wake_time")
                            container.repo.settingsClear("sleep_time")
                        }
                        mapOf("ok" to true)
                    }
                    "slots_set" -> {
                        val slots = (args["slots"] as? List<*>)?.mapNotNull { row ->
                            val o = row as? Map<*, *> ?: return@mapNotNull null
                            val weekdays = (o["weekdays"] as? List<*>)
                                ?.mapNotNull { (it as? Number)?.toLong() } ?: return@mapNotNull null
                            FixedSlotRef(
                                id = o["id"]?.toString()?.takeIf { it != "null" },
                                name = o["name"]?.toString().orEmpty(),
                                weekdays = weekdays,
                                startMin = (o["start_min"] as? Number)?.toLong() ?: 0L,
                                endMin = (o["end_min"] as? Number)?.toLong() ?: 0L,
                            )
                        }
                        if (slots == null) {
                            mapOf("ok" to false, "error" to "slots 缺失")
                        } else {
                            kotlinx.coroutines.runBlocking {
                                CommandHandler(container.repo).execute(
                                    ScheduleCommand.UpdateFixedSlots(slots = slots),
                                    actor = CommandActor.HUMAN,
                                )
                            }
                            mapOf("ok" to true, "count" to slots.size)
                        }
                    }
                    "share_intake" -> {
                        val text = args["text"] as? String
                        if (text.isNullOrEmpty()) {
                            mapOf("ok" to false, "error" to "text 缺失")
                        } else {
                            // ACTION_SEND intake 同代码路径（forwardSharedText 之后的段）
                            Handler(Looper.getMainLooper()).post { container.postPendingShare(text) }
                            mapOf("ok" to true)
                        }
                    }
                    "artifacts_query" -> {
                        val title = args["title"] as? String
                        if (title.isNullOrEmpty()) {
                            mapOf("ok" to false, "error" to "title 缺失")
                        } else {
                            val rows = kotlinx.coroutines.runBlocking {
                                // 第二句柄只读（repo 无 rawQuery 端口面；双句柄同文件已实证安全）
                                AndroidSqlStore(app).rawQuery(
                                    "SELECT title, category, origin, state, plan_id FROM artifacts WHERE title = ?",
                                    listOf(title),
                                )
                            }
                            // 单元格字符串化（JSON 面友好；驱动断言只比对字面量）
                            mapOf(
                                "ok" to true,
                                "count" to rows.size,
                                "rows" to rows.map { row -> row.entries.associate { (k, v) -> k to v?.toString() } },
                            )
                        }
                    }
                    "plan_seed" -> {
                        // 走查/基线播种：addPlan（愿望池条目）
                        val title = args["title"] as? String ?: "走查计划"
                        val id = kotlinx.coroutines.runBlocking {
                            CommandHandler(container.repo).execute(
                                ScheduleCommand.UpsertPlan(title = title),
                                actor = CommandActor.HUMAN,
                            )
                        }.targetId
                        mapOf("ok" to true, "id" to id)
                    }
                    "block_seed" -> {
                        // 走查/基线播种：place_block（human；confirmed 落库）
                        val label = args["label"] as? String ?: "走查块"
                        val startMin = (args["start_min"] as? Number)?.toLong() ?: 600L
                        val endMin = (args["end_min"] as? Number)?.toLong() ?: 660L
                        // 默认日期=作息日（清晨 <wake 属昨日——今日页显示作息日；
                        // 2026-11-16 验收走查实证：LocalDate.now() 错配即块不可见）
                        val date = args["date"] as? String ?: run {
                            val wake = kotlinx.coroutines.runBlocking {
                                (container.repo.settingsGet("wake_time") ?: "420").toIntOrNull() ?: 420
                            }
                            val now = java.time.LocalDateTime.now()
                            val base = if (now.hour * 60 + now.minute < wake) now.toLocalDate().minusDays(1) else now.toLocalDate()
                            base.toString()
                        }
                        val planId = args["plan_id"] as? String
                        val id = kotlinx.coroutines.runBlocking {
                            CommandHandler(container.repo).execute(
                                ScheduleCommand.PlaceBlock(
                                    date = date, startMin = startMin, endMin = endMin,
                                    planId = planId, label = if (planId == null) label else null,
                                ),
                                actor = CommandActor.HUMAN,
                            )
                        }.targetId
                        mapOf("ok" to true, "target" to id)
                    }
                    "plans_clean" -> {
                        // 走查预清理：按标题删计划（命令层级联——块/子树一并；raw SQL 会撞 FK）
                        val title = args["title"] as? String ?: ""
                        val ids: List<String> = kotlinx.coroutines.runBlocking {
                            (container.repo.listPlans(includeArchived = true) as? JsonArray)
                                ?.filterIsInstance<JsonObject>()
                                ?.filter { (it["title"] as? JsonPrimitive)?.contentOrNull == title }
                                ?.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                        }
                        kotlinx.coroutines.runBlocking {
                            for (id in ids) {
                                runCatching {
                                    CommandHandler(container.repo).execute(
                                        ScheduleCommand.DeletePlan(id),
                                        actor = CommandActor.HUMAN,
                                    )
                                }
                            }
                        }
                        mapOf("ok" to true, "count" to ids.size)
                    }
                    "blocks_clean" -> {
                        val label = args["label"] as? String
                        if (label == null) {
                            mapOf("ok" to false, "error" to "label 缺失")
                        } else {
                        val store = AndroidSqlStore(app)
                        kotlinx.coroutines.runBlocking {
                            store.execute("DELETE FROM schedule_blocks WHERE label = '$label'")
                        }
                        stateRevisionCounter.bump()
                        mapOf("ok" to true)
                        }
                    }
                    "fact_seed" -> {
                        // 走查/基线播种：structured 凭证（moment 锚=当日 nowMin+offset，
                        // 可选 advance）——通关条窗口/通关卡六层装配的确定性数据源
                        val title = args["title"] as? String ?: "走查凭证"
                        val role = args["role"] as? String ?: "departure"
                        val offsetMin = (args["offset_min"] as? Number)?.toLong() ?: 60L
                        val advance = (args["advance"] as? Number)?.toLong()
                        val planId = args["plan_id"] as? String
                        val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                        val anchorMin = ((nowMin + offsetMin).mod(1440))
                        val today = java.time.LocalDate.now().toString()
                        val anchors = listOf(
                            buildJsonObject {
                                put("kind", "moment")
                                put("role", role)
                                put("date", today)
                                put("min", anchorMin)
                            },
                        )
                        val constraints = advance?.let {
                            buildJsonObject { put("advance_arrival_minutes", it) }
                        }
                        val id = kotlinx.coroutines.runBlocking {
                            CommandHandler(container.repo).execute(
                                ScheduleCommand.UpsertFacts(
                                    category = "transit",
                                    sourceKind = "booking",
                                    state = "structured",
                                    origin = "ai",
                                    title = title,
                                    timeAnchors = anchors,
                                    constraints = constraints,
                                    rawText = "走查播种原文：$title",
                                    planId = planId,
                                ),
                                actor = CommandActor.HUMAN,
                            )
                        }.targetId ?: ""
                        mapOf("ok" to true, "id" to id)
                    }
                    "artifacts_clean" -> {
                        val title = args["title"] as? String
                        if (title.isNullOrEmpty()) {
                            mapOf("ok" to false, "error" to "title 缺失")
                        } else {
                            // 走查产物即走即清（title 为驱动字面量，无注入面）
                            val store = AndroidSqlStore(app)
                            kotlinx.coroutines.runBlocking {
                                store.execute("DELETE FROM artifacts WHERE title = '$title'")
                            }
                            stateRevisionCounter.bump()
                            mapOf("ok" to true)
                        }
                    }
                    "relay_login" -> {
                        // B 档播种器（relay 登录态）：走查账号幂等登录——先 login
                        // （已有账号走真实登录路径），账号不存在（首次播种）再
                        // register 兜底；密码错/网络断则诚实回执。已登录其他账号
                        // 先 logout 换装。登录后本地身份缺位（新库/重装）→ 服务端
                        // 金库还原（account 页 restore 同代码路径，MSK 落安全区）。
                        val username = ((args["username"] as? String) ?: "walk_a").trim().lowercase()
                        val password = (args["password"] as? String) ?: "walk-pass-2026"
                        val session = container.accountSession
                        val outcome = runCatching {
                            if (session.isLoggedIn && session.username == username) {
                                "already"
                            } else {
                                if (session.isLoggedIn) {
                                    kotlinx.coroutines.runBlocking { session.logout() }
                                }
                                kotlinx.coroutines.runBlocking { session.login(username, password) }
                                "login"
                            }
                        }.recoverCatching {
                            kotlinx.coroutines.runBlocking { session.register(username, password) }
                            "register"
                        }
                        val restored = if (outcome.isSuccess && session.isLoggedIn) {
                            runCatching {
                                val hasIdentity =
                                    kotlinx.coroutines.runBlocking { container.identityStore.load() } != null
                                if (!hasIdentity) {
                                    kotlinx.coroutines.runBlocking {
                                        session.restoreIdentityFromVault(password, username)
                                    }
                                } else {
                                    true
                                }
                            }.getOrDefault(false)
                        } else {
                            false
                        }
                        mapOf(
                            "ok" to outcome.isSuccess,
                            "mode" to (outcome.getOrNull() ?: ""),
                            "restored" to restored,
                            "status" to session.status.value.name,
                            "user_id" to (session.userId ?: ""),
                            "error" to (outcome.exceptionOrNull()?.let { e ->
                                val body = (e as? app.shiguang.core.model.collab.relay.RelayAuthException)?.body
                                if (body != null) "${e.message} body=$body" else (e.message ?: "")
                            } ?: ""),
                        )
                    }
                    "relay_logout" -> {
                        // 播种收尾：登出（不动本地密钥与协同数据，session 语义）。
                        kotlinx.coroutines.runBlocking { container.accountSession.logout() }
                        mapOf("ok" to true, "status" to container.accountSession.status.value.name)
                    }
                    "relay_status" -> {
                        val s = container.accountSession
                        mapOf(
                            "ok" to true,
                            "status" to s.status.value.name,
                            "username" to (s.username ?: ""),
                            "user_id" to (s.userId ?: ""),
                        )
                    }
                    "mcp_ping" -> {
                        val controller = container.mcpController
                        if (!controller.running.value) {
                            mapOf("ok" to false, "error" to "mcp_not_running")
                        } else {
                            // 连接拒绝/异常回状态串不抛（enable 异步竞态期 ping 会撞 refused）
                            runCatching { rawPing(controller.endpoint, controller.token.value.orEmpty()) }
                                .fold(
                                    onSuccess = { resp ->
                                        mapOf(
                                            "ok" to true,
                                            "status" to resp.substringBefore('|'),
                                            "body" to resp.substringAfter('|', missingDelimiterValue = ""),
                                        )
                                    },
                                    onFailure = { e ->
                                        mapOf("ok" to false, "status" to "error", "body" to "${e.javaClass.simpleName}: ${e.message}")
                                    },
                                )
                        }
                    }
                    "collab_seed" -> {
                        // 协作走查/播种面（T4.9）：包头+步骤+明文骨架事件（cipher=哑元——
                        // audit 半盲面不消费密文；decision 回放解不开=半盲空态即走查对象）
                        val bundleId = args["bundle_id"] as? String ?: "walk-b1"
                        val proposalType = args["proposal_type"] as? String ?: "DIRECTIVE"
                        val status = args["status"] as? String ?: "PROPOSED"
                        val now = System.currentTimeMillis()
                        val store = AndroidSqlStore(app)
                        kotlinx.coroutines.runBlocking {
                            store.execute(
                                "INSERT OR REPLACE INTO collab_bundles (id, proposal_type, status, lifecycle, base_revision, max_seq_id, last_acked_seq, key_version, initiator_id, participants, expires_at, created_at, updated_at) " +
                                    "VALUES ('$bundleId', '$proposalType', '$status', 'active', 1, 4, 0, 1, 'walker-initiator', '[\"u_alice\",\"u_bob\",\"walker-initiator\"]', ${now + 86_400_000}, $now, $now)",
                            )
                            store.execute(
                                "INSERT OR REPLACE INTO collab_steps (bundle_id, id, required_uids, threshold, is_optional, status, votes, sort, starts_at_ms, ends_at_ms, created_at, updated_at) " +
                                    "VALUES ('$bundleId', 's1', '[\"u_alice\",\"u_bob\"]', 1, 0, 'PENDING', '{}', 0, NULL, NULL, $now, $now)",
                            )
                            store.execute(
                                "INSERT OR REPLACE INTO collab_steps (bundle_id, id, required_uids, threshold, is_optional, status, votes, sort, starts_at_ms, ends_at_ms, created_at, updated_at) " +
                                    "VALUES ('$bundleId', 's2', '[\"u_alice\"]', 1, 0, 'READY', '{}', 1, ${now + 3_600_000}, ${now + 7_200_000}, $now, $now)",
                            )
                            val events = listOf(
                                Triple(1L, "PROPOSE", "walker-initiator"),
                                Triple(2L, "ACCEPT", "u_alice"),
                                Triple(3L, "REJECT", "u_bob"),
                                Triple(4L, "BRIEFING_UPDATE", "walker-initiator"),
                            )
                            for ((seq, actionType, sender) in events) {
                                store.execute(
                                    "INSERT OR REPLACE INTO collab_events (bundle_id, seq_id, action_type, sender_id, cipher, key_ver, client_msg_id, topology_ref, server_at_ms, created_at) " +
                                        "VALUES ('$bundleId', $seq, '$actionType', '$sender', 'e30=', 1, 'walk-msg-$seq', NULL, $now, $now)",
                                )
                            }
                            stateRevisionCounter.bump()
                        }
                        mapOf("ok" to true, "bundle_id" to bundleId)
                    }
                    "collab_seed_cipher" -> {
                        // B 档播种器第二版（双账号）：真实好友关系 + 真实 K_bundle 密文事件——
                        // 替换 collab_seed 的 e30= 哑元，供 S1 正常同步态采集与回放对拍。
                        val bundleId = (args["bundle_id"] as? String)?.takeIf { it.isNotBlank() } ?: "walk-cipher"
                        val userA = ((args["username_a"] as? String) ?: "walk_a").trim().lowercase()
                        val userB = ((args["username_b"] as? String) ?: "walk_b").trim().lowercase()
                        val password = (args["password"] as? String) ?: "walk-pass-2026"
                        val seed = runCatching {
                            kotlinx.coroutines.runBlocking {
                                seedLogin(container, userA, password)
                                val aId = container.accountSession.userId
                                    ?: throw IllegalStateException("A 账号 user_id 缺失")
                                seedLogin(container, userB, password)
                                val bId = container.accountSession.userId
                                    ?: throw IllegalStateException("B 账号 user_id 缺失")

                                seedLogin(container, userA, password)
                                val request = runCatching {
                                    container.loadFriendsSession()?.requestFriendByUsername(userB)
                                }.recoverCatching { e -> if (alreadyLinked(e)) "already" else throw e }.getOrNull()

                                seedLogin(container, userB, password)
                                val accept = runCatching {
                                    container.loadFriendsSession()?.acceptInviteFromPeer(aId, true)
                                }.recoverCatching { e -> if (alreadyLinked(e)) "already" else throw e }.getOrNull()
                                val friendsB = container.loadFriendsSession()?.listFriends()?.count { it.isAccepted } ?: 0

                                seedLogin(container, userA, password)
                                val friendsA = container.loadFriendsSession()?.listFriends()?.count { it.isAccepted } ?: 0

                                val now = System.currentTimeMillis()
                                val repo = container.collabRepository
                                repo.ensureBundle(bundleId, "DIRECTIVE", aId, listOf(aId, bId), now + 86_400_000, 1)
                                val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                container.bundleKeySource.persist(bundleId, 1, key)
                                repo.upsertSteps(
                                    bundleId,
                                    listOf(
                                        CollabStep(bundleId, "s1", listOf(aId, bId), 1, false, "READY", emptyMap(), 0, now + 3_600_000, now + 7_200_000),
                                        CollabStep(bundleId, "s2", listOf(bId), 1, true, "PENDING", emptyMap(), 1, now + 10_800_000, now + 14_400_000),
                                    ),
                                )
                                val events = listOf("PROPOSE" to aId, "ACCEPT" to bId, "VOTE" to aId)
                                events.forEachIndexed { i, (action, sender) ->
                                    val seq = i + 1L
                                    val plain = seedPayload(action, aId, bId, now).toByteArray(Charsets.UTF_8)
                                    val sealed = sealPayload(key, plain, 1)
                                    repo.recordEvent(
                                        bundleId = bundleId,
                                        seqId = seq,
                                        actionType = action,
                                        senderId = sender,
                                        cipher = sealed.encode().toByteArray(Charsets.UTF_8),
                                        keyVer = 1,
                                        clientMsgId = "seed-$bundleId-$seq",
                                        serverAtMs = now,
                                    )
                                }
                                mapOf(
                                    "ok" to true,
                                    "bundle_id" to bundleId,
                                    "user_id_a" to aId,
                                    "user_id_b" to bId,
                                    "request" to (request ?: "null"),
                                    "accept" to (accept ?: "null"),
                                    "friends_a" to friendsA,
                                    "friends_b" to friendsB,
                                    "events" to events.size,
                                )
                            }
                        }
                        seed.fold(
                            onSuccess = {
                                stateRevisionCounter.bump()
                                it
                            },
                            onFailure = { e ->
                                mapOf(
                                    "ok" to false,
                                    "error" to "${e.javaClass.simpleName}: ${e.message}",
                                    "bundle_id" to bundleId,
                                )
                            },
                        )
                    }
                    "collab_clean" -> {
                        // 走查产物即走即清（walk- 前缀驱动字面量族，无业务数据触碰面）
                        val store = AndroidSqlStore(app)
                        kotlinx.coroutines.runBlocking {
                            // 外键序：子表全清后才可删 collab_bundles（key_ring/steps/events
                            // 均REFERENCES bundles——bundles 先删即 FK 787，采图轮实证）
                            store.execute("DELETE FROM collab_events WHERE bundle_id LIKE 'walk-%'")
                            store.execute("DELETE FROM collab_steps WHERE bundle_id LIKE 'walk-%'")
                            store.execute("DELETE FROM collab_key_ring WHERE bundle_id LIKE 'walk-%'")
                            store.execute("DELETE FROM local_outbox_events WHERE bundle_id LIKE 'walk-%'")
                            store.execute("DELETE FROM collab_block_refs WHERE bundle_id LIKE 'walk-%'")
                            store.execute("DELETE FROM collab_bundles WHERE id LIKE 'walk-%'")
                        }
                        stateRevisionCounter.bump()
                        mapOf("ok" to true)
                    }
                    "gesture_inject" -> {
                        // S4 交互对拍面：精确 DOWN/MOVE/UP 时序注入（起点=窗口坐标 px，
                        // hold_ms 控长按窗口、steps/step_interval_ms 控吸附临界拖拽速度）
                        val x = (args["x"] as? Number)?.toFloat()
                        val y = (args["y"] as? Number)?.toFloat()
                        if (x == null || y == null) {
                            mapOf("ok" to false, "error" to "missing_xy", "hint" to "需 x/y 起点（窗口坐标 px）")
                        } else {
                            injectPreciseGesture(
                                activity = activity,
                                x = x, y = y,
                                dx = (args["dx"] as? Number)?.toFloat() ?: 0f,
                                dy = (args["dy"] as? Number)?.toFloat() ?: 0f,
                                holdMs = (args["hold_ms"] as? Number)?.toLong() ?: 0L,
                                steps = (args["steps"] as? Number)?.toInt() ?: 12,
                                stepIntervalMs = (args["step_interval_ms"] as? Number)?.toLong() ?: 16L,
                            )
                        }
                    }
                    else -> actions.execute(action, args)
                }
            },
            screenshotProvider = {
                // 不冒充空图（DebugBridgeWiring 同口径）：PixelCopy 失败即抛，端点回错
                DebugScreenshot.capture(activity) ?: throw IllegalStateException("截图失败：PixelCopy 空返回")
            },
        )
        try {
            bridge.start(port = 0)
            Log.i(TAG, "[debug-bridge][native] token=${bridge.token}")
        } catch (e: Exception) {
            Log.w(TAG, "[debug-bridge][native] start failed: $e")
        }
    }

    /** 宿主进程内回环 ping（Content-Length 标准帧；返回 "status|body"）。 */
    private fun rawPing(endpoint: String, token: String): String {
        val port = java.net.URI(endpoint).port
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        java.net.Socket("127.0.0.1", port).use { s ->
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(s.outputStream, Charsets.UTF_8))
            w.write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            w.write("x-api-key: $token\r\n")
            w.write("Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n")
            w.write(body)
            w.flush()
            val r = java.io.BufferedReader(java.io.InputStreamReader(s.inputStream, Charsets.UTF_8))
            var len = 0
            var statusLine = ""
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) break
                if (statusLine.isEmpty()) statusLine = line
                if (line.lowercase().startsWith("content-length:")) len = line.substringAfter(':').trim().toInt()
            }
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = r.read(buf, read, len - read)
                if (n == -1) break
                read += n
            }
            return "${statusLine.split(" ").getOrNull(1) ?: "?"}|${String(buf, 0, read)}"
        }
    }

    /** B 档播种专用：切换账号并还原各自身份金库——本地 IdentityRecord 是单槽，
     *  不清会串号；会话 user_id 必须来自当前账号。 */
    private suspend fun seedLogin(container: AppContainer, username: String, password: String): String {
        val session = container.accountSession
        if (session.isLoggedIn) session.logout()
        container.identityStore.clear()
        val mode = runCatching { session.login(username, password); "login" }
            .recoverCatching { e1 ->
                // register 兜底；注册撞 409（账号已在）说明 login 首试瞬时失败——
                // 回退再试一次 login（采图轮实证：relay 抖动下 login 偶发超时/失败）。
                runCatching { session.register(username, password); "register" }
                    .recoverCatching { e2 ->
                        if (e2 is app.shiguang.core.model.collab.relay.RelayAuthException && e2.statusCode == 409) {
                            session.login(username, password); "login-retry"
                        } else throw e2
                    }
                    .getOrThrow()
            }
            .getOrThrow()
        val hasIdentity = session.restoreIdentityFromVault(password, username) ||
            container.identityStore.load() != null
        if (!hasIdentity) throw IllegalStateException("身份金库还原失败：$username")
        return mode
    }

    /** 好友关系幂等：409=已存在，404=邀请已被处置。 */
    private fun alreadyLinked(e: Throwable): Boolean =
        e is RelayFriendsException && (e.statusCode == 409 || e.statusCode == 404)

    /** FSM 回放器 signalFromPayload 认的载荷口径（与生产事件同形）。 */
    private fun seedPayload(action: String, aId: String, bId: String, now: Long): String =
        when (action) {
            "PROPOSE" -> buildJsonObject {
                put("proposal_type", "DIRECTIVE")
                putJsonArray("skeleton") {
                    add(buildJsonObject {
                        put("step_id", "s1")
                        putJsonArray("required_uids") { add(JsonPrimitive(aId)); add(JsonPrimitive(bId)) }
                        put("threshold", 1)
                        put("is_optional", false)
                        put("starts_at_ms", now + 3_600_000)
                        put("ends_at_ms", now + 7_200_000)
                    })
                    add(buildJsonObject {
                        put("step_id", "s2")
                        putJsonArray("required_uids") { add(JsonPrimitive(bId)) }
                        put("threshold", 1)
                        put("is_optional", true)
                        put("starts_at_ms", now + 10_800_000)
                        put("ends_at_ms", now + 14_400_000)
                    })
                }
                putJsonArray("participants") { add(JsonPrimitive(aId)); add(JsonPrimitive(bId)) }
                putJsonArray("initial_slots") { add(JsonPrimitive("slot-1")) }
                put("quorum", 1)
                put("expires_at_ms", now + 86_400_000)
            }.toString()
            "ACCEPT" -> buildJsonObject { put("step_id", "s1") }.toString()
            "VOTE" -> buildJsonObject { put("slot_id", "slot-1"); put("preference", "PREFER") }.toString()
            else -> "{}"
        }
}
