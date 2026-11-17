package app.shiguang.core.tokens

/**
 * ui-design §1–§3 视觉令牌单一事实源的 Kotlin 逐字直译（P4①-a，2026-11-15）。
 *
 * 对照源 = `lib/theme/tokens.dart`（155 行，2026-10-05 拍板定稿）+ ui-design §1.1/§1.2；
 * 抽取与对账清单 = docs/design/native-migration/behavior/design_tokens.md。
 * 纪律：值逐字禁现推——改任何常量必须先改 ui-design §1–§3，StTokensValueTest
 * 逐值对拍撤改即红；Padding/圆角/色彩/动效/手势一律取本模块常量，禁组件内魔术数字。
 *
 * 与 Dart 侧的结构差（设计稿既定，非新增决策）：StColors 由 mutable static +
 * applyBrightness 改为双面板 immutable data class，激活面板经 CompositionLocal
 * 由 ShiguangTheme 提供（设计稿「令牌绑进 CompositionLocal」；mutable static 是
 * Dart 单窗口取舍，不复制）。字段命名保留 Dart camelCase 原名，对拍逐值可追溯。
 *
 * 本模块 pure JVM 零依赖；色彩以 ARGB Long 承载（Compose `Color(Long)` 直收），
 * alpha 分量随值内嵌（如 roamBg 浅色 = E3F2FD·12% = 0x1FE3F2FD）。
 */

