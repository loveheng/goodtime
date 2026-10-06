import 'package:flutter/material.dart';
import 'dart:convert';

import 'package:flutter/services.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../data/settings.dart';
import '../models/fixed_slot.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// ISO 星期中文名（1=周一 … 7=周日），索引 0 占位。
const _weekdayNames = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/// 设置页（ui-spec §1/§10）：作息边界 + 一周节奏（fixed_slots 增删改，
/// 首启后唯一编辑入口）+ 例外日（旅行模式）+ 排程偏好（最小块粒度/单日排量上限/
/// 填充率上限/天气城市/user_rules）+ 外观三档 + MCP 服务 + JSON 导出。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  Repository get _repo => AppServices.repo;
  CommandHandler get _handler => CommandHandler(_repo);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListenableBuilder(
        listenable: Listenable.merge([_repo, AppServices.mcp]),
        builder: (context, _) => FutureBuilder<Map<String, Object?>>(
          future: ScheduleQueries(_repo).getSettings(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            final settings = snap.data ?? const {};
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _scheduleSection(context, settings),
                const SizedBox(height: 16),
                _rhythmSection(context),
                const SizedBox(height: 16),
                _exceptionsSection(context, settings),
                const SizedBox(height: 16),
                _prefsSection(context, settings),
                const SizedBox(height: 16),
                _appearanceSection(context, settings),
                const SizedBox(height: 16),
                _mcpSection(context),
                const SizedBox(height: 16),
                _exportSection(context),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _exportSection(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '数据出口'),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.ios_share),
              title: const Text('导出 JSON（全量）'),
              subtitle: const Text('计划/一周节奏/日程块/设置，本地持有随导随走'),
              onTap: () async {
                final data = await _repo.exportAll();
                const encoder = JsonEncoder.withIndent('  ');
                final json = encoder.convert(data);
                if (!context.mounted) return;
                await showDialog<void>(
                  context: context,
                  builder: (dialogContext) => AlertDialog(
                    title: const Text('全量导出'),
                    content: SizedBox(
                      width: double.maxFinite,
                      child: SingleChildScrollView(
                        child: SelectableText(json,
                            style: const TextStyle(fontSize: 11)),
                      ),
                    ),
                    actions: [
                      TextButton(
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: json));
                          Navigator.of(dialogContext).pop();
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('已复制到剪贴板')),
                          );
                        },
                        child: const Text('复制全部'),
                      ),
                      TextButton(
                        onPressed: () => Navigator.of(dialogContext).pop(),
                        child: const Text('关闭'),
                      ),
                    ],
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String text) => Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall),
      );

  /// 外观三档（ui-spec §0，2026-10-06 拍板解锁双主题）：跟随系统/浅色/深色。
  /// theme_mode 为 uiOnly 键——仅人可写（AI 经 update_settings 触碰即拒）。
  Widget _appearanceSection(
      BuildContext context, Map<String, Object?> settings) {
    final current = settings['theme_mode'] as String? ?? 'system';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '外观'),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'system', label: Text('跟随系统')),
                ButtonSegment(value: 'light', label: Text('浅色')),
                ButtonSegment(value: 'dark', label: Text('深色')),
              ],
              selected: {current},
              onSelectionChanged: (s) => _handler.execute(
                UpdateSettingsCommand(values: {'theme_mode': s.first}),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _scheduleSection(BuildContext context, Map<String, Object?> settings) {
    final wake = settings['wake_time'] as int? ?? 420;
    final sleep = settings['sleep_time'] as int? ?? 1380;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '作息边界'),
            Row(
              children: [
                Expanded(child: _timeTile(context, '起床', wake)),
                Expanded(child: _timeTile(context, '睡觉', sleep)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _timeTile(BuildContext context, String label, int minutes) {
    return ListTile(
      title: Text(label),
      subtitle: Text(clockOf(minutes), style: const TextStyle(fontSize: 20)),
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: TimeOfDay(hour: minutes ~/ 60, minute: minutes % 60),
        );
        if (picked != null) {
          final key = label == '起床' ? 'wake_time' : 'sleep_time';
          await _handler.execute(
              UpdateSettingsCommand(values: {key: picked.hour * 60 + picked.minute}));
        }
      },
    );
  }

  /// 一周节奏：fixed_slots 增删改（首启后唯一编辑入口，§4）。
  /// 整单批量替换经 UpdateFixedSlotsCommand——每个条目独立（工作日/周末可并存）。
  Widget _rhythmSection(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: _sectionTitle(context, '一周节奏')),
                TextButton.icon(
                  onPressed: () => _editSlotSheet(context, null),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('添加'),
                ),
              ],
            ),
            FutureBuilder<List<FixedSlot>>(
              future: _loadSlots(),
              builder: (context, snap) {
                final slots = snap.data ?? const <FixedSlot>[];
                if (slots.isEmpty) {
                  return Text('没有固定安排是完全正常的（自由职业预设）',
                      style: TextStyle(color: StColors.textSecondary));
                }
                return Column(
                  children: [
                    for (final s in slots)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(s.name),
                        subtitle: Text(
                            '${[for (final w in s.weekdays) _weekdayNames[w]].join('、')} ${clockOf(s.startMin)}–${clockOf(s.endMin)}'),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              icon: const Icon(Icons.edit_outlined, size: 18),
                              tooltip: '编辑',
                              onPressed: () => _editSlotSheet(context, s),
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete_outline, size: 18),
                              tooltip: '删除',
                              onPressed: () async {
                                final rest = slots.where((x) => x != s).toList();
                                await _handler.execute(
                                    UpdateFixedSlotsCommand(slots: rest));
                                if (context.mounted) {
                                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                                      content: Text('已删除「${s.name}」')));
                                }
                              },
                            ),
                          ],
                        ),
                      ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _editSlotSheet(BuildContext context, FixedSlot? existing) async {
    final slot = await showModalBottomSheet<FixedSlot>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _FixedSlotEditorSheet(existing: existing),
    );
    if (slot == null || !context.mounted) return;
    final current = await _loadSlots();
    final next = current.where((x) => x.id != slot.id).toList()..add(slot);
    await _handler.execute(UpdateFixedSlotsCommand(slots: next));
  }

  Future<List<FixedSlot>> _loadSlots() async {
    final slots = await _repo.listFixedSlots();
    return slots;
  }

  /// 例外日（§13 旅行模式）：日期范围 + 标签；期间工作类 fixed_slots 挂起、
  /// 填充率降至 35%。整列经 update_settings(exceptions=JSON) 落库。
  Widget _exceptionsSection(
      BuildContext context, Map<String, Object?> settings) {
    final list = (settings['exceptions'] as List<dynamic>? ?? const [])
        .whereType<Map<String, Object?>>()
        .map((e) => ExceptionWindow(
              start: e['start'] as String,
              end: e['end'] as String,
              label: e['label'] is String ? e['label'] as String : null,
            ))
        .toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: _sectionTitle(context, '假期与出行')),
                TextButton.icon(
                  onPressed: () => _editExceptionSheet(context, null, list),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('添加'),
                ),
              ],
            ),
            if (list.isEmpty)
              Text('没有特别安排是完全正常的',
                  style: TextStyle(color: StColors.textSecondary))
            else
              for (final w in list)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(w.label ?? '假期与出行'),
                  subtitle: Text('${w.start} → ${w.end}'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        tooltip: '编辑',
                        onPressed: () =>
                            _editExceptionSheet(context, w, list),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline, size: 18),
                        tooltip: '删除',
                        onPressed: () async {
                          final rest =
                              list.where((x) => x != w).toList();
                          await _saveExceptions(rest);
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('已删除该例外日')));
                          }
                        },
                      ),
                    ],
                  ),
                ),
          ],
        ),
      ),
    );
  }

  Future<void> _editExceptionSheet(BuildContext context, ExceptionWindow? existing,
      List<ExceptionWindow> current) async {
    final w = await showModalBottomSheet<ExceptionWindow>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _ExceptionEditorSheet(existing: existing),
    );
    if (w == null || !context.mounted) return;
    final next = current.where((x) => x != w).toList()..add(w);
    await _saveExceptions(next);
  }

  Future<void> _saveExceptions(List<ExceptionWindow> list) async {
    await _handler.execute(UpdateSettingsCommand(values: {
      SettingsKeys.exceptions: jsonEncode([
        for (final w in list)
          {
            'start': w.start,
            'end': w.end,
            if (w.label != null) 'label': w.label,
          },
      ]),
    }));
  }

  /// 排程偏好：最小块粒度 / 单日排量上限 / 填充率上限 / 天气城市 / user_rules。
  /// 各字段独立提交（update_settings 字段级），改一个存一个；非法值由命令层拦截。
  Widget _prefsSection(BuildContext context, Map<String, Object?> settings) {
    final minBlock = settings['min_block_minutes'] as int?;
    final dailyLimit = settings['daily_new_blocks_limit'] as int?;
    final fillRate = settings['fill_rate_limit'] as int?;
    final weather = settings['weather_location'] as String? ?? '';
    final rules = settings['user_rules'] as String? ?? '';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '排程偏好'),
            _intField(
              context,
              label: '最小块粒度（分钟）',
              initial: minBlock,
              onSaved: (v) => _handler.execute(
                  UpdateSettingsCommand(values: {SettingsKeys.minBlockMinutes: v})),
            ),
            _intField(
              context,
              label: '单日新增排量上限（块数）',
              initial: dailyLimit,
              onSaved: (v) => _handler.execute(UpdateSettingsCommand(
                  values: {SettingsKeys.dailyNewBlocksLimit: v})),
            ),
            _intField(
              context,
              label: '填充率上限（%）',
              initial: fillRate,
              hint: '默认 60，留白是吸收意外的护城河',
              onSaved: (v) => _handler.execute(
                  UpdateSettingsCommand(values: {SettingsKeys.fillRateLimit: v})),
            ),
            _textField(
              context,
              label: '天气城市（手动填，零定位权限）',
              initial: weather,
              onSaved: (v) => _handler.execute(UpdateSettingsCommand(
                  values: {SettingsKeys.weatherLocation: v})),
            ),
            _textField(
              context,
              label: '我的偏好（自然语言，如「周五晚上不排深度工作」）',
              initial: rules,
              maxLines: 3,
              onSaved: (v) => _handler.execute(
                  UpdateSettingsCommand(values: {SettingsKeys.userRules: v})),
            ),
          ],
        ),
      ),
    );
  }

  Widget _intField(BuildContext context,
      {required String label,
      required int? initial,
      String? hint,
      required Future<void> Function(int) onSaved}) {
    final ctrl = TextEditingController(text: initial?.toString() ?? '');
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: ctrl,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: label,
                hintText: hint,
                border: const OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ),
          const SizedBox(width: 8),
          TextButton(
            onPressed: () {
              final v = int.tryParse(ctrl.text.trim());
              if (v == null) {
                ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('请填数字'), duration: Duration(seconds: 1)));
                return;
              }
              onSaved(v);
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  Widget _textField(BuildContext context,
      {required String label,
      required String initial,
      int maxLines = 1,
      required Future<void> Function(String) onSaved}) {
    final ctrl = TextEditingController(text: initial);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          TextField(
            controller: ctrl,
            maxLines: maxLines,
            decoration: InputDecoration(
              labelText: label,
              border: const OutlineInputBorder(),
              isDense: true,
            ),
          ),
          TextButton(
            onPressed: () => onSaved(ctrl.text.trim()),
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  Widget _mcpSection(BuildContext context) {
    final mcp = AppServices.mcp;
    final running = mcp.running;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, 'MCP 服务'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Row(
                children: [
                  Container(
                    width: 10,
                    height: 10,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: running ? const Color(0xFF81C784) : StColors.safelineOff,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(running ? '运行中' : '已停止'),
                ],
              ),
              value: running,
              onChanged: (on) async {
                if (on) {
                  final error = await mcp.enable();
                  if (error != null && context.mounted) {
                    ScaffoldMessenger.of(context)
                        .showSnackBar(SnackBar(content: Text(error)));
                  }
                } else {
                  await mcp.disable();
                }
              },
            ),
            FutureBuilder<String>(
              future: mcp.endpoint(),
              builder: (context, snap) => ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: const Text('连接地址'),
                subtitle: Text(snap.data ?? '…'),
                trailing: IconButton(
                  icon: const Icon(Icons.copy, size: 18),
                  tooltip: '复制',
                  onPressed: snap.data == null
                      ? null
                      : () {
                          Clipboard.setData(ClipboardData(text: snap.data!));
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(content: Text('已复制'), duration: Duration(seconds: 1)),
                          );
                        },
                ),
              ),
            ),
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('访问令牌'),
              subtitle: Text(mcp.token ?? '…'),
              trailing: TextButton(
                onPressed: () => mcp.regenerateToken(),
                child: const Text('重新生成'),
              ),
            ),
            Text('桌面 AI 用此地址配对（USB 场景先 adb reverse tcp:8765 tcp:8765）',
                style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}

