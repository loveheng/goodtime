---
memo: done
format: v2
---

# 完成列表
- [2026-10-15] R2 CDN 串版事故修复 + 28021 重推（APK 版本化 key app-28021.apk + 清单 no-cache；客户端全链路核验过：清单 GET/no-cache 头/APK 实哈希=清单值） (域: online)
- [2026-10-15] R2 补发 v1.2.1+28021（设置页双「设置」标题修复 + 「关于」长按触发区扩到整卡；远端清单 GET + APK HEAD 核验过） (域: online)
- [2026-10-15] R2 发布 v1.2.1+28020（MCP 暴露面收口：HTTP 仅回环调试+「关于」长按隐藏入口+mDNS 退役；远端清单 GET + APK HEAD 核验过，自更新流程待真机验） (域: online)
- [2026-10-15] bonsoir 5.1.x→7.x 升级评估挂账作废：bonsoir 依赖随 MCP 暴露面收口摘除（mDNS 广播退役，无 mDNS 真机实证需求；根 gradle compileSdk≥36 钳制保留兜底） (域: m1)
- [2026-10-06] 今日页「昨日遗留区」实装（missed 三键：补勾补记/顺延今日/退回愿望池，PostponeBlock 新命令） (域: m1)
- [2026-10-06] 清单页「收起的旧想法」冷藏池折叠区实装（archived 可见可恢复） (域: m1)
- [2026-10-06] 全局动作 sheet「遇到突发情况？」实装（PanicClear 命令：clear_remaining/push_2h 原子批量 + 两段确认 UI） (域: m1)
- [2026-10-06] 清单详情簇实装：roadmap 当前进度/open_items 手答「敲定」/子树 ≤3 级缩进/reward_spec 编辑/「排期」快捷动作；命令层与 add_plan+update_plan 工具面补 open_items 入参（人机澄清闭环全通） (域: m1)
- [2026-10-06] 设置页编辑器簇实装：一周节奏 fixed_slots 增删改（编辑 sheet：名称/ISO 星期多选/起止，整单 UpdateFixedSlotsCommand 替换）/例外日 exceptions 增删改（日期范围+标签，JSON 落 settings）/排程偏好（最小块粒度+单日排量上限+填充率上限+天气城市+user_rules 字段级 UpdateSettingsCommand） (域: m1)
- [2026-10-07] App 图标落地：用户「日出雪原+路线」图 → 自适应图标（前景 72dp 安全区铺满+背景 #DDE2E6）+ 传统 mipmap 五档替换；母版/原图存 assets/icon/（§11 拍板留痕） (域: misc)
- [2026-10-07] App 图标换稿：「日出+清单卡片」版替换初版「日出雪原+路线」，全套资源（mipmap/自适应/母版/背景色 #F9E5C2）重生成，§11 台账已改 (域: misc)
- [2026-10-07] R2 发布 v1.1.0+12011（新图标+自更新体系首发；APK/sha256/清单远端核验通过）+ 修 .env 空 CHANGELOG= 覆盖外部传参陷阱（.env 已注释该行） (域: misc)
- [2026-10-08] fact 草案定稿评估与二期裁决落笔：§9 新增（开工 Blocker 四项+维护性隐患四项，含 Q-A/B/C 拍板）+ §8 重写为落地对账表 + header/§0 状态与留痕同步 + voided 双通道口径（UI 折叠组可见/unvoid 双向/raw→structured 不可逆）+ §2.3 引用漂移三处改指 §1.7 + §2 三 Tab 措辞；配套 ui-spec §0.4 词条两组、schedule-app §11 二期裁决留痕、golden-samples #4 附录状态同步（docs-lint 全绿） (域: misc)
