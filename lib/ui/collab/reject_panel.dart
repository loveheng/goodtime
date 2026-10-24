import 'package:flutter/material.dart';

import '../../data/collab_repository.dart';
import '../../theme/tokens.dart';

/// 双维拒绝面板（§11.2 D 页）的结构化结果。
///
/// 默认态一屏完事：主维度 → 子类芯片 → 可选备注 → 一键拒绝；隐私分级与建议
/// 转派收进【详细说明】展开态（§3.4 减层版，2026-10-25 拍板——必填正是制造
/// 敷衍的根源）。理由默认全员可见；私密理由经发起人 IK_A 单向加密（§3.5 隐私
/// 分级），非发起人端时间轴只渲染骨架提示。
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

/// 双维拒绝面板（§11.2 D 页 §3.4 减层版）：默认一屏（维度+子类+可选备注即可
/// 提交），隐私分级与建议转派收进展开态。
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
  _RejectDimension? _dimension;
  String? _subReason;
  String _privacy = 'PUBLIC';
  String? _suggestReassign;
  bool _showAdvanced = false;
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

  bool get _canSubmit => _dimension != null && _subReason != null;

  Future<void> _submit() async {
    if (!_canSubmit) return;
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
          Text('步骤 ${widget.step.id}',
              style: TextStyle(color: StColors.textSecondary)),
        ],
      );

  Widget _body() => Column(
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
            onTap: () => setState(() => _dimension = _RejectDimension.time),
          ),
          const SizedBox(height: 10),
          _bigOption(
            icon: Icons.block_outlined,
            title: '权责异议',
            subtitle: '这件事不该由我来做',
            selected: _dimension == _RejectDimension.role,
            onTap: () => setState(() => _dimension = _RejectDimension.role),
          ),
          const SizedBox(height: 14),
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
          const SizedBox(height: 8),
          _advancedToggle(),
          if (_showAdvanced) ..._advancedSection,
        ],
      );

  /// 展开态入口：隐私分级 + 建议转派收进来，默认折叠（§3.4 减层）。
  Widget _advancedToggle() => InkWell(
        onTap: () => setState(() => _showAdvanced = !_showAdvanced),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              Icon(
                _showAdvanced ? Icons.expand_less : Icons.expand_more,
                size: 18,
                color: StColors.textSecondary,
              ),
              const SizedBox(width: 6),
              Text(
                '详细说明与隐私（选填）',
                style: TextStyle(color: StColors.textSecondary, fontSize: 13),
              ),
            ],
          ),
        ),
      );

  List<Widget> get _advancedSection => [
        Text('谁可以看到你的理由？',
            style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
        const SizedBox(height: 10),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'PUBLIC', label: Text('全员可见')),
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
        const SizedBox(height: 14),
        _reassignChips(),
        const SizedBox(height: 6),
      ];

  Widget _reassignChips() {
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
          const Spacer(),
          FilledButton(
            onPressed: _canSubmit && !_submitting ? _submit : null,
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
}
