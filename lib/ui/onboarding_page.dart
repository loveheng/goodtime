import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../models/fixed_slot.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 首启引导 3 步（ui-spec §2）：①作息边界（必填不可跳）→②一周节奏（可跳）
/// →③排程偏好（可跳）。完成即写 settings+fixed_slots；未完成无法进主框架。
class OnboardingPage extends StatefulWidget {
  const OnboardingPage({super.key, required this.onDone});

  /// 完成回调（通知外层闸门重查 settings）。
  final VoidCallback onDone;

  @override
  State<OnboardingPage> createState() => _OnboardingPageState();
}

class _OnboardingPageState extends State<OnboardingPage> {
  int _step = 0;

  // ① 作息边界（预填常规值，必填=不可跳过本步）
  int _wake = 420; // 07:00
  int _sleep = 1380; // 23:00

  // ② 一周节奏
  String? _preset; // 上班族 / 学生 / 自由职业

  // ③ 排程偏好
  int _minBlock = 30;
  int _dailyLimit = 8;

  CommandHandler get _handler => CommandHandler(AppServices.repo);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('拾光', style: Theme.of(context).textTheme.headlineMedium),
              const SizedBox(height: 4),
              Text('第 ${_step + 1} 步，共 3 步', style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 24),
              Expanded(child: _stepBody()),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _next,
                child: Text(_step < 2 ? '下一步' : '开始使用'),
              ),
              if (_step > 0)
                TextButton(
                  onPressed: () => setState(() => _step += 1),
                  child: const Text('跳过'),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _stepBody() => switch (_step) {
        0 => _stepSchedule(),
        1 => _stepRhythm(),
        _ => _stepPreference(),
      };

  // ① 作息边界：「几点起床？几点睡觉？」+「一切安排以此为界」
  Widget _stepSchedule() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('几点起床？几点睡觉？', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const Text('一切安排以此为界', style: TextStyle(color: StColors.textSecondary)),
        const SizedBox(height: 24),
        _timeCard('起床', _wake, (m) => setState(() => _wake = m)),
        const SizedBox(height: 12),
        _timeCard('睡觉', _sleep, (m) => setState(() => _sleep = m)),
      ],
    );
  }

  Widget _timeCard(String label, int minutes, ValueChanged<int> onChanged) {
    return Card(
      child: ListTile(
        title: Text(label),
        trailing: Text(
          clockOf(minutes),
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
        ),
        onTap: () async {
          final picked = await showTimePicker(
            context: context,
            initialTime: TimeOfDay(hour: minutes ~/ 60, minute: minutes % 60),
          );
          if (picked != null) onChanged(picked.hour * 60 + picked.minute);
        },
      ),
    );
  }

  // ② 一周节奏：模板三选；自由职业=留空直接过
  Widget _stepRhythm() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('一周里哪些时间是有固定安排的？',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const SizedBox(height: 24),
        _presetCard('上班族', '工作日白天在班上'),
        _presetCard('学生', '工作日有课'),
        _presetCard('自由职业', '没有固定安排是完全正常的'),
      ],
    );
  }

  Widget _presetCard(String name, String note) {
    final selected = _preset == name;
    return Card(
      child: ListTile(
        leading: Icon(selected ? Icons.radio_button_checked : Icons.radio_button_off),
        title: Text(name),
        subtitle: Text(note),
        onTap: () => setState(() => _preset = name),
      ),
    );
  }

  // ③ 排程偏好：粒度 + 单日上限
  Widget _stepPreference() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('排程偏好', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const SizedBox(height: 24),
        const Text('最小块粒度'),
        SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 15, label: Text('15 分钟')),
            ButtonSegment(value: 30, label: Text('30 分钟')),
            ButtonSegment(value: 60, label: Text('60 分钟')),
          ],
          selected: {_minBlock},
          onSelectionChanged: (s) => setState(() => _minBlock = s.first),
        ),
        const SizedBox(height: 24),
        Text('单日新增排量上限：$_dailyLimit 项'),
        Slider(
          value: _dailyLimit.toDouble(),
          min: 3,
          max: 12,
          divisions: 9,
          label: '$_dailyLimit',
          onChanged: (v) => setState(() => _dailyLimit = v.round()),
        ),
        const Text('上限是休息权的模型支撑，不是效率指标',
            style: TextStyle(color: StColors.textSecondary)),
      ],
    );
  }

  Future<void> _next() async {
    if (_step == 0) {
      setState(() => _step = 1);
      return;
    }
    if (_step == 1 && _preset != null && _preset != '自由职业') {
      await _handler.execute(
        UpdateFixedSlotsCommand(slots: _presetSlots(_preset!)),
      );
    }
    if (_step == 2) {
      await _handler.execute(UpdateSettingsCommand(values: {
        'wake_time': _wake,
        'sleep_time': _sleep,
        'min_block_minutes': _minBlock,
        'daily_new_blocks_limit': _dailyLimit,
      }));
      widget.onDone();
      return;
    }
    setState(() => _step = 2);
  }

  /// 一周节奏预设（§9）：工作日白天两段固定占用；自由职业=空表合法。
  List<FixedSlot> _presetSlots(String preset) {
    if (preset == '上班族') {
      return [
        FixedSlot(name: '上班', weekdays: const [1, 2, 3, 4, 5], startMin: 9 * 60, endMin: 12 * 60),
        FixedSlot(name: '上班（下午）', weekdays: const [1, 2, 3, 4, 5], startMin: 14 * 60, endMin: 18 * 60),
      ];
    }
    if (preset == '学生') {
      return [
        FixedSlot(name: '上课', weekdays: const [1, 2, 3, 4, 5], startMin: 8 * 60, endMin: 12 * 60),
        FixedSlot(name: '上课（下午）', weekdays: const [1, 2, 3, 4, 5], startMin: 14 * 60, endMin: 17 * 60),
      ];
    }
    return const [];
  }
}
