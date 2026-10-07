import 'package:flutter/material.dart';

import '../action/command_handler.dart';
import '../action/commands.dart';
import '../app_services.dart';
import '../data/settings.dart';
import '../models/fixed_slot.dart';
import '../theme/tokens.dart';
import '../util/schedule_day.dart';

/// 首启引导 4 步（ui-spec §2）：①作息边界（必填不可跳）→②一周节奏（可跳）
/// →③排程数值偏好（可跳）→④关于你（身份画像+我的偏好，可跳但推荐）。
/// 完成即写 settings+fixed_slots；未完成无法进主框架。
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

  // ④ 关于你（可跳但推荐；human 通道写入，不受 uiOnly 限制）
  String _userRules = '';
  String _identityPrompt = '';

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
              Text('第 ${_step + 1} 步，共 4 步', style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 24),
              Expanded(child: _stepBody()),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: _next,
                child: Text(_step < 3 ? '下一步' : '开始使用'),
              ),
              if (_step > 0 && _step < 3)
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
        2 => _stepPreference(),
        _ => _stepProfile(),
      };

  // ① 作息边界：「几点起床？几点睡觉？」+「一切安排以此为界」
  Widget _stepSchedule() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('几点起床？几点睡觉？', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        Text('一切安排以此为界', style: TextStyle(color: StColors.textSecondary)),
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
        Text('上限是休息权的模型支撑，不是效率指标',
            style: TextStyle(color: StColors.textSecondary)),
      ],
    );
  }

  // ④ 关于你：身份画像 + 我的偏好（可跳但强烈推荐）。
  // 这两项注入 AI 排程上下文，是排程贴合度的地基；human 通道写入，不受 uiOnly 限制。
  // 内容包可滚动：小屏/键盘弹出挤压竖向空间时不溢出（测试视口 800×600 即溢出）。
  Widget _stepProfile() {
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('关于你', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Text('这两项是 AI 排程的地基，建议填一下；可稍后在「设置」页随时修改。',
              style: TextStyle(color: StColors.textSecondary)),
          const SizedBox(height: 24),
          const Text('身份画像（系统提示词）', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 6),
          _multiLineField(
            hint: '例如：自由职业者，家有两岁宝宝，上午精力最好、晚上 9 点后不处理工作；'
                '硬约束是周三下午要陪诊。',
            onChanged: (v) => setState(() => _identityPrompt = v),
          ),
          const SizedBox(height: 20),
          const Text('我的偏好（自然语言）', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 6),
          _multiLineField(
            hint: '例如：周五晚上不排深度工作；午饭后留 30 分钟散步；'
                'deadline 前两天不排新事务。',
            onChanged: (v) => setState(() => _userRules = v),
          ),
        ],
      ),
    );
  }

  Widget _multiLineField({
    required String hint,
    required ValueChanged<String> onChanged,
  }) {
    return TextField(
      maxLines: 4,
      decoration: InputDecoration(
        hintText: hint,
        border: const OutlineInputBorder(),
        alignLabelWithHint: true,
      ),
      onChanged: onChanged,
    );
  }

  Future<void> _next() async {
    if (_step == 0) {
      setState(() => _step = 1);
      return;
    }
    if (_step == 1) {
      if (_preset != null && _preset != '自由职业') {
        await _handler.execute(
          UpdateFixedSlotsCommand(slots: _presetSlots(_preset!)),
        );
      }
      setState(() => _step = 2);
      return;
    }
    if (_step == 2) {
      setState(() => _step = 3);
      return;
    }
    // _step == 3：关于你 —— 提交全部设置并进入主框架。
    // 身份画像/我的偏好为空则不写（可跳）；非空走 human 通道，不受 uiOnly 限制。
    final values = <String, Object?>{
      SettingsKeys.wakeTime: _wake,
      SettingsKeys.sleepTime: _sleep,
      SettingsKeys.minBlockMinutes: _minBlock,
      SettingsKeys.dailyNewBlocksLimit: _dailyLimit,
    };
    final rules = _userRules.trim();
    if (rules.isNotEmpty) values[SettingsKeys.userRules] = rules;
    final identity = _identityPrompt.trim();
    if (identity.isNotEmpty) values[SettingsKeys.identityPrompt] = identity;
    await _handler.execute(UpdateSettingsCommand(values: values));
    widget.onDone();
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
