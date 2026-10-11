---
memo: done
format: v2
---

# 完成列表
- [2026-10-06] 今日页「昨日遗留区」实装（missed 三键：补勾补记/顺延今日/退回愿望池，PostponeBlock 新命令） (域: m1)
- [2026-10-06] 清单页「收起的旧想法」冷藏池折叠区实装（archived 可见可恢复） (域: m1)
- [2026-10-06] 全局动作 sheet「遇到突发情况？」实装（PanicClear 命令：clear_remaining/push_2h 原子批量 + 两段确认 UI） (域: m1)
- [2026-10-06] 清单详情簇实装：roadmap 当前进度/open_items 手答「敲定」/子树 ≤3 级缩进/reward_spec 编辑/「排期」快捷动作；命令层与 add_plan+update_plan 工具面补 open_items 入参（人机澄清闭环全通） (域: m1)
- [2026-10-06] 设置页编辑器簇实装：一周节奏 fixed_slots 增删改（编辑 sheet：名称/ISO 星期多选/起止，整单 UpdateFixedSlotsCommand 替换）/例外日 exceptions 增删改（日期范围+标签，JSON 落 settings）/排程偏好（最小块粒度+单日排量上限+填充率上限+天气城市+user_rules 字段级 UpdateSettingsCommand） (域: m1)
- [2026-10-07] App 图标落地：用户「日出雪原+路线」图 → 自适应图标（前景 72dp 安全区铺满+背景 #DDE2E6）+ 传统 mipmap 五档替换；母版/原图存 assets/icon/（§11 拍板留痕） (域: misc)
- [2026-10-07] App 图标换稿：「日出+清单卡片」版替换初版「日出雪原+路线」，全套资源（mipmap/自适应/母版/背景色 #F9E5C2）重生成，§11 台账已改 (域: misc)
- [2026-10-07] R2 发布 v1.1.0+12011（新图标+自更新体系首发；APK/sha256/清单远端核验通过）+ 修 .env 空 CHANGELOG= 覆盖外部传参陷阱（.env 已注释该行） (域: misc)
- [2026-10-08] fact 草案定稿评估与二期裁决落笔：§9 新增（开工 Blocker 四项+维护性隐患四项，含 Q-A/B/C 拍板）+ §8 重写为落地对账表 + header/§0 状态与留痕同步 + voided 双通道口径（UI 折叠组可见/unvoid 双向/raw→structured 不可逆）+ §2.3 引用漂移三处改指 §1.7 + §2 三 Tab 措辞；配套 ui-spec §0.4 词条两组、schedule-app §11 二期裁决留痕、golden-samples #4 附录状态同步（docs-lint 全绿） (域: misc)
- [2026-10-09] 事实挂载回改四项 + voided 可见出口（main，60c9995 之后）：① raw→structured 出生确认严进 category/source_kind 必填（缺 invalidRequest+hint，工具面描述同步）；② 多 span 一致性比对改重叠度贪心；③ delete_plan 解挂三分支（块随删双清/块保留留 block_id/日切悬空不写库降级）；④ voided 双通道「已作废·N 条」折叠组（repository 含 voided 查询变体 + 凭证子页 + 卡面弱化标）；playbook facts.md 同步，三新测试文件 + repository 扩充，全绿 (域: misc)
- [2026-10-09] 二期 V1.5 硬红线 + 分期二临场通关条（feat/fact-v15 分支）：fact_rules 引擎（role 受控枚举+存量兼容 [DEGRADE]/rule_scope 派生生效/红线 factRuleViolations/临场 computeActionPasses 本地时钟公式）；服务端 upsert rule_scope 必填 + propose/adjust 子树校验 rejected_by_facts + human 角标放行；MCP role 枚举 schema + rule_scope 要求；UI 今日页 ActionPassBar（Banner 下遗留区上/+N 折叠/跳凭证）+ 块浮层 human 规则角标；playbook 同步；三新测试文件，全绿 (域: misc)
- [2026-10-09] Phase 0 契约冻结完成（epic `online`）：中继独立仓 /home/zzh/app/shiguang-relay 落骨架（go.mod/Dockerfile/compose）+ proto 补 go_package + 双端生成（gen/go、gen/dart）+ schema.sql 容器建库 10 表且 seq 唯一约束实测生效 + README 鉴权定稿章节；commit `0e320ec` + tag `v0.1.6-contract`（零 Go 业务代码，停火协议遵守）；主仓内 shiguang-relay/ 目录已移除（双仓分离） (域: online)
- [2026-10-09] 方向 B 多人轨拍板留痕：§13 地基转向（守零代发+内容E2EE，放开同步/账号/服务端联系人；产品灵魂四项不变）并入 SSOT schedule-app §13 + fact 草案 §6；Q1–Q10 全部落定默认值（Q1 匿名隔离底线 / Q2 匿名PoP / Q3 白名单好友 / Q5 门限共识+落槌 / Q7 recovery_vault / Q8 自建Compose+Caddy / Q10 独立仓 shiguang-relay），并录入三项工程防线（Sybil→注册PoW+IP限流；灾备→Litestream异地备份；FCM阻塞→异步Worker池）+ Phase 0 停火协议（只出 proto+schema 打 tag，零 Go 业务代码） (域: misc)
