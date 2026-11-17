package app.shiguang.core.action

import app.shiguang.core.model.Artifact
import app.shiguang.core.model.ArtifactRules
import app.shiguang.core.model.ScheduleDay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 事实凭证规则引擎（lib/action/fact_rules.dart Kotlin 等价，fact-user-relay-draft.md
 * §9.1/§9.2/§9.3）。纯函数模块，无 IO 依赖。
 *
 * 本轮（2026-11-14）补全硬红线族：rule_scope 生效派生 + factRuleViolations 对单块
 * （forbidden_weekdays / daily_deadline_min）+ subtreeArtifacts 子树聚合。
 * 临场通关条时间合成（computeActionPasses）属 P3 UI 面——行为清单过目后平移。
 */
object FactRules {

    /** rule 类锚所属凭证建档必须显式给出生效窗（§9.2 Q-A：漏填即拒，杜绝永久生效误杀）。 */
    fun ruleScopeComplete(ruleScope: JsonObject?): Boolean {
        if (ruleScope == null) return false
        val from = (ruleScope["valid_from"] as? JsonPrimitive)?.contentOrNull
        val to = (ruleScope["valid_to"] as? JsonPrimitive)?.contentOrNull
        return ScheduleDay.tryParseIsoDate(from ?: "") != null &&
            ScheduleDay.tryParseIsoDate(to ?: "") != null
    }

