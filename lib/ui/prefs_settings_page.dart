import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../action/queries.dart';
import '../data/settings.dart';

/// 排程偏好设置页（ui-spec §1 子页化 2026-10-08 拍板）：最小块粒度/单日排量上限/
/// 填充率上限/天气城市/我的偏好/身份画像。各字段独立提交（update_settings
/// 字段级），改一个存一个；非法值由命令层拦截。自 settings_page 大平铺迁出。
class PrefsSettingsPage extends StatefulWidget {
  const PrefsSettingsPage({super.key});

  @override
  State<PrefsSettingsPage> createState() => _PrefsSettingsPageState();
}

class _PrefsSettingsPageState extends State<PrefsSettingsPage> {
  CommandHandler get _handler => CommandHandler(AppServices.repo);

  Map<String, Object?>? _settings;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final s = await ScheduleQueries(AppServices.repo).getSettings();
    if (!mounted) return;
    setState(() => _settings = s);
  }

  @override
  Widget build(BuildContext context) {
    final s = _settings;
    return Scaffold(
      appBar: AppBar(title: const Text('排程偏好')),
      body: s == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _intField(
                  context,
                  label: '最小块粒度（分钟）',
                  initial: s['min_block_minutes'] as int?,
                  onSaved: (v) => _save(
                      {SettingsKeys.minBlockMinutes: v}),
                ),
                _intField(
                  context,
                  label: '单日新增排量上限（块数）',
                  initial: s['daily_new_blocks_limit'] as int?,
                  onSaved: (v) => _save(
                      {SettingsKeys.dailyNewBlocksLimit: v}),
                ),
                _intField(
                  context,
                  label: '填充率上限（%）',
                  initial: s['fill_rate_limit'] as int?,
                  hint: '默认 60，留白是吸收意外的护城河',
                  onSaved: (v) =>
                      _save({SettingsKeys.fillRateLimit: v}),
                ),
                _textField(
                  context,
                  label: '天气城市（手动填，零定位权限）',
                  initial: s['weather_location'] as String? ?? '',
                  onSaved: (v) =>
                      _save({SettingsKeys.weatherLocation: v}),
                ),
                _textField(
                  context,
                  label: '我的偏好（自然语言，如「周五晚上不排深度工作」）',
                  initial: s['user_rules'] as String? ?? '',
                  maxLines: 3,
                  onSaved: (v) => _save({SettingsKeys.userRules: v}),
                ),
                _textField(
                  context,
                  label: '身份画像（系统提示词）：描述你是谁、处境与硬约束，随 MCP 注入 AI 排程上下文',
                  initial: s['identity_prompt'] as String? ?? '',
                  maxLines: 4,
                  onSaved: (v) =>
                      _save({SettingsKeys.identityPrompt: v}),
                ),
              ],
            ),
    );
  }

  Future<void> _save(Map<String, Object?> values) async {
    try {
      await _handler.execute(UpdateSettingsCommand(values: values));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('已保存'), duration: Duration(seconds: 1)));
      }
    } on Exception catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(e.toString().replaceFirst(RegExp(r'^Exception: '), '')),
            duration: const Duration(seconds: 3)));
      }
    }
    await _reload();
  }

  Widget _intField(BuildContext context,
      {required String label,
      required int? initial,
      String? hint,
      required Future<void> Function(int) onSaved}) {
    final ctrl = TextEditingController(text: initial?.toString() ?? '');
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              key: ValueKey('$label|$initial'),
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
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          TextField(
            key: ValueKey('$label|$initial'),
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
}
