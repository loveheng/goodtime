import '../util/schedule_day.dart';

import 'dart:convert';

/// app_settings 键注册表与读取助手（schedule-app.md §6/§9/§10）。
///
/// settings 与三表同库（app_settings KV，db.dart v2），统一字符串存储；
/// 类型编解码集中在命令层与查询层，本类只登记合法键与基础读取。
/// 内部键（mcp_token/mcp_enabled/last_daily_cut）不经本白名单，由服务层直写。
abstract final class SettingsKeys {
  /// 作息边界·起床（分钟 0..1439）——首启引导必填（§9），propose 硬前置（§6 校验铁律）
  static const wakeTime = 'wake_time';

  /// 作息边界·睡觉（分钟 0..1439；小于 wake ⇒ 跨午夜睡眠）
  static const sleepTime = 'sleep_time';

  /// 最小块粒度（分钟，首启可跳过）
  static const minBlockMinutes = 'min_block_minutes';

  /// 单日新增排量上限（块数，首启可跳过；休息权的模型支撑）
  static const dailyNewBlocksLimit = 'daily_new_blocks_limit';

  /// 晨间生理电量三档 high/normal/low（十轮拍板：手动点选、默认平稳、永不接传感器）
  static const todayEnergy = 'today_energy';

  /// 显式偏好文本（「周五晚上不排深度工作」），AI 经 update_settings 维护
  static const userRules = 'user_rules';

  /// 可选城市字符串（手动填、零定位权限，M4 天气投影消费）
  static const weatherLocation = 'weather_location';

  /// 例外日 JSON 数组 [{start,end,label}]（十三轮旅行模式，M4 消费，M1 先通存储）
  static const exceptions = 'exceptions';

  /// 填充率上限（百分比 5..100，默认 60，update_settings 可调——§6「默认可调」）
  static const fillRateLimit = 'fill_rate_limit';

  /// 快记草稿内部键（§10 快记入口 2026-10-06 拍板）：UI 专属三字段静默持久化，
  /// 刻意不入 [all] 白名单——update_settings 拒收，AI 不可触碰草稿；
  /// 由 QuickNoteDraftCommand（human-only）直写。
  static const quickNoteDraftText = 'quick_note_draft_text';
  static const quickNoteDraftImportant = 'quick_note_draft_important';
  static const quickNoteDraftDeadline = 'quick_note_draft_deadline';

  static const all = [
    wakeTime,
    sleepTime,
    minBlockMinutes,
    dailyNewBlocksLimit,
    todayEnergy,
    userRules,
    weatherLocation,
    exceptions,
    fillRateLimit,
  ];

  /// propose 硬前置（§6 校验铁律）：settings 未初始化（缺作息边界）时整单硬拒。
  static bool initialized(Map<String, String> s) =>
      s.containsKey(wakeTime) && s.containsKey(sleepTime);

  static int? intOf(Map<String, String> s, String key) =>
      s.containsKey(key) ? int.tryParse(s[key]!) : null;

  static String? stringOf(Map<String, String> s, String key) =>
      s.containsKey(key) ? s[key] : null;
}

/// 例外日（§13 旅行模式）：日期范围 + 标签；期间工作类 fixed_slots 挂起
/// （作息边界 wake/sleep 保留）、填充率自动降至 35%（ScheduleRules）。
class ExceptionWindow {
  const ExceptionWindow({required this.start, required this.end, this.label});

  final String start; // 'yyyy-MM-dd'（含）
  final String end; // 'yyyy-MM-dd'（含）
  final String? label;

  bool covers(DateTime date) {
    final d = DateTime(date.year, date.month, date.day);
    final s = tryParseIsoDate(start);
    final e = tryParseIsoDate(end);
    if (s == null || e == null) return false;
    return !d.isBefore(s) && !d.isAfter(e);
  }
}

/// 解析 settings.exceptions（JSON 数组 [{start,end,label?}]）；坏条目静默跳过。
List<ExceptionWindow> parseExceptions(Map<String, String> raw) {
  final v = SettingsKeys.stringOf(raw, SettingsKeys.exceptions);
  if (v == null || v.isEmpty) return const [];
  try {
    final list = jsonDecode(v) as List<dynamic>;
    return [
      for (final e in list)
        if (e is Map && e['start'] is String && e['end'] is String)
          ExceptionWindow(
            start: e['start'] as String,
            end: e['end'] as String,
            label: e['label'] is String ? e['label'] as String : null,
          ),
    ];
  } catch (_) {
    return const [];
  }
}