    /** 派生生效判定（不入库）：date ∈ [valid_from, valid_to] 闭区间即生效；无窗=长期有效。 */
    fun ruleScopeActive(ruleScope: JsonObject?, date: LocalDate): Boolean {
        if (ruleScope == null) return true
        val from = ScheduleDay.tryParseIsoDate(
            (ruleScope["valid_from"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
        val to = ScheduleDay.tryParseIsoDate(
            (ruleScope["valid_to"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
        if (from == null && to == null) return true
        if (from != null && date.isBefore(from)) return false
        if (to != null && date.isAfter(to)) return false
        return true
    }

    // ── 临场通关条族（behavior/action_pass_bar.md 转正清单 §A/§B，D25 过目 2026-11-15）──

    /** moment.role 受控枚举（§9.1）：行动类 4 个参与临场触发，信息类 2 个仅展示。 */
    val ACTIONABLE_ROLES = setOf("departure", "deadline", "checkin", "checkout")

    /**
     * 解析 role（§9.1 存量兼容）：未知/空 → reference（不触发），并记录 [DEGRADE]
     * （存量中文 role 读侧容错，不迁移不改写 payload）。
     */
    fun normalizeRole(raw: String?): String {
        if (raw == null) return "reference"
        if (raw in ACTIONABLE_ROLES || raw == "arrival" || raw == "reference") return raw
        println("[DEGRADE][fact_rules] 未知 moment role=$raw → 按 reference 处理（不触发临场条）")
        return "reference"
    }

    /** role → 白话标签（§9.1，UI 出白话不出枚举）。未知/信息类兜底「参考」。 */
    fun roleLabel(role: String?): String = when (normalizeRole(role)) {
        "departure" -> "发车/起飞"
        "deadline" -> "停止检票"
        "checkin" -> "开放入住"
        "checkout" -> "退房"
        "arrival" -> "到达"
        else -> "参考"
    }
}

/** 余生事实红线命中（硬红线对单块）。 */
class FactViolation(val fact: JsonObject, val reason: String)

private fun forbiddenWeekdays(a: JsonObject): List<Int>? {
    val c = (a["payload"] as? JsonObject)?.get("constraints") as? JsonObject ?: return null
    val v = c["forbidden_weekdays"] as? JsonArray ?: return null
    return v.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
}

private fun dailyDeadlineMin(a: JsonObject): Int? =
    (((a["payload"] as? JsonObject)?.get("constraints") as? JsonObject)
        ?.get("daily_deadline_min") as? JsonPrimitive)?.longOrNull?.toInt()

private fun factTitle(a: JsonObject): String =
    (a["title"] as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * 硬红线校验（§9.3）：给定块的归属日历日与起止分钟，对一组事实（通常=本 plan
 * 及递归子树的 structured 凭证 MCP 行）逐条判定。weekday 一律取块 date（开始日），
 * 跨午夜块溢出次日不校（与「按开始日归属」单一真相一致）。
 */
fun factRuleViolations(
    facts: List<JsonObject>,
    blockDate: LocalDate,
    startMin: Int,
    endMin: Int,
): List<FactViolation> {
    val weekday = blockDate.dayOfWeek.value // 1=周一
    val out = mutableListOf<FactViolation>()
    for (f in facts) {
        if ((f["state"] as? JsonPrimitive)?.contentOrNull != Artifact.STATE_STRUCTURED) continue
        val scope = (f["payload"] as? JsonObject)?.get("rule_scope") as? JsonObject
        if (!FactRules.ruleScopeActive(scope, blockDate)) continue
        val fwd = forbiddenWeekdays(f)
        if (fwd != null && fwd.contains(weekday)) {
            out.add(FactViolation(f, "${factTitle(f)}：${weekdayName(weekday)}禁止（规则提醒）"))
        }
        val dl = dailyDeadlineMin(f)
        if (dl != null && startMin > dl) {
            out.add(FactViolation(f, "${factTitle(f)}：每日 ${clockOf(dl)} 后停止受理"))
        }
    }
    return out
}

/** 本 plan 及递归子树的 artifacts（§9.3：复用内存递归，深度 ≤3；state 白名单由 factRuleViolations 再筛）。 */
internal suspend fun subtreeArtifacts(repo: BridgeRepositoryPort, rootPlanId: String): List<JsonObject> {
    val ids = mutableListOf(rootPlanId)
    suspend fun walk(parentId: String, depth: Int) {
        if (depth > 3) return
        val children = (repo.listPlans(parentId = parentId) as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (k in children) {
            val id = (k["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            ids.add(id)
            walk(id, depth + 1)
        }
    }
    walk(rootPlanId, 1)
    return (repo.artifactsForPlans(ids) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}

private fun weekdayName(weekday: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[weekday - 1]

// ── 一致性派生与临场通关条（behavior/{fact_sheet,action_pass_bar}.md 转正清单，D25）──

/** 折算至绝对时间轴的 span 区段（贪心选锚用）。 */
internal class AnchorSpan(val start: LocalDateTime, val end: LocalDateTime)

/** 锚点绝对时刻折算（date+min，任一缺=null——Dart _abs 逐字镜像）。 */
internal fun anchorAbs(date: String?, min: Long?): LocalDateTime? {
    val d = ScheduleDay.tryParseIsoDate(date ?: "") ?: return null
    return d.atStartOfDay().plusMinutes(min ?: return null)
}

/** span 与块的重叠分钟数（≤0 表示不重叠；负值由 `ov > 0` 判据消化——Dart 同构）。 */
private fun overlapMinutes(s: AnchorSpan, b0: LocalDateTime, b1: LocalDateTime): Int =
    java.time.Duration.between(if (s.start.isAfter(b0)) s.start else b0, if (s.end.isBefore(b1)) s.end else b1)
        .toMinutes().toInt()

private fun payloadOf(a: JsonObject): JsonObject? = a["payload"] as? JsonObject

/** constraints.advance_arrival_minutes（int 才取——Dart `v is int ? v : null` 镜像）。 */
fun advanceArrivalMinutes(a: JsonObject): Int? =
    ((payloadOf(a)?.get("constraints") as? JsonObject)
        ?.get("advance_arrival_minutes") as? JsonPrimitive)?.longOrNull?.toInt()

/** Artifact 模型入口（页侧已解析形态）。 */
fun advanceArrivalMinutes(a: app.shiguang.core.model.Artifact): Int? = advanceArrivalMinutes(a.payload)

/**
 * 凭证锚点集合（kind 过滤前按 Dart `raw is! Map continue` 口径取对象行）。
 */
internal fun anchorRows(payload: JsonObject): List<JsonObject> =
    (payload["time_anchors"] as? JsonArray)
        ?.filterIsInstance<JsonObject>() ?: emptyList()

/**
 * §1.4 一致性派生比对（**选锚规则=重叠度贪心**，§9.5-5 2026-10-08 拍板；behavior/
 * fact_sheet.md §A 转正清单）：绑定已反转至块侧（fact §9.5-6）——块 null 即静默
 * 不检（日切删块悬空 §9.5-8）；span 锚与关联块起止（跨午夜按绝对时刻折算，严禁
 * 同日直接比分钟）不一致 → 琥珀条。多 span（往返票/入住+预约段）只比对选中那条：
 * 与块重叠区间最大者优先；均不重叠时取起始最早者。
 */
/** Artifact 模型入口（页侧已解析形态——payload 委托同实现）。 */
fun factInconsistentWithBlock(a: app.shiguang.core.model.Artifact, block: app.shiguang.core.model.ScheduleBlock?): Boolean =
    factInconsistentImpl(a.payload, block)

/** 凭证 MCP 行入口（FactRules 族统一行形——payload 由此提取）。 */
fun factInconsistentWithBlock(a: JsonObject, block: app.shiguang.core.model.ScheduleBlock?): Boolean =
    factInconsistentImpl(payloadOf(a) ?: JsonObject(emptyMap()), block)

private fun factInconsistentImpl(payload: JsonObject, block: app.shiguang.core.model.ScheduleBlock?): Boolean {
    if (block == null) return false
    val bDate = ScheduleDay.tryParseIsoDate(block.date) ?: return false
    val b0 = bDate.atStartOfDay().plusMinutes(block.startMin)
    var b1 = bDate.atStartOfDay().plusMinutes(block.endMin)
    if (b1.isBefore(b0)) b1 = b1.plusDays(1)
    val spans = mutableListOf<AnchorSpan>()
    for (o in anchorRows(payload)) {
        if ((o["kind"] as? JsonPrimitive)?.contentOrNull != "span") continue
        val s0 = anchorAbs(
            (o["date"] as? JsonPrimitive)?.contentOrNull,
            (o["start_min"] as? JsonPrimitive)?.longOrNull,
        ) ?: continue
        var s1 = anchorAbs(
            (o["end_date"] as? JsonPrimitive)?.contentOrNull ?: (o["date"] as? JsonPrimitive)?.contentOrNull,
            (o["end_min"] as? JsonPrimitive)?.longOrNull,
        ) ?: continue
        if (s1.isBefore(s0)) s1 = s1.plusDays(1)
        spans.add(AnchorSpan(s0, s1))
    }
    if (spans.isEmpty()) return false
    var best: AnchorSpan? = null
    var bestOverlap = 0
    for (s in spans) {
        val ov = overlapMinutes(s, b0, b1)
        if (ov > 0 && (best == null || ov > bestOverlap)) {
            best = s
            bestOverlap = ov
        }
    }
    // 均不重叠 → 起始最早者
    best = best ?: spans.reduce { x, y -> if (x.start.isBefore(y.start)) x else y }
    return best.start != b0 || best.end != b1
}

/** 临场通关条候选项（§9.1/§9.4；fact=structured 凭证 MCP 行）。 */
class ActionPass(
    val fact: app.shiguang.core.model.Artifact,
    val role: String, // 归一后的受控枚举值
    val actionTime: LocalDateTime,
    val triggerTime: LocalDateTime,
    val dismissTime: LocalDateTime,
    val title: String,
)

/**
 * 临场通关条候选计算（behavior/action_pass_bar.md 转正清单 §A 逐字镜像）：
 * 扫描 structured 事实的 moment 锚，只取行动类 role；按分层时相公式合成窗口
 * （T_target=T_action−advance / T_trigger=T_target−120 / T_dismiss=T_action+20，
 * 全角色统一——2026-10-09 去 2×2 特判），仅返回 [trigger, dismiss] 闭区间含
 * [nowLocal] 的条目，actionTime 升序。**本地物理时钟判定（禁 toUtc——旅行跨
 * 时区错位）**：nowLocal 由调用方注入（页侧=LocalDateTime.now()）。
 */
fun computeActionPasses(facts: List<JsonObject>, nowLocal: LocalDateTime): List<ActionPass> {
    val out = mutableListOf<ActionPass>()
    for (f in facts) {
        if ((f["state"] as? JsonPrimitive)?.contentOrNull != Artifact.STATE_STRUCTURED) continue
        for (o in anchorRows(payloadOf(f) ?: JsonObject(emptyMap()))) {
            if ((o["kind"] as? JsonPrimitive)?.contentOrNull != "moment") continue
            val role = FactRules.normalizeRole((o["role"] as? JsonPrimitive)?.contentOrNull)
            if (role !in FactRules.ACTIONABLE_ROLES) continue
            val tAction = anchorAbs(
                (o["date"] as? JsonPrimitive)?.contentOrNull,
                (o["min"] as? JsonPrimitive)?.longOrNull,
            ) ?: continue
            val advance = advanceArrivalMinutes(f) ?: 0
            val tTarget = tAction.minusMinutes(advance.toLong())
            val tTrigger = tTarget.minusMinutes(ArtifactRules.PASS_LEAD_MINUTES.toLong())
            val tDismiss = tAction.plusMinutes(ArtifactRules.PASS_DISMISS_AFTER_MINUTES.toLong())
            if (!nowLocal.isBefore(tTrigger) && !nowLocal.isAfter(tDismiss)) {
                out.add(ActionPass(fact = app.shiguang.core.model.Artifact.fromMap(f), role = role, actionTime = tAction, triggerTime = tTrigger, dismissTime = tDismiss, title = factTitle(f)))
            }
        }
    }
    out.sortBy { it.actionTime }
    return out
}

private fun clockOf(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