/** 语义色板（ui-design §1.1 浅色基线 + §1.2 深色映射）：LIGHT/DARK 两常量实例。 */
data class StColorsPalette(
    val deepFocusBg: Long,
    val deepFocusText: Long,
    val sparkStroke: Long,
    val sparkBg: Long,
    val roamBg: Long,
    val roamStroke: Long,
    val celebrationBg: Long,
    val celebrationStroke: Long,
    val voucherBg: Long,
    val supplyBandBg: Long,
    val supplyBandStroke: Long,
    val freeFlowBg: Long,
    val humanBlockBg: Long,
    val overlapHint: Long,
    val fixedSlotBg: Long,
    val projectionBg: Long,
    val missedAccent: Long,
    val safelineOff: Long,
    val safelineOn: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    /** 发丝灰（Flutter Colors.grey.shade300 字面量直译：边框/分隔线，双面板同值非主题自适应）。 */
    val hairlineGrey: Long,
    /** 芯片灰（Flutter Colors.grey.shade100 字面量直译：状态 Chip 底，双面板同值）。 */
    val chipGrey: Long,
    /** 表决通过（Flutter Colors.green 直译：成员徽标圆点，双面板同值）。 */
    val voteAccepted: Long,
    /** 表决拒绝（Flutter Colors.red 直译：成员徽标圆点，双面板同值）。 */
    val voteRejected: Long,
    /** 表决待复核（Flutter Colors.amber 直译：成员徽标圆点，双面板同值）。 */
    val votePendingRecheck: Long,
    /** 幽灵层琥珀填充（Dart 硬编码 0x55E8A33D 直译——ui-design §1.1 ghostBusyPrivate 行，D27 F2）。 */
    val ghostBusyFill: Long,
    /** 幽灵层斜纹（Dart 硬编码 0x33B26A00 直译——同上）。 */
    val ghostBusyStripe: Long,
    /** 轨道灰（Flutter Colors.grey.shade200 字面量直译：进度条底/降级条底，双面板同值）。 */
    val trackGrey: Long,
) {
    companion object {
        /** 归档淡出透明度（ui-design §1.1 archivedFade 15%；对账发现 F1 补 token，夜间档留痕待晨间复核）。 */
        const val archivedFadeAlpha: Float = 0.15f

        /** 浅色基线面板（ui-design §1.1 逐值）。 */
        val LIGHT = StColorsPalette(
            deepFocusBg = 0xFF283593,
            deepFocusText = 0xFFFFFFFF,
            sparkStroke = 0xFFFFB300,
            sparkBg = 0xFFFFF8E1,
            roamBg = 0x1FE3F2FD,
            roamStroke = 0x6690CAF9,
            celebrationBg = 0xFFFFF3D6,
            celebrationStroke = 0xFFD4AF37,
            voucherBg = 0xFFFFECB3,
            supplyBandBg = 0xFFE8F5E9,
            supplyBandStroke = 0xFF81C784,
            freeFlowBg = 0xFFF1F8E9,
            humanBlockBg = 0x6678909C,
            overlapHint = 0xFFFF9800,
            fixedSlotBg = 0xFFECEFF1,
            projectionBg = 0xFFF5F5F5,
            missedAccent = 0xFFFFF3E0,
            safelineOff = 0xFFBDBDBD,
            safelineOn = 0xFFD4AF37,
            textPrimary = 0xFF1A1C1E,
            textSecondary = 0xFF606468,
            hairlineGrey = 0xFFE0E0E0,
            trackGrey = 0xFFEEEEEE,
            chipGrey = 0xFFFAFAFA,
            voteAccepted = 0xFF4CAF50,
            voteRejected = 0xFFF44336,
            votePendingRecheck = 0xFFFFC107,
            ghostBusyFill = 0x55E8A33D,
            ghostBusyStripe = 0x33B26A00,
        )

        /** 深色映射面板（ui-design §1.2：换值十三项 + 双主题同值八项）。 */
        val DARK = StColorsPalette(
            deepFocusBg = 0xFF283593,
            deepFocusText = 0xFFFFFFFF,
            sparkStroke = 0xFFFFB300,
            sparkBg = 0xFF332C10,
            roamBg = 0x1F90CAF9,
            roamStroke = 0x6690CAF9,
            celebrationBg = 0xFF3B3323,
            celebrationStroke = 0xFFD4AF37,
            voucherBg = 0xFF3A2F12,
            supplyBandBg = 0xFF1E3324,
            supplyBandStroke = 0xFF81C784,
            freeFlowBg = 0xFF1F2E1B,
            humanBlockBg = 0x5478909C,
            overlapHint = 0xFFFF9800,
            fixedSlotBg = 0xFF26292B,
            projectionBg = 0xFF2A2D2F,
            missedAccent = 0xFF3A2F1B,
            safelineOff = 0xFF616161,
            safelineOn = 0xFFD4AF37,
            textPrimary = 0xFFE3E2E0,
            textSecondary = 0xFF9CA0A5,
            hairlineGrey = 0xFFE0E0E0,
            trackGrey = 0xFFEEEEEE,
            chipGrey = 0xFFFAFAFA,
            voteAccepted = 0xFF4CAF50,
            voteRejected = 0xFFF44336,
            votePendingRecheck = 0xFFFFC107,
            ghostBusyFill = 0x55E8A33D,
            ghostBusyStripe = 0x33B26A00,
        )
    }
}

/** 尺度令牌（ui-design §3.1 尺度基准 + §2 圆角刻度；间距阶梯拾贝 Insets 惯例）。 */
object StScale {
    /** 时间轴基准比例尺：1dp = 1min（16.5h 聚焦窗 ≈990dp）。 */
    const val dpPerMinute: Float = 1.0f

    /** 最小触控热区钳制：块渲染高度下限（Material 触控目标）。 */
    const val blockMinHeightDp: Float = 44f

    /** 圆角刻度：块 12 / 卡片 16 / 胶囊 999（999=全圆胶囊，禁他用）。 */
    const val radiusBlock: Float = 12f
    const val radiusCard: Float = 16f
    const val radiusCapsule: Float = 999f

    /** 间距刻度（对账发现 F2：ui-design §2 补登记，夜间档留痕待晨间复核）。 */
    const val insetXs: Float = 4f
    const val insetSm: Float = 8f
    const val insetMd: Float = 12f
    const val insetLg: Float = 16f
    const val insetXl: Float = 20f

