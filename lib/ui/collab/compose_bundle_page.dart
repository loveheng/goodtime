import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../collab/outbox.dart';
import '../../collab/propose.dart';
import '../../collab/relay/auth_api.dart' show RelayAuthException;
import '../../collab/relay/peers_api.dart';
import '../../theme/tokens.dart';

/// 发起协同（§11.2 A 页的发起端；Phase 4.3 发起切片）。
///
/// 三块表单即三件事：**和谁一起**（只能挑双向 accepted 的好友——服务端 PROPOSE
/// 会复检准入 §1.7）/ **怎么定**（各司其职 Directive / 大家投票 Consensus）/ **做什么**
/// （步骤或候选时段）。点「发出邀请」后全部 work 都交给 `BundleProposalService`：
/// 造 K_bundle → 逐成员封信封 → 落发件箱 → 尝试弹出（离线留在箱里），UI 只在最后
/// 翻译结局给用户听。
///
/// 纯渲染在 [ComposeBundleForm]（不碰 DB / crypto，可单测）；本页只做数据装配与路由。
class ComposeBundlePage extends StatefulWidget {
  final BundleProposalService proposer;
  final Future<List<FriendView>> Function() loadFriends;

  /// 立即弹出钩子（离线挂号也不会丢）；为空=只入箱，等常驻调度联网补发。
  final Future<DrainReport> Function()? drainNow;
  final String meId;

  final int Function() nowMs;
  final String Function() newLocalId;

  /// 零好友空态的引导入口：跳到好友域加一位（否则发起页名单永空，协同不可达真机）。
  final VoidCallback? onAddFriend;

  const ComposeBundlePage({
    super.key,
    required this.proposer,
    required this.loadFriends,
    required this.meId,
    this.drainNow,
    int Function()? nowMs,
    String Function()? newLocalId,
    this.onAddFriend,
  })  : nowMs = nowMs ?? _defaultNow,
        newLocalId = newLocalId ?? _defaultLocalId;

  static int _defaultNow() => DateTime.now().millisecondsSinceEpoch;
  static String _defaultLocalId() =>
      'local-${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}';

  @override
  State<ComposeBundlePage> createState() => _ComposeBundlePageState();
}

