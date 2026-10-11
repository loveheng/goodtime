import 'package:flutter/material.dart';

import '../../collab/fsm.dart' show BundleFsmStatus, CollabBundleState, CollabProposalType,
    CollabStepState, MemberBallot, MemberVoteState, StepFsmStatus;
import '../../collab/materialize.dart' show materializeBundle;
import '../../collab/outbox.dart';
import '../../collab/replay.dart';
import '../../data/collab_repository.dart';
import '../../theme/tokens.dart';

/// 决策矩阵（§11.2 C 页）：发起人聚合决策面板。
///
/// 数据源 = FSM 回放派生态 [CollabBundleState]（[replayBundle] 解密落库事件得到），
/// 纯渲染落在 [DecisionMatrixView]（公开、可单测，不依赖 DB/异步）。展示每步 M-of-N
/// 门限进度条、逐成员表决徽标、待确认成员旁离线催办铃铛（Nudge）、STALLED 步骤的
/// 发起人闭环三键（转派/降级/裁撤），顶部备忘置顶（Briefing Sticky）。
///
/// 三键/Nudge/Cancel/Reopen 仅发起人有操作权，经离线发件箱落库、联网由常驻调度弹出。
class DecisionMatrixPage extends StatefulWidget {
  final CollabBundleRepository collabRepo;
  final OutboxRepository outboxRepo;
  final String bundleId;
  final String initiatorId;
  final String? currentUserId;
  final Future<List<int>?> Function(String bundleId, int keyVer)? bundleKey;
  final List<CollabStep> steps;

  const DecisionMatrixPage({
    super.key,
    required this.collabRepo,
    required this.outboxRepo,
    required this.bundleId,
    required this.initiatorId,
    this.currentUserId,
    this.bundleKey,
    this.steps = const [],
  });

  @override
  State<DecisionMatrixPage> createState() => _DecisionMatrixPageState();
}

class _DecisionMatrixPageState extends State<DecisionMatrixPage> {
  CollabBundleState? _state;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final events = await widget.collabRepo.loadEvents(widget.bundleId);
    CollabBundleState state;
    if (widget.bundleKey != null) {
      state = await replayBundle(events, widget.bundleKey!,
          bundleId: widget.bundleId);
      // 顺手把派生态回填入 collab_steps——B 页时间槽 / A 页画板自此读物化表，
      // 不必再持有密钥（解开 member_calendar_page 顶部「时间槽未物化」钉子）。
      await materializeBundle(widget.collabRepo, widget.bundleId,
          bundleKey: widget.bundleKey!);
    } else {
      // 运行期密钥环未接入时回退到空态（与 B 页时间槽同家族施工钉子）：仅展示
      // 头部与三键入口，逐成员表决需解密回放——留作密钥环检索接入后的显示。
      state = CollabBundleState.empty(widget.bundleId);
    }
    if (!mounted) return;
    setState(() {
      _state = state;
    });
  }

  bool get _isInitiator =>
      widget.currentUserId != null && widget.currentUserId == widget.initiatorId;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('决策')),
      body: _state == null
          ? const Center(child: Text('加载中…'))
          : DecisionMatrixView(
              state: _state!,
              currentUserId: widget.currentUserId,
              isInitiator: _isInitiator,
              steps: widget.steps,
              actions: _isInitiator
                  ? DecisionMatrixActions(
                      onNudge: _nudge,
                      onReassign: _reassign,
                      onBypass: _bypass,
                      onPrune: _prune,
                      onCancel: _cancel,
                      onReopen: _reopen,
                      onUpdateBriefing: _updateBriefing,
                    )
                  : null,
            ),
    );
  }

  Future<void> _nudge(String stepId, String uid) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'NUDGE',
      payload: <String, Object?>{'step_id': stepId, 'nudge_uids': [uid]},
    );
    if (mounted) _toast('已催办，联网后自动发送');
  }

  Future<void> _reassign(String stepId, String targetUid) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'REASSIGN',
      payload: <String, Object?>{'step_id': stepId, 'reassign_to': [targetUid]},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  Future<void> _bypass(String stepId, String uid) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'BYPASS',
      payload: <String, Object?>{'step_id': stepId, 'bypass_uids': [uid]},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  Future<void> _prune(String stepId) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'PRUNE',
      payload: <String, Object?>{'step_id': stepId},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  Future<void> _cancel() async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'CANCEL',
      payload: const <String, Object?>{},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  Future<void> _reopen() async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'REOPEN',
      payload: const <String, Object?>{},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  Future<void> _updateBriefing(String text) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'BRIEFING_UPDATE',
      payload: <String, Object?>{'briefing': text},
    );
    if (mounted) _toast('已记下，联网后自动发送');
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
}