    /** 快记 FAB 净空：滚动体底部预留（FAB 56 + 上下边距 + 呼吸）。 */
    const val fabClearanceDp: Float = 88f
}

/** 动效时长、曲线与弹簧物理参数（ui-design §3.3 全 app 仅三处微动效）。 */
object StMotion {
    /** 导航过渡（ui-design §3.3 2026-11-18 新增档）：页面进出/子路由推入返回的交叉淡入淡出时长。 */
    const val navTransitionMs: Int = 220

    /** 长按触发后的原地坍缩（「只做 5 分钟」）。 */
    const val collapseMs: Int = 300

    /** 右滑消散（「暂缓并收回清单」，绝不显红）。 */
    const val meltMs: Int = 400

    /** 安全线点亮 spring 的名义感知时长：感知窗口径，不是传给动画 API 的时长参数。 */
    const val safelineMs: Int = 250

    /** 弹簧阻尼比 0.75 = 低回弹（安全线点亮专用，微弱回弹）。 */
    const val springDampingRatio: Float = 0.75f

    /** 弹簧刚度 400（与 Material 3 motion.spring.default 同值）。 */
    const val springStiffness: Float = 400f

    /** ease-out = Cubic(0, 0, 0.58, 1)：:app 侧组装 CubicBezierEasing。 */
    const val easeOutX1: Float = 0f
    const val easeOutY1: Float = 0f
    const val easeOutX2: Float = 0.58f
    const val easeOutY2: Float = 1f

    /** ease-in-out = Cubic(0.42, 0, 0.58, 1)（不是 FastOutSlowIn）。 */
    const val easeInOutX1: Float = 0.42f
    const val easeInOutY1: Float = 0f
    const val easeInOutX2: Float = 0.58f
    const val easeInOutY2: Float = 1f

    /** fast-out-slow-in = Cubic(0.4, 0, 0.2, 1)（Material 标准曲线）。 */
    const val fastOutSlowInX1: Float = 0.4f
    const val fastOutSlowInY1: Float = 0f
    const val fastOutSlowInX2: Float = 0.2f
    const val fastOutSlowInY2: Float = 1f
}

/** 手势物理约束（ui-design §3.4 拍板）。 */
object StGesture {
    /** 横滑触发硬指标：水平初位移下限与横纵位移比下限，不满足交还垂直滚动。 */
    const val horizontalSlopDp: Float = 20f
    const val axisRatioMin: Float = 2.0f

    /** 滑动卡点：右滑融化过块宽 45% 吸附+触觉反馈；左滑换乘过 35% 呼出抽屉。 */
    const val meltFraction: Float = 0.45f
    const val swapFraction: Float = 0.35f

    /** 长按触发时长（按住期间 scale 0.98 呼吸压缩）。 */
    const val longPressMs: Int = 400
    const val longPressScale: Float = 0.98f

    /** 页面级横滑翻页：累计位移下限，不设速度门槛；低于此位移视为误触弹回。 */
    const val pageSwipeDp: Float = 60f

    /** 派生保护区三级绘制过滤阈值（分钟）：≥30 全渲染 / 15–30 图标 / <15 静默。 */
    const val bandFullMin: Int = 30
    const val bandIconMin: Int = 15
}

/**
 * 块渲染微度量（behavior/block_renderer.md 已过目值 + D12 补实现裁决件，2026-11-15）。
 * 来源 = behavior/block_renderer.md 形态层/状态层/角标错位链逐条（§9.1.6 过目转正）；
 * 虚线节距规范未定值，取常规 4/3 留痕（施工轮 devlog 可调）。
 */
object StUi {
    /** proposed 虚线边框宽（D12 F1 补实现；现 Dart 实线 1.2 同宽）。 */
    const val proposedBorderWidthDp: Float = 1.2f

    /** 重叠碰撞亮橙提示线宽（D12 F8 补实现，§3.4「1dp 细线」）。 */
    const val collisionHintWidthDp: Float = 1f