class _ComposeBundlePageState extends State<ComposeBundlePage> {
  final List<FriendView> _contacts = [];
  final Set<String> _selected = <String>{};
  final List<TextEditingController> _titles = [];
  final List<ProposalStepDraft> _steps = [];
  ProposalKind _kind = ProposalKind.directive;
  bool _loading = true;
  bool _sending = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _addStep(); // 开局给一条空步骤（不留空白 workflow）
    _load();
  }

  @override
  void dispose() {
    for (final c in _titles) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final friends = await widget.loadFriends();
      if (!mounted) return;
      setState(() {
        _contacts
          ..clear()
          ..addAll(friends);
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '暂时连不上中继，取不到好友名单';
      });
    }
  }

  void _addStep() {
    setState(() {
      final id = 's${_steps.length + 1}';
      _titles.add(TextEditingController());
      _steps.add(ProposalStepDraft(
        stepId: id,
        requiredUids: List<String>.from(_selected),
      ));
    });
  }

  void _removeStep(int index) {
    setState(() {
      _titles.removeAt(index).dispose();
      _steps.removeAt(index);
    });
  }

  void _toggleMember(String uid) {
    setState(() {
      if (_selected.contains(uid)) {
        _selected.remove(uid);
        for (var i = 0; i < _steps.length; i++) {
          _steps[i] = _withRequired(_steps[i], required: false, uid: uid);
        }
      } else {
        _selected.add(uid);
        // 新邀的人默认加入每个步骤的必答名单（发起人可逐项取消）
        if (_kind == ProposalKind.directive) {
          for (var i = 0; i < _steps.length; i++) {
            _steps[i] = _withRequired(_steps[i], required: true, uid: uid);
          }
        }
      }
    });
  }

  ProposalStepDraft _withRequired(
    ProposalStepDraft step, {
    required bool required,
    required String uid,
  }) {
    final next = step.requiredUids.where((u) => u != uid).toList();
    if (required) next.add(uid);
    return ProposalStepDraft(
      stepId: step.stepId,
      title: step.title,
      requiredUids: next,
      threshold: step.threshold,
      isOptional: step.isOptional,
      startsAtMs: step.startsAtMs,
      endsAtMs: step.endsAtMs,
    );
  }

  void _setKind(ProposalKind kind) {
    setState(() {
      _kind = kind;
      if (kind == ProposalKind.consensus) {
        for (var i = 0; i < _steps.length; i++) {
          _steps[i] = ProposalStepDraft(
            stepId: _steps[i].stepId,
            title: _steps[i].title,
            startsAtMs: _steps[i].startsAtMs,
            endsAtMs: _steps[i].endsAtMs,
          );
        }
      }
    });
  }

  Future<void> _pickSlot(int index) async {
    final now = DateTime.now();
    final day = await showDatePicker(
      context: context,
      initialDate: now,
      firstDate: now,
      lastDate: now.add(const Duration(days: 365)),
    );
    if (day == null || !mounted) return;
    final start = await showTimePicker(
        context: context, initialTime: const TimeOfDay(hour: 10, minute: 0));
    if (start == null || !mounted) return;
    final endfrom = TimeOfDay(
        hour: (start.hour + 1) % 24, minute: start.minute);
    final end = await showTimePicker(context: context, initialTime: endfrom);
    if (end == null || !mounted) return;

    int at(TimeOfDay t) => DateTime(
          day.year,
          day.month,
          day.day,
          t.hour,
          t.minute,
        ).millisecondsSinceEpoch;
    setState(() {
      final s = _steps[index];
      _steps[index] = ProposalStepDraft(
        stepId: s.stepId,
        title: s.title,
        requiredUids: s.requiredUids,
        threshold: s.threshold,
        isOptional: s.isOptional,
        startsAtMs: at(start),
        endsAtMs: at(end),
      );
    });
  }

  BundleDraft _draft() => BundleDraft(
        localId: widget.newLocalId(),
        kind: _kind,
        inviteeUids: _selected.toList(),
        steps: [
          for (var i = 0; i < _steps.length; i++)
            ProposalStepDraft(
              stepId: _steps[i].stepId,
              title: _titles[i].text.trim(),
              requiredUids: _steps[i].requiredUids,
              threshold: _steps[i].threshold,
              isOptional: _steps[i].isOptional,
              startsAtMs: _steps[i].startsAtMs,
              endsAtMs: _steps[i].endsAtMs,
            ),
        ],
      );

  Future<void> _submit() async {
    setState(() => _sending = true);
    final draft = _draft();
    try {
      // 装箱前的全部风险都在这里：取成员公钥（联网）、K_bundle 落户、发件箱落盘。
      // 任一步炸掉＝**箱里一条都没有**，反馈必须说「没发出去」，不许说已挂号。
      await widget.proposer.propose(meId: widget.meId, draft: draft);
    } on ArgumentError catch (e) {
      if (!mounted) return;
      setState(() => _sending = false);
      _toast(e.message.toString());
      return;
    } catch (e) {
      debugPrint('[compose] propose 未完成（未入箱）: $e');
      if (!mounted) return;
      setState(() => _sending = false);
      _toast(composeFailureText(e));
      return;
    }
    // 走到这里＝确已落进待发箱（§11.1 先落盘、后反馈）；此后无论弹出成败，
    // 条目都在、常驻调度联网必补发，反馈只能报「联网后自动发出」。
    var sent = false;
    try {
      if (widget.drainNow != null) {
        sent = composeSentOut(await widget.drainNow!());
      }
    } catch (e) {
      // 弹出自身炸了（drainer 只归一化传输层异常，DB 级异常会漏到这里）：
      // 箱内条目原样保留，按未发出反馈即可，不必让用户重试。
      debugPrint('[compose] drain 抛异常（条目仍在箱内）: $e');
    }
    if (!mounted) return;
    _toast(sent ? '已发出，对方会收到一条邀约' : '已记下，联网后自动发出');
    Navigator.of(context).pop();
  }

  void _toast(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('发起协同')),
      body: ComposeBundleForm(
        loading: _loading,
        error: _error,
        contacts: _contacts,
        selected: _selected,
        kind: _kind,
        steps: _steps,
        titleOf: (i) => _titles[i],
        sending: _sending,
        onToggleContact: _toggleMember,
        onKindChanged: _setKind,
        onToggleRequired: (i, uid) {
          final s = _steps[i];
          setState(() => _steps[i] = _withRequired(s,
              required: !s.requiredUids.contains(uid), uid: uid));
        },
        onPickSlot: _pickSlot,
        onAddStep: _addStep,
        onRemoveStep: _removeStep,
        onSubmit: _submit,
        onAddFriend: widget.onAddFriend,
      ),
    );
  }
}

