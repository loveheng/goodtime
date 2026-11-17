package app.shiguang.shiguang.ui.timeline

import app.shiguang.core.model.ScheduleBlock

/** 车道分栏布局结果项：块 + 车道号 + 等宽车道宽（画布均分）。 */
data class LaneSlot(val block: ScheduleBlock, val lane: Int, val laneWidthDp: Float)

/**
 * 时间轴车道分栏（§6.5 重叠分栏 M1 泛化；ui-design §3.4 D12 写回「等宽分栏 N≥2」）：
 * 按 startMin 排序切重叠簇（簇内任一块开始 ≥ 前簇最大结束则切簇）+ 簇内 greedy 车道
 * （排进第一条「车道末 ≤ 本块开始」的车道；跨午夜 endAbs=end≤start?end+1440:end）。
 * 纯逻辑 JVM 对拍件（Dart _layoutLanes/_assignLanes 逐字镜像）。
 */
object LaneLayout {

    /** 簇内 greedy 车道分配（块 id → lane）。 */
    fun assignLanes(cluster: List<ScheduleBlock>): Map<String, Int> {
        val laneEnds = mutableListOf<Long>()
        val lanes = LinkedHashMap<String, Int>()
        for (b in cluster) {
            val endAbs = if (b.endMin <= b.startMin) b.endMin + 1440 else b.endMin
            var lane = 0
            while (lane < laneEnds.size && laneEnds[lane] > b.startMin) lane++
            if (lane == laneEnds.size) laneEnds.add(endAbs) else laneEnds[lane] = endAbs
            lanes[b.id!!] = lane
        }
        return lanes
    }

    /** 重叠簇切分 + 等宽分栏：簇内最大车道数均分画布宽（块宽−2dp 留缝由装配层扣）。 */
    fun layout(blocks: List<ScheduleBlock>, timelineWidthDp: Float): List<LaneSlot> {
        val sorted = blocks.sortedBy { it.startMin }
        val out = mutableListOf<LaneSlot>()
        var cluster = mutableListOf<ScheduleBlock>()
        var clusterEnd = -1L
        fun flush() {
            if (cluster.isEmpty()) return
            val lanes = assignLanes(cluster)
            val maxLane = (lanes.values.maxOrNull() ?: -1) + 1
            val width = timelineWidthDp / maxLane
            for (b in cluster) out.add(LaneSlot(b, lanes[b.id!!] ?: 0, width))
            cluster = mutableListOf()
            clusterEnd = -1L
        }
        for (b in sorted) {
            if (cluster.isNotEmpty() && b.startMin >= clusterEnd) flush()
            cluster.add(b)
            val endAbs = if (b.endMin <= b.startMin) b.endMin + 1440 else b.endMin
            if (endAbs > clusterEnd) clusterEnd = endAbs
        }
        flush()
        return out
    }
}
