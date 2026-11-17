---
status: active
updated: 2026-11-16
---

# 行为清单 · collab_canvas（发起人排期画板 A 页，协作九页施工输入）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/collab/collab_canvas_page.dart`（360 行全文）+ `lib/collab/freemask.dart`（freeMaskBitwiseAnd）+ ui-pages §8.3 + 协作设计 §3.6（Quorum Density Bar/私事幽灵图层）+ Kotlin 就位面（FreeMask.kt=freeMaskBitwiseAnd 已平移且 GoldenE2eeTest freemask 向量覆盖）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 发起人排期画板（§11.2 A 页）：①双轨切换（团队大盘/个人总览）；②全员共振条——按 FreeMask 逐 30min 桶统计「几人有空」，人越多越绿，全员交集桶高亮描边；③私事幽灵图层——个人总览轨上发起人私事区间以半透明琥珀斜纹投影，与候选块重叠只给温和提示**绝不断言冲突**（决断权在发起人，弱占位不参与忙碌回填） |
| 触发与阈值 | 点按类：SegmentedButton 双轨切换（唯一交互件；画布与共振条均只读零点按）。无手势数值阈值 |
| 状态空间 | B 档 S1–S4：S1=画板态（步骤块列表+共振条）；S2=空态（无已回填时段步骤：「暂无已回填时段的步骤」textSecondary 居中）；S3=断连（壳层横幅；本页零网络——掩码与私事区间由调用方注入）；S4=装配中（FutureBuilder 未落：整页 CircularProgressIndicator）；共振条降级态=掩码空→灰底 24dp 条「暂无成员空闲数据」12sp |
| 落库命令与拒绝码 | 零落库零命令（纯渲染页；时段=CollabRepository.listSteps 已物化投影，掩码/私事区间由调用方注入）。零拒绝码 |
| 反馈形式 | 零即时反馈（只读）；幽灵图层=纯视觉投影不拦下层交互 |
| 黄金向量指针 | e2ee_freemask_vectors.json（freeMaskBitwiseAnd 全员交集逐位语义源，GoldenE2eeTest 已消费）；零新向量（quorumDensity 强度统计=UI 层纯函数，随施工轮 JVM 测试钉住） |
| 像素基线指针 | B 档 collab_canvas 4 态——**manifest 现无本组，协作轮补采**（需掩码+私事区间播种器） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 步骤块 | listSteps(bundleId) 中 startsAtMs!=null && endsAtMs!=null 的步骤；subtitle「HH:mm–HH:mm」（双位补零） |
| 共振条 | quorumDensity(masks)：桶 i 有空人数=逐掩码 '1' 计数（长度不齐取最长防越界=调用方 bug 容错）；着色 alpha=0.15+0.85*(density[i]/memberCount)（memberCount<=0 取 1）；全员交集桶=allFree[i]=='1' 描边 1.5px sparkStroke；freeMaskBitwiseAnd 抛异常（长度不齐）→allFree='' 降级不阻塞渲染 |
| 幽灵图层 | privateBusy 区间 [(start,end)] 线性映射窗口（clamp 0..1）；窗口跨度<=0 取 1 防除零；区间 x1<=x0 跳过 |
| 窗口参数 | windowStartMs/windowEndMs 由调用方注入（本页不自算）；桶宽=Expanded 均分+横向 margin 0.5 |
| Kotlin 装配 | FreeMask.freeMaskBitwiseAnd 已平移（:core:model/collab/FreeMask.kt）；quorumDensity 随施工轮落 :core:model 或页内纯函数（JVM 可测）；调用方=决策矩阵物化回填后入口（**现状无入口，见 F1**） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「排期画板」 |
| 双轨切换 | SegmentedButton「团队大盘」/「个人总览」（padding(16,12,16,0)） |
| 共振条 | 区标签「全员共振条」13sp w600 textSecondary+6+24dp 高横条：逐桶 Container（绿 alpha 梯度+圆角 2+交集桶描边）；降级态=灰 200 底圆角 6 居中文案 |
| 画布 | Stack：个人总览轨&&privateBusy 非空时幽灵层=半透明琥珀填充（**硬编码 0x55E8A33D**）+斜纹线（**0x33B26A00**，宽 2，步长 8px 斜率 12px）；步骤块列表=Card+ListTile dense（event 图标 18：READY=sparkStroke 否则灰；「步骤 {id}」14sp w600+时段 12sp+右侧状态 tag 11sp） |
| 空态 | 「暂无已回填时段的步骤」textSecondary 居中 |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；共振条=Row+weight 均分 Box；幽灵层=Canvas drawRect+drawLine（对位 CustomPaint）；步骤块=ShiguangCard+ListItem |

## §C 架构接线

- 路由：`CollabCanvas(bundleId, windowStartMs, windowEndMs)`——routing_map 契约面为 data object，**参数化随施工轮同步注记**。
- 对拍发现 F1（入口缺位）：CollabCanvasPage 在 Dart 全仓**零导航入口**（无任何页面 push 它）——现状=挂路由契约未挂线（施工钉子）。Kotlin 施工=按路由可达（debug nav/画廊），入口挂载点（决策矩阵「画板」钮 or 包卡第三入口）**待裁决**。
- 对拍发现 F2（魔数色值）：幽灵层两色值与斜纹步长 8/斜率 12 均硬编码无 token——违反 K2 令牌化红线，Kotlin 施工须落 :core:tokens StUi 微度量+StColors（琥珀系两值登记 tokens 对账随施工轮写回 ui-design）。
- 对拍发现 F3（STATUS 字面量）：步骤块 READY 判定=step.status=='READY' 字符串字面量（FSM StepFsmStatus 枚举名）——Kotlin 用 CollabFsm StepFsmStatus.READY 类型面，禁字符串比较。