// ---- 发起回执判定（纯函数，可单测） -----------------------------------------

/// 弹出战报 → 是否「已发出」：本轮没有因离线保序停止、且确实有包被服务端发号。
bool composeSentOut(DrainReport? report) =>
    report != null && !report.stoppedByNetwork && report.created.isNotEmpty;

/// 装箱前失败的原因白话翻译（ui-spec §0.4「发起失败原因」）。
///
/// 分类型落到具体处置动作：看不出门类的兜底也把异常摘要带上——真机 release
/// 无日志可捞，屏面上那句话就是唯一的现场证据。
String composeFailureText(Object e) {
  if (e is RelayAuthException) return _relayFailureText(e.statusCode);
  if (e is RelayPeersException) return _relayFailureText(e.statusCode);
  if (e is DioException) return '没发出去：暂时连不上中继，联网后再试一次';
  return '没发出去：${e.runtimeType} $e';
}

String _relayFailureText(int code) => switch (code) {
      401 => '没发出去：登录态已过期，请到「设置 → 协同账号」重新登录',
      404 => '没发出去：取不到对方的通信公钥，让对方重新登录一次再约',
      429 => '没发出去：操作太频繁，稍后再试',
      _ => '没发出去：中继打回了这次邀请（$code），稍后再试',
    };

/// 发起表单的纯渲染层（不依赖 DB/crypto，可直接 widget 测试）。
class ComposeBundleForm extends StatelessWidget {
  final bool loading;
  final String? error;
  final List<FriendView> contacts;
  final Set<String> selected;
  final ProposalKind kind;
  final List<ProposalStepDraft> steps;
  final TextEditingController Function(int index) titleOf;
  final bool sending;

  final void Function(String uid) onToggleContact;
  final void Function(ProposalKind kind) onKindChanged;
  final void Function(int index, String uid) onToggleRequired;
  final Future<void> Function(int index) onPickSlot;
  final VoidCallback onAddStep;
  final void Function(int index) onRemoveStep;
  final Future<void> Function() onSubmit;
  final VoidCallback? onAddFriend;