/// 决策矩阵纯渲染层（不依赖 DB，可单测）。
class DecisionMatrixView extends StatelessWidget {
  final CollabBundleState state;
  final String? currentUserId;
  final bool isInitiator;
  final List<CollabStep> steps;
  final DecisionMatrixActions? actions;

  const DecisionMatrixView({
    super.key,
    required this.state,
    this.currentUserId,
    this.isInitiator = false,
    this.steps = const [],
    this.actions,
  });

  @override
  Widget build(BuildContext context) {
    final sorted = state.steps.values.toList()
      ..sort((a, b) => a.stepId.compareTo(b.stepId));
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _Header(state: state, isInitiator: isInitiator),
        const SizedBox(height: 12),
        BriefingSticky(isInitiator: isInitiator, onEdit: actions?.onUpdateBriefing),
        const SizedBox(height: 12),
        if (sorted.isEmpty)
          _EmptyMatrix()
        else
          for (final step in sorted)
            _StepMatrixTile(
              step: step,
              currentUserId: currentUserId,
              isInitiator: isInitiator,
              actions: actions,
            ),
        if (isInitiator) ..._footerActions(),
      ],
    );
  }

  List<Widget> _footerActions() {
    final children = <Widget>[const SizedBox(height: 12)];
    if (state.status == BundleFsmStatus.confirmed) {
      children.add(FilledButton.tonal(
        onPressed: actions?.onReopen,
        child: const Text('重新打开方案'),
      ));
    } else if (state.status != BundleFsmStatus.cancelled &&
        state.status != BundleFsmStatus.expired) {
      children.add(OutlinedButton(
        onPressed: actions?.onCancel,
        child: const Text('取消方案'),
      ));
    }
    return children;
  }
}

/// 决策矩阵动作集合（发起人权限；非发起人传 null 禁用全部操作）。
class DecisionMatrixActions {
  final void Function(String stepId, String uid)? onNudge;
  final void Function(String stepId, String targetUid)? onReassign;
  final void Function(String stepId, String uid)? onBypass;
  final void Function(String stepId)? onPrune;
  final Future<void> Function()? onCancel;
  final Future<void> Function()? onReopen;
  final Future<void> Function(String)? onUpdateBriefing;

  const DecisionMatrixActions({
    this.onNudge,
    this.onReassign,
    this.onBypass,
    this.onPrune,
    this.onCancel,
    this.onReopen,
    this.onUpdateBriefing,
  });
}

class _Header extends StatelessWidget {
  final CollabBundleState state;
  final bool isInitiator;
  const _Header({required this.state, required this.isInitiator});

  @override
  Widget build(BuildContext context) {
    final type = state.proposalType == CollabProposalType.consensus
        ? '生活预约'
        : '团队调度';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Chip(label: Text(type), visualDensity: VisualDensity.compact,
                backgroundColor: StColors.sparkBg),
            const SizedBox(width: 8),
            Chip(
              label: Text(_bundleStatus(state.status)),
              visualDensity: VisualDensity.compact,
              backgroundColor: Colors.grey.shade100,
            ),
            const Spacer(),
            Text('${state.participants.length} 人协同',
                style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
          ],
        ),
      ),
    );
  }
}

/// 顶部备忘置顶（Briefing Sticky）：发起人可编辑经 BRIEFING_UPDATE 落库。
class BriefingSticky extends StatelessWidget {
  final bool isInitiator;
  final Future<void> Function(String)? onEdit;
  const BriefingSticky({super.key, required this.isInitiator, this.onEdit});

