import 'package:flutter/material.dart';
import 'dart:convert';

import 'package:flutter/services.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../app_services.dart';
import '../data/repository.dart';
import '../models/fixed_slot.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 设置页 M1（ui-spec §1/§10）：作息边界编辑 + 一周节奏只读展示（编辑器 M2）
/// + MCP 服务（开关/状态灯/连接地址/访问令牌，ui-spec §0.4）。
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

  Widget _rhythmSection(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '一周节奏'),
            FutureBuilder<List<FixedSlotRow>>(
              future: _loadSlots(),
              builder: (context, snap) {
                final slots = snap.data ?? const <FixedSlotRow>[];
                if (slots.isEmpty) {
                  return const Text('没有固定安排是完全正常的',
                      style: TextStyle(color: StColors.textSecondary));
                }
                return Column(
                  children: [
                    for (final s in slots)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(s.name),
                        subtitle: Text('${s.weekdaysLabel} ${clockOf(s.startMin)}–${clockOf(s.endMin)}'),
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

  Future<List<FixedSlotRow>> _loadSlots() async {
    final slots = await _repo.listFixedSlots();
    const names = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    return [
      for (final FixedSlot s in slots)
        FixedSlotRow(
          s.name,
          s.weekdays.map((w) => names[w]).join('、'),
          s.startMin,
          s.endMin,
        ),
    ];
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
            const Text('桌面 AI 用此地址配对（USB 场景先 adb reverse tcp:8765 tcp:8765）',
                style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
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
