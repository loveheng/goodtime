import 'dart:ui';

/// ui-spec §0 视觉令牌单一事实源（2026-10-05 拍板定稿；2026-10-06 解锁双主题）。
///
/// 纪律（自拾贝 tokens.dart 同款）：Padding/Margin/圆角/色彩/动效/手势阈值
/// 一律走本文件常量，禁止在组件里写魔术数字或裸色值；ui-spec 改版先改这里。
/// 拍板基线：Material 3 + 固定品牌色板、关闭动态取色；主题三档（跟随系统/浅/深，
/// 2026-10-06 拍板，浅色默认基线不变）。

/// 语义色彩（ui-spec §0.1）：浅/深两套面板，[StColors] 为**当前激活面板**。
/// 单窗口单主题——仅 ShiguangApp 装配点经 [StColors.applyBrightness] 置位，
/// 业务代码只读不写；全局可变面板是对 ThemeExtension 方案的取舍
/// （51 处调用点零迁移 vs 多窗口/动态取色才需要扩展，否决记录 schedule-app §11）。
class StColors {
  const StColors._();

  /// 深度攻坚态块底（高对比主战役）——双主题同值。
  static Color deepFocusBg = const Color(0xFF283593);
  static Color deepFocusText = const Color(0xFFFFFFFF);

  /// 微型火种态胶囊（「5 分钟启动版」）。
  static Color sparkStroke = const Color(0xFFFFB300);
  static Color sparkBg = const Color(0xFFFFF8E1);

  /// 漫游主题态（弱底弱框，起止以 `~` 显示）。
  static Color roamBg = const Color(0x1FE3F2FD); // E3F2FD @12%
  static Color roamStroke = const Color(0x6690CAF9); // 90CAF9 @40%

  /// 庆祝勋章态（香槟金微光，界面文案「犒劳时刻」）。
  static Color celebrationBg = const Color(0xFFFFF3D6);
  static Color celebrationStroke = const Color(0xFFD4AF37);

  /// 凭证通关卡 Hero 面（ui-spec §0.1 voucherSurface，2026-10-08 切片 6）：
  /// 色系近 spark 琥珀、**不用庆祝金**——庆祝金语义专属犒劳时刻；描边复用
  /// sparkStroke 不引新色（verbal 待核实角标同款纪律）。
  static Color voucherBg = const Color(0xFFFFECB3);

  /// 🛡️ 能量补给带（派生渲染不入库；界面文案「留白缓冲」）。
  static Color supplyBandBg = const Color(0xFFE8F5E9);
  static Color supplyBandStroke = const Color(0xFF81C784);

  /// 🍃 自由流动区（派生；界面文案「自由支配时间」）。
  static Color freeFlowBg = const Color(0xFFF1F8E9);

  /// human/手工块（现实色，与 AI 彩色块区分；重叠双列时橙线提示放行）。
  static Color humanBlockBg = const Color(0x6678909C); // 78909C @40%
  static Color overlapHint = const Color(0xFFFF9800); // §3 橙色提示拍板

  /// fixed_slots 背景带 / 系统日历投影块。
  static Color fixedSlotBg = const Color(0xFFECEFF1);
  static Color projectionBg = const Color(0xFFF5F5F5);

  /// missed 中性标（拍板方案 A：原形态色降饱和 40% + 米黄左标，绝不红）。
  static Color missedAccent = const Color(0xFFFFF3E0);

  /// 安全线徽章（界面文案「今日底线守住啦」）。
  static Color safelineOff = const Color(0xFFBDBDBD);
  static Color safelineOn = const Color(0xFFD4AF37);

  /// 正文/次要文字（中性灰，不用纯黑）。
  static Color textPrimary = const Color(0xFF1A1C1E);
  static Color textSecondary = const Color(0xFF606468);