    /** done ✓ 徽章 / skipped ⊘ 槽位尺寸。 */
    const val doneBadgeSizeDp: Float = 14f

    /** 角标 emoji 字号（📋📌🎫🎂）。 */
    const val badgeGlyphSp: Float = 10f

    /** 「提案」chip 字号。 */
    const val chipTextSp: Float = 9f

    /** 手势意图提示层字号（暂缓并收回清单/换件轻松的？）。 */
    const val hintOverlaySp: Float = 11f

    /** 深度块呼吸留白尾注字号。 */
    const val footnoteSp: Float = 8f

    /** missed 降饱和系数（降 40% = 饱和度×0.6；D12 F3 补实现）。 */
    const val missDesaturation: Float = 0.6f

    /** 虚线节距（dp；规范未定值，取常规留痕）。 */
    const val dashLenDp: Float = 4f
    const val dashGapDp: Float = 3f

    /** 角标共存错位（D12 前行为清单）：提案存在时 🎂/📌 右移 40；庆祝存在时 📌 右移 16；🎫 凭证行栈距 12。 */
    const val badgeOffsetOverlapDp: Float = 40f
    const val badgeOffsetCelebrationDp: Float = 16f
    const val voucherStackDp: Float = 12f

    /** 凭证微标 badge 文本 maxWidth（D12 F4 裁决=96dp，现实实现 60）。 */
    const val voucherBadgeMaxWidthDp: Float = 96f

    /** 「提案」chip 纵向内边距（行为清单 chip 形状 h4/v1；h=insetXs）。 */
    const val chipPadVDp: Float = 1f

    // —— N8c 批补档（值出处=onboarding.md/app_gate.md/update_page.md/mcp_settings.md 转正清单逐字）
    /** onboarding 页 padding（Dart EdgeInsets.all(24)）。 */
    const val onboardingInsetDp: Float = 24f

    /** onboarding 步骤体大间距（Dart SizedBox 24）。 */
    const val onboardingGapLgDp: Float = 24f

    /** onboarding 时间卡间距（Dart SizedBox 12）。 */
    const val onboardingGapMdDp: Float = 12f

    /** onboarding 输入域标签间距（Dart SizedBox 6）。 */
    const val onboardingGapXsDp: Float = 6f

    /** onboarding 步骤标题字号（Dart fontSize 22 bold）。 */
    const val stepTitleTextSp: Float = 22f

    /** onboarding 时间卡钟点字号（Dart fontSize 24 bold）。 */
    const val onboardingClockTextSp: Float = 24f

    /** MCP 运行态圆点直径（Dart Container 10×10）。 */
    const val mcpStatusDotDp: Float = 10f

    /** MCP 页复制图标尺寸（Dart Icon size 18）。 */
    const val mcpCopyIconDp: Float = 18f

    // —— N5 批补档（K2 令牌化清理：值出处=schedule_page.md/app_shell_sheets.md 转正清单逐字，
    //    Flutter 实现现状就地值；非现推参数）———————————————————————————————
    /** §F gutter 时钟列宽（画布宽 = 容器宽 − 52 − 16）。 */
    const val timelineGutterDp: Float = 52f

    /** §F supply 补给带实线宽（0.6dp，F9 虚线框裁决件的实线现状值）。 */
    const val supplyLineWidthDp: Float = 0.6f

    /** §F 小时分隔线宽 / 安全线徽章 outline 宽（1dp）。 */
    const val dividerWidthDp: Float = 1f

    /** F9 保护区虚线框节距 6/4dp（2026-11-16 拍板转正——Dart 基线为实线无虚线可对拍，密度档属规范驱动人工视觉裁决）。 */
    const val bandDashLenDp: Float = 6f
    const val bandDashGapDp: Float = 4f

    /** §A compact 密度字号（翻日/回到现在/回到今天钮与 NowNext 副行、图标 emoji）。 */
    const val compactTextSp: Float = 12f

