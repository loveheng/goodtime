import 'dart:async';

import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/settings.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 快记入口（ui-spec §1/§3；2026-10-06 拍板）：右下角 FAB + 键盘吸附展开抽屉，
/// 自顶部常驻快记条迁改（大屏单手触达；放工守卫态不撤=「快记永不关门」平移）。

/// 快记草稿仓：三字段静默持久化于 app_settings KV（quick_note_draft_* 内部键，
/// AI 不可达），写入走 QuickNoteDraftCommand（R1：UI 不直连 repo 写）。
/// 即写即存 + 中途收起零询问不丢内容；仅保存成功/清空收起才清除。
class QuickNoteDraft {
  const QuickNoteDraft({this.text = '', this.important = false, this.deadline});

  final String text;
  final bool important;
  final DateTime? deadline;

  bool get isEmpty => text.trim().isEmpty;

  /// FAB 琥珀点信号（草稿在盘时点亮）。
  static final ValueNotifier<bool> hasDraft = ValueNotifier(false);

  /// 打开抽屉前预载（在 FAB tap 内 await，防异步回填覆盖用户输入）。
  static Future<QuickNoteDraft> load() async {
    final repo = AppServices.repo;
    final text = await repo.settingsGet(SettingsKeys.quickNoteDraftText) ?? '';
    final important =
        await repo.settingsGet(SettingsKeys.quickNoteDraftImportant) == '1';
    final deadlineIso =
        await repo.settingsGet(SettingsKeys.quickNoteDraftDeadline);
    final draft = QuickNoteDraft(
      text: text,
      important: important,
      deadline: deadlineIso == null ? null : tryParseIsoDate(deadlineIso),
    );
    hasDraft.value = !draft.isEmpty;
    return draft;
  }

  static Future<void> persist(QuickNoteDraft draft) async {
    await CommandHandler(AppServices.repo).execute(QuickNoteDraftCommand(
      text: draft.text,
      importance: draft.important,
      deadline: draft.deadline == null ? null : isoDate(draft.deadline!),
    ));
    hasDraft.value = !draft.isEmpty;
  }
}

/// 右下角快记悬浮钮：两 Tab 恒在；草稿在盘时右上角 6dp 琥珀点（sparkStroke）。
class QuickNoteFab extends StatefulWidget {
  const QuickNoteFab({super.key});

  @override
  State<QuickNoteFab> createState() => _QuickNoteFabState();
}

class _QuickNoteFabState extends State<QuickNoteFab> {
  @override
  void initState() {
    super.initState();
    // 冷启动回读草稿在盘态（点亮琥珀点）。
    // DEGRADE: 回读失败按无草稿处理（琥珀点不亮），不挡捕获主路径，无兜底提示。
    unawaited(QuickNoteDraft.load().then<void>(
      (_) {},
      onError: (Object _) {},
    ));
  }

  Future<void> _open(BuildContext context) async {
    final draft = await QuickNoteDraft.load();
    if (!context.mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => Padding(
        padding: EdgeInsets.only(
            bottom: MediaQuery.viewInsetsOf(sheetContext).bottom),
        child: QuickNotePanel(initial: draft),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: QuickNoteDraft.hasDraft,
      builder: (context, has, child) => Stack(
        clipBehavior: Clip.none,
        children: [
          child!,
          if (has)
            Positioned(
              right: -2,
              top: -2,
              child: Container(
                key: const Key('quick_note_draft_dot'),
                width: 6,
                height: 6,
                decoration: BoxDecoration(
                  color: StColors.sparkStroke,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: Theme.of(context).colorScheme.surface,
                    width: 2,
                  ),
                ),
              ),
            ),
        ],
      ),
      child: FloatingActionButton(
        tooltip: '记一笔',
        onPressed: () => _open(context),
        child: const Icon(Icons.edit_note),
      ),
    );
  }
}

/// 快记抽屉面板（键盘吸附）：输入框 + 一颗星 + 可选截止日 + 「收入清单」。
/// 文本草稿 300ms 防抖即写即存，星/死线即改即存；保存成功或清空收起才清草稿。
class QuickNotePanel extends StatefulWidget {
  const QuickNotePanel({super.key, required this.initial});

