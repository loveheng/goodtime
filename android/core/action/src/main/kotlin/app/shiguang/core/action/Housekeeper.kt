package app.shiguang.core.action

import app.shiguang.core.model.ScheduleDay
import app.shiguang.core.model.ScheduleRules
import app.shiguang.core.model.SettingsKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/**
 * 中央管家（schedule-app §8；lib/service/housekeeper.dart Kotlin 等价，行为清单
 * fgs_housekeeper.md 过目 2026-11-15）：确定性时间驱动状态迁移与漂移浮出，
 * 无 LLM、不做判断——「需要判断」的部分浮出给桌面 AI（晨间 digest 见 queries）。
 */
class Housekeeper(private val repo: BridgeRepositoryPort) {

    /** 日切计数体（Dart record (missed, voided, archived, protected) 同形）。 */
    data class DailyCutStats(
        val missed: Int,
        val voided: Int,
        val archived: Int,
        val protected: Boolean,
    )

    /**
     * 日切扫描（幂等，wake_time 切割 §11）：回扫 [ScheduleRules.DAILY_CUT_LOOKBACK_DAYS]
     * 天（设备数日未开机也能补齐）——
     * ① 已确认块错过日切 → **missed**（可补勾，宽容视窗；补记走 tick_block）；
     *    但 Clean Slate 保护期（[ScheduleQueries.isBreakdown]）或庆祝块 → 静默
     *    **archived**（中性淡出，不计入复盘聚合——「破罐破摔」防线，绝不显红）；
     * ② 未确认提案块过期 → **作废**（物理删除：提案从未成为现实，AI 可整单重提）。
     * 顺延记账（postpone_count）在 shift_block 命令内完成，此处不重复。
     * ⚠ wake 缺省 ??420（与 isBreakdown 的 ??0 缺省差逐字保留）。
     */
    suspend fun dailyCut(now: LocalDateTime? = null): DailyCutStats {
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 420
        val n = now ?: LocalDateTime.now()
        val today = ScheduleDay.scheduleDayOf(n, wake)
        val stale = repo.blocksInRange(
            ScheduleDay.isoDate(today.minusDays(ScheduleRules.DAILY_CUT_LOOKBACK_DAYS.toLong())),
            ScheduleDay.isoDate(today.minusDays(1)),
        ).jsonArray.mapNotNull { it as? JsonObject }
        val cleanSlate = ScheduleQueries(repo).isBreakdown(now = n)
        var missed = 0
        var voided = 0
        var archived = 0
        for (b in stale) {
            val id = (b["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val status = (b["status"] as? JsonPrimitive)?.contentOrNull
            val isCelebration = (b["is_celebration"] as? JsonPrimitive)?.booleanOrNull ?: false
            when (status) {
                "confirmed" -> if (isCelebration || cleanSlate) {
                    repo.patchBlock(id, buildJsonObject { put("status", "archived") }, null)
                    archived++
                } else {
                    repo.patchBlock(id, buildJsonObject { put("status", "missed") }, null)
                    missed++
                }
                "proposed" -> if (isCelebration) {
                    repo.patchBlock(id, buildJsonObject { put("status", "archived") }, null)
                    archived++
                } else {
                    repo.deleteBlock(id)
                    voided++
                }
            }
        }
        return DailyCutStats(missed, voided, archived, cleanSlate)
    }

    /** 冷启动日切：每作息日至多执行一次（app_settings 内部键 last_daily_cut）。 */
    suspend fun dailyCutIfNeeded(now: LocalDateTime? = null) {
        val n = now ?: LocalDateTime.now()
        val raw = repo.settingsAll().toStringMap()
        val wake = SettingsKeys.intOf(raw, SettingsKeys.WAKE_TIME) ?: 420
        val today = ScheduleDay.isoDate(ScheduleDay.scheduleDayOf(n, wake))
        if (raw["last_daily_cut"] == today) return
        dailyCut(now = n)
        repo.settingsSet(buildJsonObject { put("last_daily_cut", JsonPrimitive(today)) })
    }

    private fun JsonObject.toStringMap(): Map<String, String> =
        keys.associateWith { k -> (this[k] as? JsonPrimitive)?.contentOrNull ?: "" }
}
