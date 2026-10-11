import 'package:flutter/material.dart';

import '../../collab/outbox.dart';
import '../../data/collab_repository.dart';
import '../../theme/tokens.dart';

/// 审计时间轴（§11.2 F 页）：无输入框的只读垂直时间树；事件气泡结构化
/// （含「理由私密发送给发起人」骨架提示）；步骤便签抽屉（≤50 字覆盖式更新）。
///
/// **半盲投影（§4.2 铁律 1）**：本页只渲染 `collab_events` 明文骨架（action_type /
/// sender_id / server_at_ms / topology_ref），密文理由 / 便签不在此解密——私密内容
/// 在非发起人端只呈现骨架提示，端侧解密呈现为后续切片（施工钉子，与 B 页时间槽同口径）。
class AuditTimelinePage extends StatefulWidget {
  final CollabBundleRepository collabRepo;
  final OutboxRepository outboxRepo;
  final String bundleId;
  final String initiatorId;
  final String? currentUserId;
  final List<CollabStep> steps;

  const AuditTimelinePage({
    super.key,
    required this.collabRepo,
    required this.outboxRepo,
    required this.bundleId,
    required this.initiatorId,
    this.currentUserId,
    this.steps = const [],
  });

  @override
  State<AuditTimelinePage> createState() => _AuditTimelinePageState();
}

class _AuditTimelinePageState extends State<AuditTimelinePage> {
  List<StoredCollabEvent> _events = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final events = await widget.collabRepo.loadEvents(widget.bundleId);
    if (!mounted) return;
    setState(() {
      _events = events;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('时间轴'),
        actions: [
          IconButton(
            icon: const Icon(Icons.note_add_outlined),
            tooltip: '步骤便签',
            onPressed: _openNoteSheet,
          ),
        ],
      ),
      body: AuditTimelineView(
        loading: _loading,
        events: _events,
        initiatorId: widget.initiatorId,
        currentUserId: widget.currentUserId,
      ),
    );
  }

  void _openNoteSheet() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => StepNoteSheet(
        steps: widget.steps,
        onConfirm: _saveNote,
      ),
    );
  }

  Future<void> _saveNote(String stepId, String note) async {
    await widget.outboxRepo.enqueue(
      bundleId: widget.bundleId,
      actionType: 'STEP_NOTE',
      payload: <String, Object?>{
        'step_id': stepId,
        'note': note,
      },
    );
    if (mounted) {
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已记下，联网后自动发送')),
      );
    }
  }
}

/// 审计时间轴纯渲染层（不依赖 DB，可单测）：加载态 / 空态 / 结构化事件气泡树。
class AuditTimelineView extends StatelessWidget {
  final bool loading;
  final List<StoredCollabEvent> events;
  final String initiatorId;
  final String? currentUserId;

  const AuditTimelineView({
    super.key,
    required this.loading,
    required this.events,
    required this.initiatorId,
    this.currentUserId,
  });

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: Text('加载中…'));
    }
    if (events.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.timeline_outlined, size: 56, color: StColors.textSecondary),
              const SizedBox(height: 16),
              Text('还没有动态',
                  style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.w600,
                      color: StColors.textPrimary)),
              const SizedBox(height: 8),
              Text('方案从发起、响应到终态的全过程都会留在这里。',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: StColors.textSecondary)),
            ],
          ),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: events.length,
      separatorBuilder: (_, _) => const SizedBox(height: 12),
      itemBuilder: (_, i) => _EventBubble(
        event: events[i],
        isLast: i == events.length - 1,
        initiatorId: initiatorId,
        currentUserId: currentUserId,
      ),
    );
  }
}

/// 单事件气泡（结构化：动作 + 执行者 + 时间 + 拓扑引用；异议附私密骨架提示）。
class _EventBubble extends StatelessWidget {
  final StoredCollabEvent event;
  final bool isLast;
  final String initiatorId;
  final String? currentUserId;

  const _EventBubble({
    required this.event,
    required this.isLast,
    required this.initiatorId,
    required this.currentUserId,
  });