    /** §D 电量三档 emoji 字号。 */
    const val energyGlyphSp: Float = 14f

    /** §C 快记 FAB 琥珀点径与 surface 描边宽。 */
    const val fabDotSizeDp: Float = 6f
    const val fabDotStrokeDp: Float = 2f

    /** §H 周视图微度量（N6 抽取转正）：列头行高追加/紧凑块圆角·margin·padding·字号行高/车道缝。 */
    const val weekColumnHeaderExtraDp: Float = 28f
    const val weekBlockCornerRadiusDp: Float = 6f
    const val weekBlockInsetDp: Float = 0.5f
    const val weekBlockPadDp: Float = 2f
    const val weekBlockTextSp: Float = 9f
    const val weekBlockLineHeight: Float = 1.1f
    const val weekLaneGapDp: Float = 1f

    /** §H 周块高钳下限（§H 清单转正值 24dp）。 */
    const val weekBlockMinHeightDp: Float = 24f

    /** §H.1 月历微度量（N6 抽取转正）：格子宽高比·margin·padding/点径点距/小字/微标。 */
    const val monthRingWidthDp: Float = 1.4f
    const val monthCellAspect: Float = 0.82f
    const val monthCellMarginDp: Float = 2f
    const val monthCellPadDp: Float = 3f
    const val monthDotSizeDp: Float = 7f
    const val monthDotSpacingDp: Float = 2f
    const val monthSmallTextSp: Float = 8f
    const val monthGlyphSp: Float = 9f

    /** §D 象限色点 10 与间距 10/冷藏池头行间距 6（D20 转正值）。 */
    const val plansDotSizeDp: Float = 10f
    const val plansDotGapDp: Float = 10f
    const val plansHeadGapDp: Float = 6f

    /** §B/§C/§D 清单页微度量（D20 转正）：行纵向 10/小图标 18/元信息 11。 */
    const val plansRowVDp: Float = 10f
    const val iconSmDp: Float = 18f
    const val plansMetaTextSp: Float = 11f

    /** §B 设置页微度量（D21 转正）：入口图标 22/作息钟字 20/导出弹框限高（N8a 实现值留痕）。 */
    const val settingsEntryIconDp: Float = 22f
    const val settingsClockTextSp: Float = 20f
    const val exportDialogMaxHeightDp: Float = 360f

    /** §E 确认卡滚动展开阈值（reverse 且 pixels<24 → 展开，清单转正值）。 */
    const val scrollExpandThresholdDp: Float = 24f

    /** 浮层 leading emoji 字号（§B 全局动作 ☕⏳🪫 20）与确认卡收起箭头图标 20。 */
    const val sheetGlyphSp: Float = 20f
    const val smallIconDp: Float = 20f

    /** 微距档（电量 tier padding/徽章纵向 2；仪表盘纵向与电量段 6；三段并列间 10）。 */
    const val compactPadDp: Float = 2f
    const val compactGapDp: Float = 6f
    const val compactGapLgDp: Float = 10f

    // —— plan 详情族批补档（值出处=behavior/{plan_detail,plan_background,plan_facts}.md
    //    转正清单逐字；4/6/8/10/12/18/20/11sp 复用既有通用档 insetXs/compactGapDp/insetSm/
    //    compactGapLgDp/insetMd/iconSmDp/insetXl·smallIconDp/plansMetaTextSp）
    /** 详情页 roadmap 进度行字号（Dart fontSize 12）。 */
    const val planRoadmapTextSp: Float = 12f

    /** 子页入口行 leading 图标（Dart Icon size 22；chevron 20=smallIconDp）。 */
    const val planEntryIconDp: Float = 22f

    /** 子计划逐级左缩进（Dart depth×16）。 */
    const val planSubtreeIndentDp: Float = 16f

