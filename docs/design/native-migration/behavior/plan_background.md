---
status: active
updated: 2026-11-15
---

# 行为清单 · plan_background（计划背景子页，P4 收尾件施工输入，已过目转正 2026-11-15 D24——生成通道可作施工输入）

> §9.1.6 模板产出（2026-11-15 抽取）。抽取源 = `lib/ui/plan_background_page.dart`（430 行全文）+ `docs/design/ui-pages.md` §9.4（背景区文案组）/§8.4（C 档 2 态）+ 背景草案 §3（入口 1）+ 命令面行为清单（upsert_background/merge_backgrounds 已平移 dispatch 26/26，只核语义）。**范围 = 背景子页本体（进行中列表 / 添加编辑 sheet / 过去的背景折叠组 / 清理动作）**；写通道全经命令层（human，arch-guard：UI 禁直连 repo 写）；source 自动=user。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 计划「背景」子页=身体状况/偏好/注意事项条目管理（自详情页整区迁出）：进行中列表（点按编辑）+「添加背景」+ 超期沉「过去的背景」折叠组（组内逐条删/清空已过期，均二次确认；主列表不加清理图标降噪） |
| 触发与阈值 | 本页零 StGesture 手势；条目点按=编辑 sheet；折叠组头点按展开/收起；删除动作全部二次确认弹框 |
| 状态空间 | S1 内容展示态（进行中列表±过去组）/ S2 无数据态（空态文案，ui-pages §8.4）；局部态=编辑 sheet（新增/编辑共用）+折叠组展开态（默认收起） |
| 落库命令与拒绝码 | `upsert_background`（id 空=新增/scope=plan/planId/content trim 非空/tags 切分/applicableDates 逐日枚举/expectedVersion=existing?.version）→ version_conflict/not_found 等；`merge_backgrounds` ops=[delete(id)]（单条删与清空已过期共用，单事务）；「结束日期早于开始日期」属 sheet 内本地校验不入命令层 |
| 反馈形式 | 保存成功关 sheet（无成功 toast；r.note 有值 SnackBar 3s=长窗劝改等命令层提示）；删除确认弹框（单条「删除这条背景？」/批量「清空这 N 条过去的背景？」content「删除后无法恢复。」取消 TextButton/删除 FilledButton）；失败 ActionException message SnackBar 3s（sheet 内）/默认 4s（页面级）；sheet 内校验失败 SnackBar 3s 不关 sheet |
| 黄金向量指针 | upsert_background/merge_backgrounds 已平移（GoldenCommandParseTest）；本页零新向量 |
| 像素基线指针 | plan_background 组 PLAN_S1/PLAN_S2（2026-11-14 已采） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 主数据 | listBackgrounds(scope='plan', planId)（**端口缺口 G1**：Kotlin BridgeRepositoryPort.listBackgrounds() 无 scope/planId 参数——Expand 补参，D16/N7 artifactsUnattributed 先例） |
| 分区派生 | 过期=有日期窗且（空窗 fail-closed 或 last<今日 ISO）；无窗=长期恒不过期；active=非过期；expired=过期（派生渲染不入库） |
| 刷新面 | Dart ListenableBuilder+FutureBuilder → Kotlin VM collect repo.revision 重载（D15/D20 形） |

## §B 页面骨架

| 行为 | 口径 |
|---|---|
| AppBar | title「背景」 |
| 头行 | Row：「进行中」labelMedium textSecondary + Spacer + TextButton.icon（add 16「添加背景」visualDensity compact）→ 编辑 sheet（existing=null） |
| 条目卡 | InkWell radiusCard padding v6：content bodyMedium + tags join(' ') bodySmall textSecondary + 日期角标 11sp textSecondary trailing |
| 日期角标 | [MM.dd] 单日 / [MM.dd–MM.dd] 区间（月日两位补零；无窗不显示） |
| 空态 | active 空：「还没有背景——身体状况、偏好、注意事项都记在这」bodySmall textSecondary v12 |
| 过去组 | Card top 4 头行 InkWell padding h12 v10：expand_less/more 18 textSecondary +「过去的背景 · N 条」labelMedium textSecondary（默认收起）；展开=dense ListTile（content bodySmall + 角标 11sp + trailing TextButton「删除」）+ 底部 TextButton.icon（cleaning_services 16「清空已过期」）padding LTRB(12,0,12,8)；逐条点按仍可开编辑 sheet |

## §C 编辑 sheet（新增/编辑共用）

| 行为 | 口径 |
|---|---|
| 形态 | ModalBottomSheet showDragHandle isScrollControlled + SafeArea padding LTRB(20,0,20,20+键盘避让)；标题「添加背景」/「编辑背景」titleLarge |
| 字段 | 内容 TextField（maxLines 3，新增 autofocus，label「背景内容」hint「例如：妈妈膝盖不好，少长台阶陡坡」）；标签 TextField（label「标签（空格分隔）」hint「#健康 #体力」）；「作用日期窗（不选=长期）」bodySmall textSecondary |
| 日期窗 | 开始 OutlinedButton（initial=今日，2020..今日+365）→ 结束 OutlinedButton（无开始禁用；initial=end??start，first=start，last=今日+365）；有开始显 close IconButton 18 tooltip「清除日期窗（改为长期）」（双清）；编辑回填 dates 首末 |
| 保存 | content trim 空不提交；tags 按 `[,,，、\s]+` 切分去空；窗枚举 s..e 逐日 ISO（end 空=单日）；end.isBefore(s) → SnackBar 3s「结束日期早于开始日期」不关 sheet；upsert_background 成功→note 有则 SnackBar 3s→关 sheet |
| 删除 | 编辑态左下「删除」TextButton redAccent → pop sheet → 二次确认（同 §D 确认口） |

## §D 删除与清空

| 行为 | 口径 |
|---|---|
| 确认弹框 | 单条「删除这条背景？」/批量「清空这 N 条过去的背景？」+ content「删除后无法恢复。」；取消 TextButton/删除 FilledButton |
| 执行 | merge_backgrounds ops=[for targets delete(id)]（单事务批量）；失败 ActionException message SnackBar 默认 4s |

## §E 架构接线

- PlanBackgroundViewModel（D15 形）+ ShiguangRoutes.PlanBackground(planId) 接装（路由已注册）。
- 端口缺口 G1：listBackgrounds(scope, planId) Expand——双实现同步（NativeSqlRepository WHERE AND 拼接镜像 Dart 语义 + DartBridgeRepository call 传参）。
- upsert/merge 命令面已平移；本页零 factExpired 依赖。