  @override
  Widget build(BuildContext context) {
    return Card(
      color: StColors.sparkBg,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            const Icon(Icons.sticky_note_2_outlined, size: 18),
            const SizedBox(width: 8),
            const Expanded(
              child: Text('备忘：发起人可置顶一段对全员的说明',
                  style: TextStyle(fontSize: 13)),
            ),
            if (isInitiator && onEdit != null)
              TextButton(
                onPressed: () async {
                  final c = TextEditingController();
                  final ok = await showDialog<bool>(
                    context: context,
                    builder: (_) => AlertDialog(
                      title: const Text('编辑备忘'),
                      content: TextField(
                        controller: c,
                        maxLines: 2,
                        decoration: const InputDecoration(
                            hintText: '对全员的说明（选填）'),
                      ),
                      actions: [
                        TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('取消')),
                        FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('保存')),
                      ],
                    ),
                  );
                  if (ok == true) {
                    final edit = onEdit;
                    if (edit != null) await edit(c.text.trim());
                  }
                },
                child: const Text('编辑'),
              ),
          ],
        ),
      ),
    );
  }
}

class _EmptyMatrix extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Column(
        children: [
          Icon(Icons.visibility_off_outlined,
              size: 48, color: StColors.textSecondary),
          const SizedBox(height: 12),
          Text('暂无步骤数据',
              style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: StColors.textPrimary)),
          const SizedBox(height: 6),
          Text('该包密钥尚未取到，联网补齐后即可解密回放',
              textAlign: TextAlign.center,
              style: TextStyle(color: StColors.textSecondary)),
        ],
      ),
    );
  }
}

/// 单步决策矩阵瓦片：M-of-N 进度条 + 逐成员表决徽标 + Nudge 铃铛 + 闭环三键。
class _StepMatrixTile extends StatelessWidget {
  final CollabStepState step;
  final String? currentUserId;
  final bool isInitiator;
  final DecisionMatrixActions? actions;

  const _StepMatrixTile({
    required this.step,
    this.currentUserId,
    required this.isInitiator,
    this.actions,
  });

  @override
  Widget build(BuildContext context) {
    final need = step.threshold <= 0 ? step.requiredUids.length : step.threshold;
    final have = step.acceptCount;
    final ratio = need == 0 ? 0.0 : (have / need).clamp(0.0, 1.0);
    final ready = step.status == StepFsmStatus.ready;
    final clash = step.status == StepFsmStatus.timeClash ||
        step.status == StepFsmStatus.roleClash;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text('步骤 ${step.stepId}',
                      style: const TextStyle(
                          fontSize: 15, fontWeight: FontWeight.w600)),
                ),
                _tag(_stepStatus(step.status),
                    ready ? StColors.sparkStroke : StColors.textSecondary),
              ],
            ),
            const SizedBox(height: 10),
            LinearProgressIndicator(
              value: ratio,
              backgroundColor: Colors.grey.shade200,
              color: ready ? StColors.sparkStroke : StColors.textSecondary,
              minHeight: 8,
            ),
            const SizedBox(height: 4),
            Text('$have/$need 人同意',
                style: TextStyle(
                    fontSize: 12,
                    color: ready ? StColors.sparkStroke : StColors.textSecondary)),
            const SizedBox(height: 10),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: step.requiredUids
                  .map((uid) => _MemberBadge(
                        uid: uid,
                        ballot: step.ballots[uid],
                        isYou: uid == currentUserId,
                        canNudge: isInitiator &&
                            (step.ballots[uid]?.state == MemberVoteState.pending) &&
                            !ready,
                        onNudge: actions?.onNudge != null
                            ? () => actions!.onNudge!.call(step.stepId, uid)
                            : null,
                      ))
                  .toList(),
            ),
            // 仅冲突（时间/权责）步骤挂闭环三键——日常 pending 不该打扰发起人
            if (isInitiator && clash)
              _ClosureThreeKeys(
                step: step,
                currentUserId: currentUserId,
                actions: actions,
              ),
          ],
        ),
      ),
    );
  }
}

