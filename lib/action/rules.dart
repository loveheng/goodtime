/// 机械校验规则常量（schedule-app.md §6/§8/§11 拍板）。
///
/// **同源单一出处**：命令层机械校验、晨间 digest、派生渲染（补给带/自由流动区/
/// 双轨仪表盘）一律引本文件常量——规则改版先改这里，杜绝两处漂移。
abstract final class ScheduleRules {
  /// 填充率红线（§6 五轮拍板）：AI 块总时长 ≤ 当日可用时间 × 比例——留白是吸收意外的护城河。
  static const int fillRateDefaultPercent = 60;

  /// 低电量降档（§6 十轮拍板）：today_energy=low 时强制压至 25%，deep 禁排。
  static const int fillRateLowEnergyPercent = 25;

  /// 例外日降档（§13 十三轮拍板，M4 消费）：假期与出行期间 35%。
  static const int fillRateExceptionsPercent = 35;

  /// 呼吸律（§6 五轮升命令层）：单块超过 45min 后必须留 ≥15min 绝对空白。
  static const int breathMin = 45;
  static const int breathBufferMin = 15;

  /// 火种豁免刻度（§6 十轮推演补拍）：低电量日 is_day_spark 块 ≤15min
  /// 且执行内容取 min_viable_action——防「火种」沦为排 deep 的幌子。
  static const int sparkMaxMinutes = 15;

  /// 紧急阈值（§4 派生）：deadline 距今 ≤3 天自动升紧急。
  static const int urgentDays = 3;

  /// 孤儿冷藏阈值（§8 四轮修正）：孤儿 >7 天未动自动静默退出 digest。
  static const int orphanColdDays = 7;

  /// 顺延记账（§4/§8）：postpone_count ≥3 打「需人工决策」标。
  static const int postponeAlert = 3;

  /// 断流定义（§8 Clean Slate，M3 消费）：连续 3 作息日无交互 或 missed 累计 ≥5。
  static const int breakdownDays = 3;
  static const int breakdownMissedTotal = 5;

  /// 日切回扫天数（漏扫兜底：设备数日未开机也能补齐状态迁移）。
  static const int dailyCutLookbackDays = 60;

  /// 时段分区边界（§6 时段完成率「早/中/晚」，按当日分钟）：早 <720（12:00）、
  /// 午 720..1079、晚 ≥1080（18:00）。分区起手始终用作息窗起点 wake。
  static const int morningEndMin = 720;
  static const int afternoonEndMin = 1080;

  /// 耐受阈值激增判定线（§6）：次日 missed 率 ≥50% 视为「激增」。
  static const double toleranceMissedRateSpike = 0.5;

  /// 耐受阈值回退默认的换算（§6「回退默认=单日新增排量上限」）：块数 × 最小块粒度
  /// 折算成分钟（8 块 × 30min = 240min 为缺省组合下的回退值）。
  static const int toleranceFallbackBlocks = 8;
  static const int toleranceFallbackBlockMin = 30;

  /// 深潜净值（§6 主指标，2026-10-05 口径复核）：重要象限（Q1+Q2，
  /// plan.importance=true）沉浸的绝对分钟数，spark 计入、完成百分比彻底抛弃。
  static const String deepNetMetricNote =
      'deep net = Σ done minutes of important-quadrant plans (spark counts)';

  /// 全局背景注入预算（background-context-draft.md §2，2026-10-07 拍板）：
  /// scope=global 条目 ≤8 条、content 总字数 ≤400。度量口径=命令层 Dart
  /// String.length 单点（UTF-16 码元；中文场景码元≈码点，确定性优先）、
  /// **只计 content**——raw_source_text（溯源存证）与 tags 不进分子，堵
  /// 「往 raw 塞长文绕预算」暗门。终态双指标：任一超限即 budget_exceeded
  /// 整体拒绝（仅拦 ai actor，human 放行——校验刻度不对称）。
  static const int globalBackgroundsMax = 8;
  static const int globalBackgroundCharsMax = 400;

  /// applicable_dates 长窗劝改线（§2）：日期窗超过 14 天说明不是瞬态信息，
  /// AI 应建议改为长期背景（去掉日期窗）——劝改非拦截，防数组膨胀。
  static const int backgroundDateWindowAdviseDays = 14;
}