  final QuickNoteDraft initial;

  @override
  State<QuickNotePanel> createState() => _QuickNotePanelState();
}

class _QuickNotePanelState extends State<QuickNotePanel> {
  late final TextEditingController _controller;
  late bool _important;
  DateTime? _deadline;
  Timer? _debounce;
  bool _submitting = false;
  bool _submitted = false;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initial.text);
    _important = widget.initial.important;
    _deadline = widget.initial.deadline;
    // 草稿回填时光标落末尾（拍板交互细则）。
    _controller.selection =
        TextSelection.collapsed(offset: _controller.text.length);
  }

  @override
  void dispose() {
    // 中途收起（下滑/点遮罩/切走）：零询问，未落盘的防抖尾巴立即冲刷；
    // 清空后收起=显式清草稿（persist 空 text 即整组清除）。
    _debounce?.cancel();
    if (!_submitted) {
      unawaited(QuickNoteDraft.persist(_current).catchError((Object _) {}));
    }
    _controller.dispose();
    super.dispose();
  }

  QuickNoteDraft get _current => QuickNoteDraft(
        text: _controller.text,
        important: _important,
        deadline: _deadline,
      );

  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      unawaited(QuickNoteDraft.persist(_current).catchError((Object _) {}));
    });
  }

  Future<void> _toggleStar() async {
    setState(() => _important = !_important);
    await QuickNoteDraft.persist(_current);
  }

  Future<void> _pickDeadline() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _deadline ?? now,
      firstDate: now,
      lastDate: now.add(const Duration(days: 365)),
    );
    if (picked == null) return;
    setState(() => _deadline = picked);
    await QuickNoteDraft.persist(_current);
  }

  Future<void> _clearDeadline() async {
    setState(() => _deadline = null);
    await QuickNoteDraft.persist(_current);
  }

  Future<void> _submit() async {
    final title = _controller.text.trim();
    if (title.isEmpty || _submitting) return;
    setState(() => _submitting = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await CommandHandler(AppServices.repo).execute(
        QuickCaptureCommand(
          title: title,
          importance: _important,
          deadline: _deadline == null ? null : isoDate(_deadline!),
        ),
      );
      _submitted = true;
      _debounce?.cancel();
      await QuickNoteDraft.persist(const QuickNoteDraft());
      if (mounted) Navigator.of(context).pop();
      messenger.showSnackBar(
        SnackBar(
          content: const Text('已收入清单'),
          duration: const Duration(seconds: 1),
          // 浮动+FAB 净空：不与右下角快记 FAB 重叠（ui-spec §3 层叠规则）
          behavior: SnackBarBehavior.floating,
          margin:
              const EdgeInsets.fromLTRB(16, 0, 16, StScale.fabClearanceDp),
        ),
      );
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.all(StScale.insetLg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _controller,
              autofocus: true,
              minLines: 1,
              maxLines: 3,
              textInputAction: TextInputAction.done,
              onChanged: _onChanged,
              onSubmitted: (_) => _submit(),
              decoration: const InputDecoration(
                hintText: '记一笔，30 秒的事',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: StScale.insetSm),
            Row(
              children: [
                IconButton(
                  icon: Icon(
                    _important ? Icons.star : Icons.star_border,
                    color: _important ? scheme.primary : null,
                  ),
                  tooltip: '标重要',
                  onPressed: _toggleStar,
                ),
                if (_deadline == null)
                  IconButton(
                    icon: const Icon(Icons.event_outlined),
                    tooltip: '截止',
                    onPressed: _pickDeadline,
                  )
                else
                  TextButton(
                    style: TextButton.styleFrom(
                      minimumSize: const Size(
                          StScale.blockMinHeightDp, StScale.blockMinHeightDp),
                    ),
                    onPressed: _clearDeadline,
                    child: Text(isoDate(_deadline!)),
                  ),
                const Spacer(),
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(
                        64, StScale.blockMinHeightDp), // 44dp 触控钳制（§0.3）
                  ),
                  onPressed: _submitting ? null : _submit,
                  icon: const Icon(Icons.add),
                  label: const Text('收入清单'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