/// 一周节奏单条编辑（名称 + ISO 星期多选 + 起止钟点）。
class _FixedSlotEditorSheet extends StatefulWidget {
  const _FixedSlotEditorSheet({this.existing});
  final FixedSlot? existing;

  @override
  State<_FixedSlotEditorSheet> createState() => _FixedSlotEditorSheetState();
}

class _FixedSlotEditorSheetState extends State<_FixedSlotEditorSheet> {
  late final TextEditingController _name;
  late final Set<int> _weekdays;
  late TimeOfDay _start;
  late TimeOfDay _end;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _name = TextEditingController(text: e?.name ?? '');
    _weekdays = {...?e?.weekdays};
    _start = e == null
        ? const TimeOfDay(hour: 9, minute: 0)
        : TimeOfDay(hour: e.startMin ~/ 60, minute: e.startMin % 60);
    _end = e == null
        ? const TimeOfDay(hour: 18, minute: 0)
        : TimeOfDay(hour: (e.endMin % 1440) ~/ 60, minute: e.endMin % 60);
  }

  Future<void> _pick(BuildContext context, bool isStart) async {
    final picked = await showTimePicker(
      context: context,
      initialTime: isStart ? _start : _end,
    );
    if (picked != null) setState(() => isStart ? _start = picked : _end = picked);
  }

  @override
  Widget build(BuildContext context) {
    final startMin = _start.hour * 60 + _start.minute;
    final endMin = _end.hour * 60 + _end.minute;
    return SafeArea(
      key: const Key('fixedSlotEditor'),
      child: Padding(
        padding: EdgeInsets.fromLTRB(
            20, 16, 20, 20 + MediaQuery.of(context).viewInsets.bottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.existing == null ? '添加固定安排' : '编辑固定安排',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            TextField(
              controller: _name,
              decoration: const InputDecoration(
                labelText: '名称（如「上班」「健身」）',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 12),
            const Text('适用星期（可多选）'),
            Wrap(
              spacing: 6,
              children: [
                for (var w = 1; w <= 7; w++)
                  FilterChip(
                    label: Text(_weekdayNames[w]),
                    selected: _weekdays.contains(w),
                    onSelected: (on) =>
                        setState(() => on ? _weekdays.add(w) : _weekdays.remove(w)),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('开始'),
                    subtitle: Text(clockOf(startMin)),
                    onTap: () => _pick(context, true),
                  ),
                ),
                Expanded(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('结束'),
                    subtitle: Text(clockOf(endMin)),
                    onTap: () => _pick(context, false),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: () {
                if (_name.text.trim().isEmpty) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('名称不能为空')));
                  return;
                }
                if (_weekdays.isEmpty) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('至少选一个适用星期')));
                  return;
                }
                if (endMin <= startMin) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('结束须晚于开始')));
                  return;
                }
                Navigator.of(context).pop(FixedSlot(
                  id: widget.existing?.id,
                  name: _name.text.trim(),
                  weekdays: [..._weekdays]..sort(),
                  startMin: startMin,
                  endMin: endMin,
                ));
              },
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }
}

