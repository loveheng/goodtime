package app.shiguang.shiguang.ui.plan

import app.shiguang.core.model.ScheduleDay
import java.time.LocalDate

/**
 * 计划详情族纯函数件（behavior/{plan_detail,plan_background,plan_facts}.md 转正清单
 * §A/§B/§C 逐字口径，D24 过目转正 2026-11-15）——零 Compose 依赖，JVM 可测。
 */

/**
 * spec 尾部路线图块进度（Dart _roadmapProgress 逐字镜像）：定位「## 路线图」→
 * 截到下一「\n## 」（无则到尾）→ Checklist 记号计数（multiLine 行首 `- [x]`/
 * `- [ ]`）→ done+open==0 返回 null；显示串「当前进度 done/total」。
 * CheckList 记号=spec 内容书写约定非渲染形态（ui-pages §5，D24-F1 修规范）。
 */
fun roadmapProgress(spec: String): String? {
    val idx = spec.indexOf("## 路线图")
    if (idx < 0) return null
    var section = spec.substring(idx)
    val next = section.indexOf("\n## ", 1)
    if (next >= 0) section = section.substring(0, next)
    val done = Regex("(?m)^\\s*-\\s+\\[x\\]").findAll(section).count()
    val open = Regex("(?m)^\\s*-\\s+\\[ \\]").findAll(section).count()
    if (done + open == 0) return null
    return "当前进度 $done/${done + open}"
}

/**
 * 背景条目日期角标（Dart _badge 逐字镜像）：[MM.dd] 单日 / [MM.dd–MM.dd] 区间
 * （月日两位补零；调用方保证 dates 非空；解析失败原文回落——Dart tryParseIsoDate
 * null 分支同构）。
 */
fun backgroundBadge(dates: List<String>): String? {
    if (dates.isEmpty()) return null
    fun f(iso: String): String {
        val d = ScheduleDay.tryParseIsoDate(iso) ?: return iso
        return "%02d.%02d".format(d.monthValue, d.dayOfMonth)
    }
    return if (dates.size == 1) "[${f(dates.first())}]"
    else "[${f(dates.first())}–${f(dates.last())}]"
}

/** 日期窗逐日 ISO 枚举（plan_background.md §C 保存口径：s..e 闭区间逐日）。 */
fun dateWindowDays(start: LocalDate, end: LocalDate): List<String> {
    val out = mutableListOf<String>()
    var cur = start
    while (!cur.isAfter(end)) {
        out.add(ScheduleDay.isoDate(cur))
        cur = cur.plusDays(1)
    }
    return out
}

/** 标签切分（Dart `split(RegExp(r'[,,，、\s]+'))` 去空 trim 逐字镜像）。 */
fun splitTags(raw: String): List<String> =
    raw.split(Regex("[,,，、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
