import 'package:flutter/material.dart';

import '../../data/collab_repository.dart';
import '../../theme/tokens.dart';

/// 双维拒绝面板（§11.2 D 页）的结构化结果。
///
/// 四步：主维度分流（时间冲突 / 权责异议）→ 子类芯片（四选一）→ 隐私分级
/// （全员可见 / 仅发起人可见）→ 可选建议转派 @成员。理由默认全员可见；私密理由
/// 经发起人 IK_A 单向加密（§3.5 隐私分级），非发起人端时间轴只渲染骨架提示。
class RejectResult {
  /// 主维度（对齐 FSM `RejectReason` 枚举名：TIME_CONFLICT / ROLE_UNWILLING）。
  final String reason;
  /// 子类芯片（四选一）：NOT_MY_JOB / PRIORITY / MISSING_PREREQ / PERSONAL。
  final String subReason;
  /// 详细说明（选填，发起人会参考）。
  final String? detail;
  /// 隐私分级：PUBLIC（默认）/ INITIATOR_ONLY。
  final String privacy;
  /// 可选建议转派的目标成员 uid（仅建议，由发起人后续处置）。
  final String? suggestReassign;

  const RejectResult({
    required this.reason,
    required this.subReason,
    this.detail,
    this.privacy = 'PUBLIC',
    this.suggestReassign,
  });
}

/// 拒绝主维度（§4.3 双维 REJECT）。
enum _RejectDimension { time, role }

/// 子类芯片（四选一，§11.2 D 页）。
const Map<String, String> _kSubReasons = {
  'NOT_MY_JOB': '非我职责',
  'PRIORITY': '优先级不符',
  'MISSING_PREREQ': '缺前置条件',
  'PERSONAL': '个人原因',
};

/// 双维拒绝面板（§11.2 D 页）：四步结构化反馈，破除「有空但不想做被迫撒谎说没空」。
///
/// 纯渲染层（不依赖 DB/异步），结构化结果经 [onConfirm] 回调；调用方负责落库与反馈。
class RejectPanel extends StatefulWidget {
  final CollabBundleHeader bundle;
  final CollabStep step;
  final String? currentUserId;
  final Future<void> Function(RejectResult result) onConfirm;

  const RejectPanel({
    super.key,
    required this.bundle,
    required this.step,
    this.currentUserId,
    required this.onConfirm,
  });

  @override
  State<RejectPanel> createState() => _RejectPanelState();
}

class _RejectPanelState extends State<RejectPanel> {
  int _step = 0; // 0 主维度 / 1 子类 / 2 隐私 / 3 转派
  _RejectDimension? _dimension;
  String? _subReason;
  String _privacy = 'PUBLIC';
  String? _suggestReassign;
  final _detailCtrl = TextEditingController();
  bool _submitting = false;

  @override
  void dispose() {
    _detailCtrl.dispose();
    super.dispose();
  }

  List<String> get _reassignCandidates => widget.bundle.participants
      .where((u) => u != widget.currentUserId)
      .toList();