/// 例外日单条编辑（起止日期 + 标签）。
class _ExceptionEditorSheet extends StatefulWidget {
  const _ExceptionEditorSheet({this.existing});
  final ExceptionWindow? existing;

  @override
  State<_ExceptionEditorSheet> createState() => _ExceptionEditorSheetState();
}

class _ExceptionEditorSheetState extends State<_ExceptionEditorSheet> {
  late final TextEditingController _label;
  late DateTime _start;
  late DateTime _end;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _label = TextEditingController(text: e?.label ?? '');
    final now = DateTime.now();
    _start = e != null ? (tryParseIsoDate(e.start) ?? now) : now;
    _end = e != null ? (tryParseIsoDate(e.end) ?? now) : now;
  }

  Future<void> _pick(BuildContext context, bool isStart) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: isStart ? _start : _end,
      firstDate: DateTime(2020),
      lastDate: DateTime(2035),
    );
    if (picked != null) setState(() => isStart ? _start = picked : _end = picked);
  }

  @override
  Widget build(BuildContext context) {
    final startIso = isoDate(_start);
    final endIso = isoDate(_end);
    return SafeArea(
      key: const Key('exceptionEditor'),
      child: Padding(
        padding: EdgeInsets.fromLTRB(
            20, 16, 20, 20 + MediaQuery.of(context).viewInsets.bottom),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.existing == null ? '添加假期与出行' : '编辑假期与出行',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('从'),
                    subtitle: Text(startIso),
                    onTap: () => _pick(context, true),
                  ),
                ),
                Expanded(
                  child: ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('到'),
                    subtitle: Text(endIso),
                    onTap: () => _pick(context, false),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _label,
              decoration: const InputDecoration(
                labelText: '标签（如「云南行」「春节」）',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: () {
                if (endIso.compareTo(startIso) < 0) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('结束日期须不早于开始')));
                  return;
                }
                Navigator.of(context).pop(ExceptionWindow(
                  start: startIso,
                  end: endIso,
                  label: _label.text.trim().isEmpty ? null : _label.text.trim(),
                ));
              },
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }
}

class FixedSlotRow {
  const FixedSlotRow(this.name, this.weekdaysLabel, this.startMin, this.endMin);
  final String name;
  final String weekdaysLabel;
  final int startMin;
  final int endMin;
}