  /// 按亮度切换激活面板（仅 ShiguangApp 装配点调用）；深色映射表 ui-spec §0.5。
  static void applyBrightness(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    deepFocusBg = const Color(0xFF283593);
    deepFocusText = const Color(0xFFFFFFFF);
    sparkStroke = const Color(0xFFFFB300);
    sparkBg = dark ? const Color(0xFF332C10) : const Color(0xFFFFF8E1);
    roamBg = dark ? const Color(0x1F90CAF9) : const Color(0x1FE3F2FD);
    roamStroke = const Color(0x6690CAF9);
    celebrationBg = dark ? const Color(0xFF3B3323) : const Color(0xFFFFF3D6);
    celebrationStroke = const Color(0xFFD4AF37);
    voucherBg = dark ? const Color(0xFF3A2F12) : const Color(0xFFFFECB3);
    supplyBandBg = dark ? const Color(0xFF1E3324) : const Color(0xFFE8F5E9);
    supplyBandStroke = const Color(0xFF81C784);
    freeFlowBg = dark ? const Color(0xFF1F2E1B) : const Color(0xFFF1F8E9);
    humanBlockBg = dark ? const Color(0x5478909C) : const Color(0x6678909C);
    overlapHint = const Color(0xFFFF9800);
    fixedSlotBg = dark ? const Color(0xFF26292B) : const Color(0xFFECEFF1);
    projectionBg = dark ? const Color(0xFF2A2D2F) : const Color(0xFFF5F5F5);
    missedAccent = dark ? const Color(0xFF3A2F1B) : const Color(0xFFFFF3E0);
    safelineOff = dark ? const Color(0xFF616161) : const Color(0xFFBDBDBD);
    safelineOn = const Color(0xFFD4AF37);
    textPrimary = dark ? const Color(0xFFE3E2E0) : const Color(0xFF1A1C1E);
    textSecondary = dark ? const Color(0xFF9CA0A5) : const Color(0xFF606468);
  }
}

/// 尺度令牌（ui-spec §0.3 Flutter 工程审查拍板）。
class StScale {
  const StScale._();

  /// 时间轴基准比例尺：1dp = 1min（16.5h 聚焦窗 ≈990dp）。
  static const double dpPerMinute = 1.0;

  /// 最小触控热区钳制：块渲染高度下限（Material 触控目标）。
  static const double blockMinHeightDp = 44;

  /// 圆角刻度：块 12 / 卡片 16 / 胶囊 999。
  static const double radiusBlock = 12;
  static const double radiusCard = 16;
  static const double radiusCapsule = 999;

  /// 间距刻度（对齐拾贝 Insets 惯例）。
  static const double insetXs = 4;
  static const double insetSm = 8;
  static const double insetMd = 12;
  static const double insetLg = 16;
  static const double insetXl = 20;

  /// 快记 FAB 净空（ui-spec §3 层叠规则，2026-10-06 拍板）：滚动体底部预留，
  /// 末尾内容可滚出 FAB 覆盖区（FAB 56 + 上下边距 + 呼吸）。
  static const double fabClearanceDp = 88;
}

/// 动效时长（全 app 仅三处微动效，其余零动画）。
class StMotion {
  const StMotion._();

  /// 长按触发后的原地坍缩（「只做 5 分钟」）。
  static const int collapseMs = 300;

  /// 右滑消散（「暂缓并收回清单」，绝不显红）。
  static const int meltMs = 400;

  /// 安全线点亮（单次 spring）。
  static const int safelineMs = 250;
}

/// 手势物理约束（ui-spec §6.5 拍板）。
class StGesture {
  const StGesture._();

  /// 横滑触发硬指标：水平初位移下限与横纵位移比下限，不满足交还垂直滚动。
  static const double horizontalSlopDp = 20;
  static const double axisRatioMin = 2.0;

  /// 滑动卡点：右滑融化过块宽 45% 吸附+触觉反馈；左滑换乘过 35% 呼出抽屉。
  static const double meltFraction = 0.45;
  static const double swapFraction = 0.35;

  /// 长按触发时长（按住期间 scale 0.98 呼吸压缩）。
  static const int longPressMs = 400;
  static const double longPressScale = 0.98;

  /// 页面级横滑翻页（日/月视图背景层，2026-10-06 拍板）：累计位移下限，
  /// 不设速度门槛——慢速蓄力拖动同样翻页；低于此位移视为误触弹回。
  static const double pageSwipeDp = 60;

  /// 派生保护区三级绘制过滤阈值（分钟）：≥30 全渲染 / 15–30 图标 / <15 静默。
  static const int bandFullMin = 30;
  static const int bandIconMin = 15;
}