  Future<void> _submit() async {
    if (_dimension == null || _subReason == null) return;
    setState(() => _submitting = true);
    final result = RejectResult(
      reason: _dimension == _RejectDimension.time
          ? 'TIME_CONFLICT'
          : 'ROLE_UNWILLING',
      subReason: _subReason!,
      detail: _detailCtrl.text.trim().isEmpty ? null : _detailCtrl.text.trim(),
      privacy: _privacy,
      suggestReassign: _suggestReassign,
    );
    await widget.onConfirm(result);
    if (mounted) setState(() => _submitting = false);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 16,
        bottom: MediaQuery.of(context).viewInsets.bottom + 20,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _header(),
          const SizedBox(height: 14),
          Flexible(
            child: SingleChildScrollView(child: _body()),
          ),
          const SizedBox(height: 14),
          _footer(),
        ],
      ),
    );
  }

  Widget _header() => Row(
        children: [
          Text('提出异议',
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
          const SizedBox(width: 8),
          Chip(
            label: Text('${_step + 1} / 4'),
            visualDensity: VisualDensity.compact,
            backgroundColor: StColors.sparkBg,
          ),
          const Spacer(),
          Text('步骤 ${widget.step.id}',
              style: TextStyle(color: StColors.textSecondary)),
        ],
      );

  Widget _body() {
    switch (_step) {
      case 0:
        return _dimensionStep();
      case 1:
        return _subReasonStep();
      case 2:
        return _privacyStep();
      default:
        return _reassignStep();
    }
  }

  /// 步骤 0：主维度分流。
  Widget _dimensionStep() => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('主要卡在哪一类？',
              style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
          const SizedBox(height: 10),
          _bigOption(
            icon: Icons.schedule_outlined,
            title: '时间冲突',
            subtitle: '这个时段我已有安排',
            selected: _dimension == _RejectDimension.time,
            onTap: () => setState(() {
              _dimension = _RejectDimension.time;
              _step = 1;
            }),
          ),
          const SizedBox(height: 10),
          _bigOption(
            icon: Icons.block_outlined,
            title: '权责异议',
            subtitle: '这件事不该由我来做',
            selected: _dimension == _RejectDimension.role,
            onTap: () => setState(() {
              _dimension = _RejectDimension.role;
              _step = 1;
            }),
          ),
        ],
      );

  /// 步骤 1：子类芯片（四选一）+ 选填详细说明。
  Widget _subReasonStep() => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('更具体的原因（四选一）',
              style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _kSubReasons.entries
                .map((e) => ChoiceChip(
                      label: Text(e.value),
                      selected: _subReason == e.key,
                      onSelected: (_) => setState(() => _subReason = e.key),
                    ))
                .toList(),
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _detailCtrl,
            maxLines: 2,
            decoration: const InputDecoration(
              hintText: '补充说明（选填，发起人会参考）',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      );

  /// 步骤 2：隐私分级开关。
  Widget _privacyStep() => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('谁可以看到你的理由？',
              style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
          const SizedBox(height: 10),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(
                  value: 'PUBLIC', label: Text('全员可见')),
              ButtonSegment(
                  value: 'INITIATOR_ONLY', label: Text('仅发起人可见')),
            ],
            selected: {_privacy},
            onSelectionChanged: (s) => setState(() => _privacy = s.first),
          ),
          const SizedBox(height: 10),
          Text(
            _privacy == 'INITIATOR_ONLY'
                ? '理由经发起人公钥单向加密，其他成员端只看到骨架提示，永远解不开。'
                : '理由用协同包密钥加密，所有参与成员的时间轴都能看到。',
            style: TextStyle(color: StColors.textSecondary, fontSize: 12),
          ),
        ],
      );

  /// 步骤 3：可选建议转派 @成员。
  Widget _reassignStep() {
    final candidates = _reassignCandidates;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('建议转给谁？（选填）',
            style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
        const SizedBox(height: 10),
        if (candidates.isEmpty)
          Text('没有可转派的成员',
              style: TextStyle(color: StColors.textSecondary))
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: candidates
                .map((u) => ChoiceChip(
                      label: Text(u),
                      selected: _suggestReassign == u,
                      onSelected: (_) => setState(
                        () => _suggestReassign =
                            _suggestReassign == u ? null : u,
                      ),
                    ))
                .toList(),
          ),
        const SizedBox(height: 10),
        Text('这只是一个建议，由发起人决定是否转派。',
            style: TextStyle(color: StColors.textSecondary, fontSize: 12)),
      ],
    );
  }

  Widget _bigOption({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool selected,
    required VoidCallback onTap,
  }) =>
      InkWell(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          decoration: BoxDecoration(
            border: Border.all(
              color: selected ? StColors.sparkStroke : Colors.grey.shade300,
              width: selected ? 1.5 : 1,
            ),
            borderRadius: BorderRadius.circular(12),
            color: selected ? StColors.sparkBg : null,
          ),
          child: Row(
            children: [
              Icon(icon, color: StColors.sparkStroke),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(fontSize: 15)),
                    Text(subtitle,
                        style: TextStyle(
                            color: StColors.textSecondary, fontSize: 12)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );

  Widget _footer() => Row(
        children: [
          if (_step > 0)
            TextButton(
              onPressed: () => setState(() => _step--),
              child: const Text('上一步'),
            ),
          const Spacer(),
          if (_step < 3)
            FilledButton(
              onPressed: _canAdvance() ? () => setState(() => _step++) : null,
              child: const Text('下一步'),
            )
          else
            FilledButton(
              onPressed: _submitting ? null : _submit,
              child: _submitting
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('提交异议'),
            ),
        ],
      );

  bool _canAdvance() {
    if (_step == 0) return _dimension != null;
    if (_step == 1) return _subReason != null;
    return true; // 步骤 2（隐私）与步骤 3（转派可选）恒可前进
  }
}
