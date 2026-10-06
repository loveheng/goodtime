import 'dart:ui';

/// ui-spec §0 视觉令牌单一事实源（2026-10-05 拍板定稿）。
///
/// 纪律（自拾贝 tokens.dart 同款）：Padding/Margin/圆角/色彩/动效/手势阈值
/// 一律走本文件常量，禁止在组件里写魔术数字或裸色值；ui-spec 改版先改这里。
/// 拍板基线：Material 3 + 固定品牌色板、关闭动态取色、MVP 锁定 Light Mode。

/// 语义色彩（ui-spec §0.1）。
class StColors {
  const StColors._();

  /// 深度攻坚态块底（高对比主战役）。
  static const Color deepFocusBg = Color(0xFF283593);
  static const Color deepFocusText = Color(0xFFFFFFFF);

  /// 微型火种态胶囊（「5 分钟启动版」）。
  static const Color sparkStroke = Color(0xFFFFB300);
  static const Color sparkBg = Color(0xFFFFF8E1);

  /// 漫游主题态（弱底弱框，起止以 `~` 显示）。
  static const Color roamBg = Color(0x1FE3F2FD); // E3F2FD @12%
  static const Color roamStroke = Color(0x6690CAF9); // 90CAF9 @40%

  /// 庆祝勋章态（香槟金微光，界面文案「犒劳时刻」）。
  static const Color celebrationBg = Color(0xFFFFF3D6);
  static const Color celebrationStroke = Color(0xFFD4AF37);

  /// 🛡️ 能量补给带（派生渲染不入库；界面文案「留白缓冲」）。
  static const Color supplyBandBg = Color(0xFFE8F5E9);
  static const Color supplyBandStroke = Color(0xFF81C784);

  /// 🍃 自由流动区（派生；界面文案「自由支配时间」）。
  static const Color freeFlowBg = Color(0xFFF1F8E9);

  /// human/手工块（现实色，与 AI 彩色块区分；重叠双列时橙线提示放行）。
  static const Color humanBlockBg = Color(0x6678909C); // 78909C @40%
  static const Color overlapHint = Color(0xFFFF9800); // §3 橙色提示拍板

  /// fixed_slots 背景带 / 系统日历投影块。
  static const Color fixedSlotBg = Color(0xFFECEFF1);
  static const Color projectionBg = Color(0xFFF5F5F5);

  /// missed 中性标（拍板方案 A：原形态色降饱和 40% + 米黄左标，绝不红）。
  static const Color missedAccent = Color(0xFFFFF3E0);

  /// 安全线徽章（界面文案「今日底线守住啦」）。
  static const Color safelineOff = Color(0xFFBDBDBD);
  static const Color safelineOn = Color(0xFFD4AF37);

  /// 正文/次要文字（中性灰，不用纯黑）。
  static const Color textPrimary = Color(0xFF1A1C1E);
  static const Color textSecondary = Color(0xFF606468);
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

  /// 派生保护区三级绘制过滤阈值（分钟）：≥30 全渲染 / 15–30 图标 / <15 静默。
  static const int bandFullMin = 30;
  static const int bandIconMin = 15;
}
