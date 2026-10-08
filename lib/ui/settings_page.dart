import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../action/queries.dart';
import '../app_services.dart';
import '../data/settings.dart';
import '../theme/tokens.dart';
import '../update/remote_config_store.dart';
import '../ui/update_page.dart';
import '../util/schedule_day.dart';
import 'exceptions_settings_page.dart';
import 'mcp_settings_page.dart';
import 'prefs_settings_page.dart';
import 'rhythm_settings_page.dart';

/// 设置页（ui-spec §1 子页化 2026-10-08 拍板）：目录形态——本页只留高频项
/// （作息边界/外观）+ AI 建议 + 编辑器簇入口行（一周节奏/假期与出行/排程偏好/
/// MCP 服务=独立子页，对齐 §1「SET→MCPG/EXC/SLOTS」原 IA）+ 应用更新/数据出口。
/// 编辑器全量迁出：rhythm/exceptions/prefs/mcp_settings_page 四子页承载。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  CommandHandler get _handler => CommandHandler(AppServices.repo);

  late final StreamSubscription<void> _sugSub;
  Future<List<Map<String, Object?>>>? _sugFuture;

  @override
  void initState() {
    super.initState();
    _sugFuture = _readSuggestions();
    _sugSub = AppServices.mcp.suggestionStream.listen((_) {
      if (!mounted) return;
      setState(() => _sugFuture = _readSuggestions());
    });
  }

  @override
  void dispose() {
    _sugSub.cancel();
    super.dispose();
  }

  Future<List<Map<String, Object?>>> _readSuggestions() async {
    final raw = await AppServices.repo.settingsGet(SettingsKeys.aiSettingSuggestions);
    if (raw == null || raw.isEmpty) return const [];
    final decoded = jsonDecode(raw);
    return decoded is List
        ? decoded
            .whereType<Map<Object?, Object?>>()
            .map((e) => Map<String, Object?>.from(e))
            .toList()
        : const [];
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListenableBuilder(
        listenable: Listenable.merge([AppServices.repo, AppServices.mcp]),
        builder: (context, _) => FutureBuilder<Map<String, Object?>>(
          future: ScheduleQueries(AppServices.repo).getSettings(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            final settings = snap.data ?? const {};
            return ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _aiSuggestionsSection(context),
                const SizedBox(height: 16),
                _scheduleSection(context, settings),
                const SizedBox(height: 16),
                _entryRow(context,
                    icon: Icons.view_week_outlined,
                    title: '一周节奏',
                    subtitle: '有固定安排的时段',
                    page: (_) => const RhythmSettingsPage()),
                _entryRow(context,
                    icon: Icons.beach_access_outlined,
                    title: '假期与出行',
                    subtitle: '例外日：期间挂起工作节奏',
                    page: (_) => const ExceptionsSettingsPage()),
                _entryRow(context,
                    icon: Icons.tune,
                    title: '排程偏好',
                    subtitle: '粒度/排量/填充率/我的偏好',
                    page: (_) => const PrefsSettingsPage()),
                _entryRow(context,
                    icon: Icons.lan_outlined,
                    title: 'MCP 服务',
                    subtitle: AppServices.mcp.running ? '运行中' : '已停止',
                    page: (_) => const McpSettingsPage()),
                const SizedBox(height: 16),
                _appearanceSection(context, settings),
                const SizedBox(height: 16),
                _updateSection(context),
                const SizedBox(height: 16),
                _exportSection(context),
              ],
            );
          },
        ),
      ),
    );
  }

  /// 子页入口行（目录形态 2026-10-08 拍板）：图标+名称+一行摘要+›。
  Widget _entryRow(BuildContext context,
      {required IconData icon,
      required String title,
      String? subtitle,
      required WidgetBuilder page}) {
    return Card(
      margin: const EdgeInsets.only(bottom: 4),
      child: ListTile(
        leading: Icon(icon, size: 22),
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: Icon(Icons.chevron_right,
            size: 20, color: StColors.textSecondary),
        onTap: () =>
            Navigator.of(context).push(MaterialPageRoute<void>(builder: page)),
      ),
    );
  }

  Widget _aiSuggestionsSection(BuildContext context) {
    return FutureBuilder<List<Map<String, Object?>>>(
      future: _sugFuture,
      builder: (context, snap) {
        final items = snap.data ?? const <Map<String, Object?>>[];
        if (items.isEmpty) return const SizedBox.shrink();
        return Card(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _sectionTitle(context, 'AI 建议（待你决断）'),
                const SizedBox(height: 8),
                for (final it in items) _suggestionTile(context, it),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _suggestionTile(BuildContext context, Map<String, Object?> it) {
    final key = it['key']?.toString() ?? '';
    final reason = it['reason']?.toString() ?? '';
    final suggested = it['suggested_value']?.toString();
    final hasValue = suggested != null && suggested.isNotEmpty;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.lightbulb_outline),
      title: Text('建议修改「$key」'),
      subtitle: Text(hasValue ? '建议值：$suggested\n原因：$reason' : '原因：$reason'),
      isThreeLine: hasValue,
      // 采纳=写入建议值（✓）；忽略=移除建议（✕）。✓ 语义修复：原实现
      // check 图标行为却是删除，与用户肌肉记忆相反（2026-10-08 拍板）。
      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
        if (hasValue)
          IconButton(
            icon: const Icon(Icons.check_circle_outline),
            tooltip: '采纳',
            onPressed: () => _applySuggestion(key, suggested),
          ),
        IconButton(
          icon: const Icon(Icons.close),
          tooltip: '忽略',
          onPressed: () => _dismissSuggestion(it),
        ),
      ]),
    );
  }

  /// 采纳（2026-10-08 拍板）：建议值写入对应设置键后移除该建议。
  /// 键白名单与发起侧同源（mcp tools.dart suggest_user_setting 的 uiOnly 五键）；
  /// 非法值由命令层拦截并回错（同页其它设置写入口径）。
  Future<void> _applySuggestion(String key, String suggested) async {
    try {
      await _handler.execute(UpdateSettingsCommand(values: {key: suggested}));
      await _removeSuggestion((e) => e['key'] == key);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('已采纳：$key = $suggested'),
          duration: const Duration(seconds: 2)));
    } on ActionException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.message), duration: const Duration(seconds: 3)));
    }
  }

  Future<void> _dismissSuggestion(Map<String, Object?> it) =>
      _removeSuggestion((e) => e['id'] == it['id']);

  /// 按判据移除建议条目并刷新（采纳/忽略共用写通道）。
  Future<void> _removeSuggestion(
      bool Function(Map<String, Object?>) test) async {
    final raw = await AppServices.repo.settingsGet(SettingsKeys.aiSettingSuggestions);
    if (raw == null) return;
    final list = (jsonDecode(raw) as List)
        .whereType<Map<Object?, Object?>>()
        .map((e) => Map<String, Object?>.from(e))
        .where((e) => !test(e))
        .toList();
    await AppServices.repo
        .settingsSet({SettingsKeys.aiSettingSuggestions: jsonEncode(list)});
    if (!mounted) return;
    setState(() => _sugFuture = _readSuggestions());
  }

  /// 应用内自更新 + 配置热更入口（docs/guide/self-update.md）。
  /// 同时展示缓存的远程公告（RemoteConfigStore 随检查更新刷新）。
  Widget _updateSection(BuildContext context) {
    final announcement = RemoteConfigStore.instance.current.announcement;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle(context, '应用更新'),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.system_update_alt),
              title: const Text('检查更新'),
              subtitle: const Text('应用内自更新 + 远程公告/MCP 指令热更'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const UpdatePage()),
              ),
            ),
            if (announcement != null && announcement.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.campaign_outlined, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(announcement,
                          style: Theme.of(context).textTheme.bodySmall),
                    ),
                  ],
                ),
              ),
          ],
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
                final data = await AppServices.repo.exportAll();
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

  /// 作息边界（高频项留本页直改；引导①必填项的日常微调口）。
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
      contentPadding: EdgeInsets.zero,
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
}