    /** 详情/编辑页底 padding（Dart LTRB(20,8,20,24) 尾值 24）。 */
    const val planPageBottomInsetDp: Float = 24f

    /** 凭证状态小字字号（原文待提炼/已作废/口头信息，Dart fontSize 10）。 */
    const val planFactStateTextSp: Float = 10f

    /** 背景头行「添加背景」前置图标（Dart Icon size 16）。 */
    const val planAddIconDp: Float = 16f

    /** 折叠组头纵向内边距（背景/凭证组头 v10；h=insetMd）——补档（施工轮自查补）。 */
    const val planGroupHeadVDp: Float = 10f

    /** 通关卡 Chip/药丸图标（Dart Icon size 12）。 */
    const val factChipIconDp: Float = 12f

    /** 通关条纵向 margin（Dart vertical 6）。 */
    const val passBarVDp: Float = 6f

    /** 通关条头行横向 padding（Dart horizontal 14）。 */
    const val passBarHeadHDp: Float = 14f

    /** 通关卡锚点行纵距（Dart vertical 3）。 */
    const val factAnchorRowVDp: Float = 3f

    /** 挂载 sheet/面板装载框高（Dart loading Padding 40 视觉近似盒）。 */
    const val factSheetLoadingDp: Float = 80f

    /** 块弹层/新建 sheet 时刻字（Dart fontSize 20=作息钟字同档）。 */
    const val sheetClockTextSp: Float = 20f

    /** 通关卡嵌入块浮层的内边距（Dart padding=zero——外层已有边距）。 */
    const val factEmbedPadDp: Float = 0f

    // —— scan 批补档（值出处=behavior/scan.md 转正清单逐字）
    /** 权限态相机图标（Dart Icon size 56）。 */
    const val scanCameraIconDp: Float = 56f

    /** 权限态主文案（Dart fontSize 16）。 */
    const val scanBodyTextSp: Float = 16f

    /** 手动区标题（Dart fontSize 13）。 */
    const val scanHintTextSp: Float = 13f

    /** 错误条/手动区大间距（Dart 24=insetXl+4? 页底 24——与 planPageBottomInsetDp 同值档）。 */
    const val scanInsetDp: Float = 24f

    // —— 协作九页共享微度量批（T4 轮；值出处=behavior/{friends,member_calendar,decision_matrix,
    //    compose_bundle,audit_timeline,my_qr,safety_number,account}.md §B 逐字对拍，非现推） ——————
    /** 按钮内联图标（Dart Icon size 18——动作行/FAB/入口钮共用档）。 */
    const val collabActionIconDp: Float = 18f

    /** 溢出菜单图标（Dart Icon size 20）。 */
    const val collabMenuIconDp: Float = 20f

    /** 行内 busy 转圈（Dart 16×16）。 */
    const val collabSpinnerDp: Float = 16f

    /** 行内 busy 转圈线宽（Dart strokeWidth 2）。 */
    const val collabSpinnerStrokeDp: Float = 2f

    /** 空态主图标（Dart Icon size 56——friends/audit/member_calendar 空态共用档）。 */
    const val collabHeroIconDp: Float = 56f

    /** 列表尾距（Dart padding bottom 32）。 */
    const val collabListTailDp: Float = 32f

    /** 次要提示文（Dart fontSize 12——hint/说明行共用档）。 */
    const val collabHintSp: Float = 12f

    /** 脚注/错误行（Dart fontSize 13）。 */
    const val collabFootnoteSp: Float = 13f

    /** 分区标题（Dart fontSize 14 w600）。 */
    const val collabSectionSp: Float = 14f

    /** 空态标题（Dart fontSize 17 w600）。 */
    const val collabHeroSp: Float = 17f

    /** 表单行距（Dart 10——维度卡间/子类区距共用档）。 */
    const val collabRowGapDp: Float = 10f

    /** 字段区距（Dart 14——标签→控件/展开区间共用档）。 */
    const val collabFieldGapDp: Float = 14f