/// 逐成员表决徽标（accepted=绿 / pending=灰 / rejected=红 / pendingRecheck=琥珀）。
class _MemberBadge extends StatelessWidget {
  final String uid;
  final MemberBallot? ballot;
  final bool isYou;
  final bool canNudge;
  final void Function()? onNudge;

  const _MemberBadge({
    required this.uid,
    this.ballot,
    this.isYou = false,
    this.canNudge = false,
    this.onNudge,
  });

  @override
  Widget build(BuildContext context) {
    final state = ballot?.state;
    final color = switch (state) {
      MemberVoteState.accepted => Colors.green,
      MemberVoteState.rejected => Colors.red,
      MemberVoteState.pendingRecheck => Colors.amber,
      _ => Colors.grey,
    };
    final label = isYou ? '你' : uid;
    return Chip(
      avatar: Icon(Icons.circle, size: 10, color: color),
      label: Text(label, style: const TextStyle(fontSize: 12)),
      visualDensity: VisualDensity.compact,
      backgroundColor: Colors.grey.shade100,
      deleteIcon: canNudge ? const Icon(Icons.notifications_active, size: 14) : null,
      onDeleted: canNudge ? onNudge : null,
    );
  }
}

/// STALLED 步骤的发起人闭环三键（转派 / 降级 / 裁撤）。
class _ClosureThreeKeys extends StatelessWidget {
  final CollabStepState step;
  final String? currentUserId;
  final DecisionMatrixActions? actions;

  const _ClosureThreeKeys({
    required this.step,
    this.currentUserId,
    this.actions,
  });

  @override
  Widget build(BuildContext context) {
    final pending = step.requiredUids
        .where((u) => step.ballots[u]?.state == MemberVoteState.pending)
        .toList();
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Wrap(
        spacing: 8,
        children: [
          OutlinedButton.icon(
            icon: const Icon(Icons.swap_horiz, size: 16),
            label: const Text('转派'),
            onPressed: actions?.onReassign != null
                ? () => _pickAndRun(
                      context,
                      title: '转派给',
                      candidates: step.requiredUids,
                      onPick: (u) => actions!.onReassign!(step.stepId, u),
                    )
                : null,
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.skip_next, size: 16),
            label: const Text('降级'),
            onPressed: actions?.onBypass != null && pending.isNotEmpty
                ? () => _pickAndRun(
                      context,
                      title: '降级成员',
                      candidates: pending,
                      onPick: (u) => actions!.onBypass!(step.stepId, u),
                    )
                : null,
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.cut, size: 16),
            label: const Text('裁撤'),
            onPressed: actions?.onPrune != null
                ? () => actions!.onPrune!(step.stepId)
                : null,
          ),
        ],
      ),
    );
  }

  Future<void> _pickAndRun(
    BuildContext context, {
    required String title,
    required List<String> candidates,
    required void Function(String) onPick,
  }) async {
    if (candidates.isEmpty) return;
    final picked = await showDialog<String>(
      context: context,
      builder: (_) => SimpleDialog(
        title: Text(title),
        children: candidates
            .map((u) => SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, u),
                  child: Text(u == currentUserId ? '你 ($u)' : u),
                ))
            .toList(),
      ),
    );
    if (picked != null) onPick(picked);
  }
}

Widget _tag(String text, Color color) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(text,
          style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w600)),
    );

String _stepStatus(StepFsmStatus s) => switch (s) {
      StepFsmStatus.ready => '已达标',
      StepFsmStatus.timeClash => '时间冲突',
      StepFsmStatus.roleClash => '权责异议',
      StepFsmStatus.optional => '可选',
      StepFsmStatus.pruned => '已裁撤',
      _ => '待确认',
    };

String _bundleStatus(BundleFsmStatus s) => switch (s) {
      BundleFsmStatus.proposed => '协商中',
      BundleFsmStatus.confirmed => '已确认',
      BundleFsmStatus.stalled => '待闭环',
      BundleFsmStatus.expired => '已流局',
      BundleFsmStatus.cancelled => '已取消',
      BundleFsmStatus.archived => '已归档',
      BundleFsmStatus.abandonedLocally => '本地已释放',
      _ => '草稿',
    };