  const ComposeBundleForm({
    super.key,
    required this.loading,
    required this.contacts,
    required this.selected,
    required this.kind,
    required this.steps,
    required this.titleOf,
    required this.sending,
    required this.onToggleContact,
    required this.onKindChanged,
    required this.onToggleRequired,
    required this.onPickSlot,
    required this.onAddStep,
    required this.onRemoveStep,
    required this.onSubmit,
    this.onAddFriend,
    this.error,
  });

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: CircularProgressIndicator());
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(error!,
                style: TextStyle(fontSize: 13, color: StColors.textSecondary)),
          ),
        _SectionLabel(title: '和谁一起', hint: selected.isEmpty ? '至少选一位' : null),
        const SizedBox(height: 8),
        if (contacts.isEmpty)
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('还没有互相确认的好友',
                  style:
                      TextStyle(fontSize: 13, color: StColors.textSecondary)),
              if (onAddFriend != null)
                TextButton.icon(
                  key: const Key('compose-add-friend'),
                  onPressed: onAddFriend,
                  icon: const Icon(Icons.person_add_alt_1, size: 16),
                  label: const Text('去好友里加一位'),
                ),
            ],
          )
        else
          Wrap(
            spacing: 8,
            children: [
              for (final f in contacts)
                FilterChip(
                  key: Key('contact-${f.userId}'),
                  label: Text(f.userId),
                  selected: selected.contains(f.userId),
                  onSelected: (_) => onToggleContact(f.userId),
                ),
            ],
          ),
        const SizedBox(height: 20),
        _SectionLabel(title: '怎么定', hint: null),
        const SizedBox(height: 8),
        SegmentedButton<ProposalKind>(
          segments: const [
            ButtonSegment(value: ProposalKind.directive, label: Text('各司其职')),
            ButtonSegment(value: ProposalKind.consensus, label: Text('大家投票')),
          ],
          selected: {kind},
          onSelectionChanged: (s) => onKindChanged(s.first),
        ),
        const SizedBox(height: 20),
        _SectionLabel(
            title: kind == ProposalKind.consensus ? '候选时间' : '要做什么',
            hint: null),
        const SizedBox(height: 8),
        for (var i = 0; i < steps.length; i++)
          _StepCard(
            index: i,
            step: steps[i],
            kind: kind,
            controller: titleOf(i),
            selected: selected.toList(),
            onToggleRequired: onToggleRequired,
            onPickSlot: onPickSlot,
            onRemove: steps.length > 1 ? () => onRemoveStep(i) : null,
          ),
        const SizedBox(height: 8),
        TextButton.icon(
          key: const Key('compose-add-step'),
          onPressed: onAddStep,
          icon: const Icon(Icons.add, size: 18),
          label: Text(kind == ProposalKind.consensus ? '再加一个候选' : '再加一步'),
        ),
        const SizedBox(height: 16),
        FilledButton(
          key: const Key('compose-submit'),
          onPressed: sending ? null : () => onSubmit(),
          child: Text(sending ? '正在发出…' : '发出邀请'),
        ),
      ],
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String title;
  final String? hint;
  const _SectionLabel({required this.title, this.hint});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Text(title,
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        if (hint != null) ...[
          const SizedBox(width: 8),
          Text(hint!,
              style:
                  TextStyle(fontSize: 12, color: StColors.textSecondary)),
        ],
      ],
    );
  }
}

class _StepCard extends StatelessWidget {
  final int index;
  final ProposalStepDraft step;
  final ProposalKind kind;
  final TextEditingController controller;
  final List<String> selected;
  final void Function(int index, String uid) onToggleRequired;
  final Future<void> Function(int index) onPickSlot;
  final VoidCallback? onRemove;

  const _StepCard({
    required this.index,
    required this.step,
    required this.kind,
    required this.controller,
    required this.selected,
    required this.onToggleRequired,
    required this.onPickSlot,
    this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    decoration: InputDecoration(
                      isDense: true,
                      border: InputBorder.none,
                      hintText: kind == ProposalKind.consensus
                          ? '候选时段名（如「周六下午」）'
                          : '这一步做什么',
                    ),
                  ),
                ),
                if (onRemove != null)
                  IconButton(
                    icon: const Icon(Icons.close, size: 18),
                    onPressed: onRemove,
                  ),
              ],
            ),
            const SizedBox(height: 6),
            TextButton.icon(
              key: Key('pick-slot-$index'),
              onPressed: () => onPickSlot(index),
              icon: const Icon(Icons.schedule, size: 16),
              label: Text(step.startsAtMs == null
                  ? '设置时段'
                  : '${_fmt(step.startsAtMs!)} – ${_fmt(step.endsAtMs!)}'),
            ),
            if (kind == ProposalKind.directive) ...[
              const SizedBox(height: 4),
              Wrap(
                spacing: 6,
                children: [
                  for (final uid in selected)
                    FilterChip(
                      key: Key('required-$index-$uid'),
                      label: Text(uid),
                      selected: step.requiredUids.contains(uid),
                      onSelected: (_) => onToggleRequired(index, uid),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

String _fmt(int ms) {
  final t = DateTime.fromMillisecondsSinceEpoch(ms);
  return '${t.month}/${t.day} ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
}
