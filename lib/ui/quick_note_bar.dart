import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../util/schedule_day.dart';

/// 快记条（ui-spec §0.4/§3.3）：输入框 + 一颗星 + 可选截止日，零细节捕获；
/// 永不关门（放工守卫例外条款）。落 QuickCapture 命令（默认 light/anywhere 愿望池）。
class QuickNoteBar extends StatefulWidget {
  const QuickNoteBar({super.key});

  @override
  State<QuickNoteBar> createState() => _QuickNoteBarState();
}

class _QuickNoteBarState extends State<QuickNoteBar> {
  final _controller = TextEditingController();
  bool _important = false;
  DateTime? _deadline;
  bool _submitting = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final title = _controller.text.trim();
    if (title.isEmpty || _submitting) return;
    setState(() => _submitting = true);
    try {
      await CommandHandler(AppServices.repo).execute(
        QuickCaptureCommand(
          title: title,
          importance: _important,
          deadline: _deadline == null ? null : isoDate(_deadline!),
        ),
      );
      _controller.clear();
      setState(() {
        _important = false;
        _deadline = null;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('已收入清单'), duration: Duration(seconds: 1)),
        );
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// deadline 的 ISO 口径（工具层 isoDate，本地日期）。
  static String deadlineIso(DateTime d) => isoDate(d);

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: _controller,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              isDense: true,
              hintText: '记一笔，30 秒的事',
              prefixIcon: IconButton(
                icon: Icon(
                  _important ? Icons.star : Icons.star_border,
                  color: _important ? scheme.primary : null,
                ),
                tooltip: '标重要',
                onPressed: () => setState(() => _important = !_important),
              ),
              suffixIcon: _deadline == null
                  ? IconButton(
                      icon: const Icon(Icons.event_outlined),
                      tooltip: '截止',
                      onPressed: _pickDeadline,
                    )
                  : TextButton(
                      onPressed: () => setState(() => _deadline = null),
                      child: Text(deadlineIso(_deadline!)),
                    ),
              border: const OutlineInputBorder(),
            ),
          ),
        ),
        const SizedBox(width: 8),
        IconButton.filled(
          onPressed: _submit,
          icon: const Icon(Icons.add),
          tooltip: '收入清单',
        ),
      ],
    );
  }

  Future<void> _pickDeadline() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: now,
      firstDate: now,
      lastDate: now.add(const Duration(days: 365)),
    );
    if (picked != null) setState(() => _deadline = picked);
  }
}