  @override
  Widget build(BuildContext context) {
    final meta = _describe(event.actionType);
    final who = event.senderId == currentUserId ? '你' : event.senderId;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Column(
            children: [
              Icon(meta.icon, size: 18, color: StColors.sparkStroke),
              if (!isLast)
                Expanded(
                  child: Container(width: 2, color: Colors.grey.shade300),
                ),
            ],
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: StColors.sparkBg,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('$who ${meta.label}',
                      style: const TextStyle(fontSize: 14)),
                  const SizedBox(height: 4),
                  Text(_formatTime(event.serverAtMs),
                      style: TextStyle(
                          color: StColors.textSecondary, fontSize: 12)),
                  if (_privacyHint != null) ...[
                    const SizedBox(height: 6),
                    Text(_privacyHint!,
                        style: TextStyle(
                            color: StColors.textSecondary, fontSize: 12)),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 非执行者视角对异议的半盲骨架提示（§4.2 铁律 1：私密理由不解密只提示）。
  String? get _privacyHint {
    if (event.actionType != 'REJECT') return null;
    if (event.senderId == currentUserId) return null; // 本人知道自己的理由
    return '已私密反馈发起人，等待调整';
  }
}

/// 动作 →（标签 / 图标）映射（协同设计 §3.5 伪聊天系统事件流）。
({String label, IconData icon}) _describe(String action) {
  switch (action) {
    case 'PROPOSE':
      return (label: '发起了方案', icon: Icons.rocket_launch_outlined);
    case 'ACCEPT':
      return (label: '同意了这一步', icon: Icons.check_circle_outline);
    case 'REJECT':
      return (label: '提出了异议', icon: Icons.report_problem_outlined);
    case 'PATCH_TIME':
      return (label: '调整了时间', icon: Icons.schedule_outlined);
    case 'REASSIGN':
      return (label: '转派了这一步', icon: Icons.swap_horiz_outlined);
    case 'MAKE_OPTIONAL':
      return (label: '将这一步设为可选', icon: Icons.remove_circle_outline);
    case 'BYPASS':
      return (label: '跳过了这一步', icon: Icons.skip_next_outlined);
    case 'PRUNE':
      return (label: '裁撤了这一步', icon: Icons.cut_outlined);
    case 'REOPEN':
      return (label: '重新打开了方案', icon: Icons.replay_outlined);
    case 'CANCEL':
      return (label: '取消了方案', icon: Icons.cancel_outlined);
    case 'VOTE':
      return (label: '投了一票', icon: Icons.how_to_vote_outlined);
    case 'ADD_SLOT':
      return (label: '增加了候选时段', icon: Icons.add_circle_outline);
    case 'FORCE_FINALIZE':
      return (label: '落槌定了时段', icon: Icons.gavel_outlined);
    case 'NUDGE':
      return (label: '催促了待确认成员', icon: Icons.notifications_active_outlined);
    case 'BRIEFING_UPDATE':
      return (label: '更新了备忘', icon: Icons.sticky_note_2_outlined);
    case 'STEP_NOTE':
      return (label: '记录了便签', icon: Icons.note_outlined);
    default:
      return (label: action, icon: Icons.circle_outlined);
  }
}

String _formatTime(int ms) {
  if (ms <= 0) return '';
  final d = DateTime.fromMillisecondsSinceEpoch(ms);
  final mm = d.minute.toString().padLeft(2, '0');
  final hh = d.hour.toString().padLeft(2, '0');
  final mo = d.month.toString().padLeft(2, '0');
  final day = d.day.toString().padLeft(2, '0');
  return '$mo-$day $hh:$mm';
}

/// 步骤便签抽屉（§11.2 F 页「步骤问答便签」）：选步骤 + ≤50 字覆盖式更新。
class StepNoteSheet extends StatefulWidget {
  final List<CollabStep> steps;
  final Future<void> Function(String stepId, String note) onConfirm;

  const StepNoteSheet({
    super.key,
    required this.steps,
    required this.onConfirm,
  });

  @override
  State<StepNoteSheet> createState() => _StepNoteSheetState();
}

class _StepNoteSheetState extends State<StepNoteSheet> {
  String? _stepId;
  final _noteCtrl = TextEditingController();
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _noteCtrl.addListener(() => setState(() {})); // 文本变化刷新「保存」可用态
  }

  @override
  void dispose() {
    _noteCtrl.dispose();
    super.dispose();
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
          const Text('步骤便签',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Text('给某一步加一条 ≤50 字的备注，覆盖式更新，经加密随事件流入库。',
              style: TextStyle(color: StColors.textSecondary, fontSize: 12)),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: widget.steps
                .map((s) => ChoiceChip(
                      label: Text('步骤 ${s.id}'),
                      selected: _stepId == s.id,
                      onSelected: (_) => setState(() => _stepId = s.id),
                    ))
                .toList(),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _noteCtrl,
            maxLength: 50,
            maxLines: 2,
            decoration: const InputDecoration(
              hintText: '备注内容（选填要点，不形成聊天流）',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 14),
          FilledButton(
            onPressed: _canSave() && !_saving
                ? () async {
                    setState(() => _saving = true);
                    await widget.onConfirm(_stepId!, _noteCtrl.text.trim());
                    if (mounted) setState(() => _saving = false);
                  }
                : null,
            child: _saving
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('保存便签'),
          ),
        ],
      ),
    );
  }

  bool _canSave() => _stepId != null && _noteCtrl.text.trim().isNotEmpty;
}
