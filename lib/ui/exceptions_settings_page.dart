import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/settings.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 假期与出行设置页（ui-spec §1 子页化 2026-10-08 拍板）：例外日（§13 旅行模式）
/// 日期范围 + 标签；期间工作类 fixed_slots 挂起、填充率降至 35%。
/// 整列经 update_settings(exceptions=JSON) 落库。自 settings_page 大平铺迁出。
class ExceptionsSettingsPage extends StatefulWidget {
  const ExceptionsSettingsPage({super.key});

  @override
  State<ExceptionsSettingsPage> createState() => _ExceptionsSettingsPageState();
}

class _ExceptionsSettingsPageState extends State<ExceptionsSettingsPage> {
  CommandHandler get _handler => CommandHandler(AppServices.repo);

  List<ExceptionWindow>? _list;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    // 读侧委托数据层解析器（R2：UI 禁内联 JSON 编解码）
    final raw = await AppServices.repo.settingsAll();
    final list = parseExceptions(raw);
    if (!mounted) return;
    setState(() => _list = list);
  }

  Future<void> _save(List<ExceptionWindow> list) async {
    // 写侧只传结构化值，JSON 编解码归命令层（R2 口径；非法形态命令层拦截）
    await _handler.execute(UpdateSettingsCommand(values: {
      SettingsKeys.exceptions: [
        for (final w in list)
          {
            'start': w.start,
            'end': w.end,
            if (w.label != null) 'label': w.label,
          },
      ],
    }));
    await _reload();
  }

  Future<void> _edit(ExceptionWindow? existing) async {
    final current = _list ?? const <ExceptionWindow>[];
    final w = await showModalBottomSheet<ExceptionWindow>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _ExceptionEditorSheet(existing: existing),
    );
    if (w == null || !mounted) return;
    await _save(current.where((x) => x != w).toList()..add(w));
  }

  @override
  Widget build(BuildContext context) {
    final list = _list;
    return Scaffold(
      appBar: AppBar(title: const Text('假期与出行')),
      body: list == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text('没有特别安排是完全正常的',
                    style: list.isEmpty
                        ? TextStyle(color: StColors.textSecondary)
                        : Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: StColors.textSecondary)),
                const SizedBox(height: 8),
                for (final w in list)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(w.label ?? '假期与出行'),
                    subtitle: Text('${w.start} → ${w.end}'),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          icon: const Icon(Icons.edit_outlined, size: 18),
                          tooltip: '编辑',
                          onPressed: () => _edit(w),
                        ),
                        IconButton(
                          icon: const Icon(Icons.delete_outline, size: 18),
                          tooltip: '删除',
                          onPressed: () async {
                            // 跨 await 后用预捕获 messenger（use_build_context_synchronously）
                            final messenger = ScaffoldMessenger.of(context);
                            await _save(list.where((x) => x != w).toList());
                            messenger.showSnackBar(
                                const SnackBar(content: Text('已删除该例外日')));
                          },
                        ),
                      ],
                    ),
                  ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: () => _edit(null),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('添加'),
                ),
              ],
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

  @override
  void dispose() {
    _label.dispose();
    super.dispose();
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