    /** 协作卡圆角（Dart BorderRadius 12——维度卡/安全码格/气泡共用档）。 */
    const val collabCardRadiusDp: Float = 12f

    /** 维度大卡内边距（Dart padding 14）。 */
    const val collabDimPadDp: Float = 14f

    /** 选中描边（Dart width 1.5——三态预占 hardTentative 同档）。 */
    const val collabSelectedBorderDp: Float = 1.5f

    /** 未选描边（Dart width 1）。 */
    const val collabIdleBorderDp: Float = 1f

    /** 步骤瓦片圆角（Dart BorderRadius 10）。 */
    const val collabTileRadiusDp: Float = 10f

    /** 步骤瓦片纵向内边距（Dart vertical 10）。 */
    const val collabTilePadVDp: Float = 10f

    /** 徽标底色 alpha（Dart withValues alpha 0.12）。 */
    const val collabBadgeAlpha: Float = 0.12f

    /** 徽标圆角（Dart BorderRadius 6）。 */
    const val collabBadgeRadiusDp: Float = 6f

    /** Chip/徽标纵向内边距（Dart vertical 2）。 */
    const val collabChipPadVDp: Float = 2f

    /** 线性进度条高（Dart minHeight 8）。 */
    const val collabProgressBarDp: Float = 8f

    /** 表决徽标圆点径（Dart Icon circle size 10）。 */
    const val collabDotDp: Float = 10f

    /** 催办铃铛径（Dart deleteIcon size 14）。 */
    const val collabBadgeIconDp: Float = 14f

    /** 空态标题（Dart fontSize 15 w600——决策矩阵空态）。 */
    const val collabEmptyTitleSp: Float = 15f

    /** 时间树连接线宽（Dart Container width 2）。 */
    const val collabRailWidthDp: Float = 2f

    /** 协作页大内边距（Dart padding 24——my_qr/safety 双页共用档）。 */
    const val collabPageInsetDp: Float = 24f

    /** 名片 QR 边长（Dart size 240）。 */
    const val collabQrSizeDp: Float = 240f

    /** 安全码数值字（Dart fontSize 22 w700）。 */
    const val collabSafetyValueSp: Float = 22f

    /** 安全码数值字距（Dart letterSpacing 2）。 */
    const val collabSafetyLetterSpacingSp: Float = 2f

    /** 卡片标题字（Dart fontSize 16 w600——账号已登录卡）。 */
    const val collabCardTitleSp: Float = 16f

    /** 提交钮 busy 转圈径（Dart 18）。 */
    const val collabBusySpinnerDp: Float = 18f

    /** 账号头像径（Dart Icon size 40）。 */
    const val collabAvatarIconDp: Float = 40f

    /** 共振条高（Dart 24）。 */
    const val collabQuorumBarDp: Float = 24f

    /** 共振条桶间隙（Dart horizontal margin 0.5）。 */
    const val collabBarGapDp: Float = 0.5f

    /** 共振条着色基座 alpha（Dart 0.15+0.85*(d/max)——基座与跨度拆双 token）。 */
    const val quorumAlphaBase: Float = 0.15f

    /** 共振条着色跨度 alpha（Dart 0.85）。 */
    const val quorumAlphaSpan: Float = 0.85f

    /** 画板状态 tag 字（Dart fontSize 11）。 */
    const val collabTagSp: Float = 11f

    /** 幽灵层斜纹线宽（Dart strokeWidth 2——D27 F2 token 落点）。 */
    const val ghostStripeWidthDp: Float = 2f

    /** 幽灵层斜纹步长（Dart x+=8）。 */
    const val ghostStripeStepDp: Float = 8f

    /** 幽灵层斜纹斜率（Dart Offset(x+12, 0)——dx 12）。 */
    const val ghostStripeSlopeDp: Float = 12f

    /** 共振条桶圆角（Dart BorderRadius 2）。 */
    const val collabBucketRadiusDp: Float = 2f
}
