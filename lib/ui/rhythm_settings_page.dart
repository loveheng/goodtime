import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/fixed_slot.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// ISO 星期中文名（1=周一 … 7=周日），索引 0 占位。
const _weekdayNames = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/// 一周节奏设置页（ui-spec §1 子页化 2026-10-08 拍板）：fixed_slots 增删改，
/// 首启后唯一编辑入口。整单批量替换经 UpdateFixedSlotsCommand——每个条目独立
/// （工作日/周末可并存）。自 settings_page 大平铺迁出（目录页只留入口行）。
class RhythmSettingsPage extends StatefulWidget {
  const RhythmSettingsPage({super.key});

  @override
  State<RhythmSettingsPage> createState() => _RhythmSettingsPageState();
}

class _RhythmSettingsPageState extends State<RhythmSettingsPage> {
  Repository get _repo => AppServices.repo;
  CommandHandler get _handler => CommandHandler(_repo);

  List<FixedSlot>? _slots;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final slots = await _repo.listFixedSlots();
    if (!mounted) return;
    setState(() => _slots = slots);
  }

  Future<void> _editSlot(FixedSlot? existing) async {
    final slot = await showModalBottomSheet<FixedSlot>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _FixedSlotEditorSheet(existing: existing),
    );
    if (slot == null || !mounted) return;
    final current = await _repo.listFixedSlots();
    final next = current.where((x) => x.id != slot.id).toList()..add(slot);
    await _handler.execute(UpdateFixedSlotsCommand(slots: next));
    await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final slots = _slots;
    return Scaffold(
      appBar: AppBar(title: const Text('一周节奏')),
      body: slots == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text('没有固定安排是完全正常的（自由职业预设）',
                    style: slots.isEmpty
                        ? TextStyle(color: StColors.textSecondary)
                        : Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: StColors.textSecondary)),
                const SizedBox(height: 8),
                for (final s in slots)
                  ListTile(
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
                          onPressed: () => _editSlot(s),
                        ),
                        IconButton(
                          icon: const Icon(Icons.delete_outline, size: 18),
                          tooltip: '删除',
                          onPressed: () async {
                            // 跨 await 后用预捕获 messenger（use_build_context_synchronously）
                            final messenger = ScaffoldMessenger.of(context);
                            final rest = slots.where((x) => x != s).toList();
                            await _handler
                                .execute(UpdateFixedSlotsCommand(slots: rest));
                            messenger.showSnackBar(
                                SnackBar(content: Text('已删除「${s.name}」')));
                            await _reload();
                          },
                        ),
                      ],
                    ),
                  ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: () => _editSlot(null),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('添加'),
                ),
              ],
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

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
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
