import 'package:flutter/material.dart';

import '../../collab/outbox.dart';
import '../../app_services.dart';
import '../../collab/relay_session.dart';
import '../../data/collab_repository.dart';
import '../../theme/tokens.dart';
import 'audit_timeline_page.dart';
import 'compose_bundle_page.dart';
import 'decision_matrix_page.dart';
import 'friends_page.dart';
import 'reject_panel.dart';

/// 协同日程（§11.2 B 页「成员混合日历」）。
///
/// 把朋友发来的协同邀约与你自己的日程混在一起看：每个协同包的步骤按**三态预占**
/// 渲染——`HARD_TENTATIVE`（团队调度·主色虚线块 + 待确认徽标·排他）/ `SOFT_CANDIDATE`
/// （生活预约·超细浅灰虚线 + 候选标签·透明底不遮挡）/ `CONFIRMED`（实心卡 + 状态条）。
/// 点未确认步骤弹底部**一键响应浮条**（同意 / 拒绝并说明），拒绝走**双维拒绝面板**
/// （§11.2 D 页）；卡片「时间轴」入口进**审计时间轴**（§11.2 F 页）。
///
/// 页面本体只负责从 [CollabBundleRepository] 拉取数据并焊接响应落库；纯渲染落在
/// [CollabBundleListView] / [ResponseSheet]（公开、可单测，不依赖 DB/异步）。
///
/// **施工钉子（与数据层对齐）**：时间轴叠块需等 FSM 回放把步骤时间槽物化到本地表
/// 后才能做——`collab_steps` 现仅存步骤态/投票。本页先以列表/卡片混合承载三态预占
/// 与一键响应；时间叠块为后续切片（Phase 4 数据收口）。
class MemberCalendarPage extends StatefulWidget {
  final CollabBundleRepository collabRepo;
  final OutboxRepository outboxRepo;
  final String? currentUserId;
  final Future<List<int>?> Function(String bundleId, int keyVer)? bundleKey;

  /// 本地发起 bundle 的真机套件装配钩子（`relay_session.buildBundleComposeKit`）。
  ///
  /// 为空则不显示发起入口——发起链路要同时拿到「身份私钥 + MSK」，缺任何一个都
  /// 发不出成员可解的邀约，与其给个必然失败的按钮，不如不显示。
  final Future<BundleComposeKit?> Function()? loadComposeKit;

  /// 好友域会话装配钩子（`relay_session.buildFriendsSession`）。
  ///
  /// 为空则不显示「好友」入口——好友域同样要「身份私钥 + MSK」，缺任何一个都签不出名片。
  final Future<RelaySession?> Function()? loadFriendsSession;

  const MemberCalendarPage({
    super.key,
    required this.collabRepo,
    required this.outboxRepo,
    this.currentUserId,
    this.bundleKey,
    this.loadComposeKit,
    this.loadFriendsSession,
  });

  @override
  State<MemberCalendarPage> createState() => _MemberCalendarPageState();
}

class _MemberCalendarPageState extends State<MemberCalendarPage> {
  List<CollabBundleHeader> _bundles = const [];
  Map<String, List<CollabStep>> _steps = const {};
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final bundles = await widget.collabRepo.listBundles();
    final steps = <String, List<CollabStep>>{};
    for (final b in bundles) {
      steps[b.id] = await widget.collabRepo.listSteps(b.id);
    }
    if (!mounted) return;
    setState(() {
      _bundles = bundles;
      _steps = steps;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('协同日程'),
        actions: [
          if (widget.loadFriendsSession != null)
            IconButton(
              icon: const Icon(Icons.people_outline),
              tooltip: '好友',
              onPressed: _openFriends,
            ),
        ],
      ),
      body: CollabBundleListView(
        loading: _loading,
        bundles: _bundles,
        steps: _steps,
        onRespond: _openResponse,
        onOpenTimeline: _openTimeline,
        onOpenDecisions: _openDecisions,
      ),
      floatingActionButton: widget.loadComposeKit == null
          ? null
          : FloatingActionButton.extended(
              icon: const Icon(Icons.add, size: 18),
              label: const Text('发起协同'),
              onPressed: _compose,
            ),
    );
  }

  Future<void> _compose() async {
    final kit = await widget.loadComposeKit!();
    if (!mounted) return;
    if (kit == null) {
      // null 两来路：未登录，或已登录但本机无协同身份记录（卸载重装清了
      // 安全区）。文案按真实来路指路，别让已登录用户对着「请登录」死循环。
      final loggedIn = AppServices.account.isLoggedIn;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(loggedIn
              ? '本机协同身份未就绪，到「设置 → 协同账号」重新注册'
              : '先到「设置 → 协同账号」登录，才能发出邀约'),
          behavior: SnackBarBehavior.floating,
        ),
      );
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ComposeBundlePage(
          proposer: kit.proposer,
          loadFriends: kit.loadFriends,
          drainNow: kit.drainNow,
          meId: widget.currentUserId ?? '',
          onAddFriend: _openFriends,
        ),
      ),
    );
    // 认祖归宗成功的包会立刻出现在本地列表里；仍是草稿（离线未弹出）则等联网。
    _load();
  }

  Future<void> _openFriends() async {
    final session = await widget.loadFriendsSession!();
    if (!mounted) return;
    if (session == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('先到「设置 → 协同账号」登录，才能管理好友'),
          behavior: SnackBarBehavior.floating,
        ),
      );
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => FriendsPage(loadSession: () async => session)),
    );
  }

  void _openResponse(CollabBundleHeader bundle, CollabStep step) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => ResponseSheet(
        bundle: bundle,
        step: step,
        onAccept: () => _accept(bundle, step),
        onReject: () => _openRejectPanel(bundle, step),
      ),
    );
  }

  void _openRejectPanel(CollabBundleHeader bundle, CollabStep step) {
    Navigator.of(context).pop(); // 关响应浮条，再开双维拒绝面板
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => RejectPanel(
        bundle: bundle,
        step: step,
        currentUserId: widget.currentUserId,
        onConfirm: (result) => _reject(bundle, step, result),
      ),
    );
  }

  Future<void> _accept(CollabBundleHeader bundle, CollabStep step) async {
    final isConsensus = bundle.proposalType == 'CONSENSUS';
    await widget.outboxRepo.enqueue(
      bundleId: bundle.id,
      actionType: isConsensus ? 'AVAILABILITY' : 'ACCEPT',
      payload: <String, Object?>{
        'step_id': step.id,
        'decision': 'OK',
      },
    );
    if (mounted) {
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已记下，联网后自动发送')),
      );
    }
  }

  Future<void> _reject(
    CollabBundleHeader bundle,
    CollabStep step,
    RejectResult result,
  ) async {
    if (bundle.proposalType == 'CONSENSUS') {
      // Consensus 无步骤权责异议：异议走时段投票（NO 票），附结构化理由供发起人参考。
      await widget.outboxRepo.enqueue(
        bundleId: bundle.id,
        actionType: 'VOTE',
        payload: <String, Object?>{
          'step_id': step.id,
          'preference': 'NO',
          'reason': result.reason,
          if (result.detail != null && result.detail!.isNotEmpty)
            'detail': result.detail!,
        },
      );
    } else {
      await widget.outboxRepo.enqueue(
        bundleId: bundle.id,
        actionType: 'REJECT',
        payload: <String, Object?>{
          'step_id': step.id,
          'reason': result.reason,
          'sub_reason': result.subReason,
          if (result.detail != null && result.detail!.isNotEmpty)
            'detail': result.detail!,
          'privacy': result.privacy,
          if (result.suggestReassign != null)
            'suggest_reassign': result.suggestReassign!,
        },
      );
    }
    if (mounted) {
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已记下，联网后自动发送')),
      );
    }
  }

  void _openTimeline(CollabBundleHeader bundle) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => AuditTimelinePage(
          collabRepo: widget.collabRepo,
          outboxRepo: widget.outboxRepo,
          bundleId: bundle.id,
          initiatorId: bundle.initiatorId,
          currentUserId: widget.currentUserId,
          steps: _steps[bundle.id] ?? const [],
        ),
      ),
    );
  }

  void _openDecisions(CollabBundleHeader bundle) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => DecisionMatrixPage(
          collabRepo: widget.collabRepo,
          outboxRepo: widget.outboxRepo,
          bundleId: bundle.id,
          initiatorId: bundle.initiatorId,
          currentUserId: widget.currentUserId,
          bundleKey: widget.bundleKey,
          steps: _steps[bundle.id] ?? const [],
        ),
      ),
    );
  }
}

/// 协同日程纯渲染列表：加载态 / 空态 / 各包步骤三态预占（不依赖 DB，可单测）。
class CollabBundleListView extends StatelessWidget {
  final bool loading;
  final List<CollabBundleHeader> bundles;
  final Map<String, List<CollabStep>> steps;
  final void Function(CollabBundleHeader, CollabStep) onRespond;
  final void Function(CollabBundleHeader)? onOpenTimeline;
  final void Function(CollabBundleHeader)? onOpenDecisions;

  const CollabBundleListView({
    super.key,
    required this.loading,
    required this.bundles,
    required this.steps,
    required this.onRespond,
    this.onOpenTimeline,
    this.onOpenDecisions,
  });

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: Text('加载中…'));
    }
    if (bundles.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.groups_outlined, size: 56, color: StColors.textSecondary),
              const SizedBox(height: 16),
              Text(
                '还没有协同日程',
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: StColors.textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '和朋友约起来，邀约会混合显示在这里——待确认、候选、已确认三态一眼分清。',
                textAlign: TextAlign.center,
                style: TextStyle(color: StColors.textSecondary),
              ),
            ],
          ),
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: bundles.length,
      separatorBuilder: (_, _) => const SizedBox(height: 16),
      itemBuilder: (_, i) => _BundleCard(
        bundle: bundles[i],
        steps: steps[bundles[i].id] ?? const [],
        onRespond: onRespond,
        onOpenTimeline: onOpenTimeline,
        onOpenDecisions: onOpenDecisions,
      ),
    );
  }
}

/// 预占三态（§11.2 B 页视觉三级）。
enum ReservationTier { confirmed, hardTentative, softCandidate }

ReservationTier _tierOf(CollabBundleHeader b) {
  if (b.status == 'CONFIRMED') return ReservationTier.confirmed;
  return b.proposalType == 'DIRECTIVE'
      ? ReservationTier.hardTentative
      : ReservationTier.softCandidate;
}

String _proposalLabel(String t) => t == 'DIRECTIVE' ? '团队调度' : '生活预约';

String _statusLabel(String s) => switch (s) {
      'PROPOSED' => '协商中',
      'CONFIRMED' => '已确认',
      'STALLED' => '待发起人闭环',
      'EXPIRED' => '已流局',
      'CANCELLED' => '已取消',
      _ => s,
    };

/// 单个协同包卡片：头部（类型 / 状态 / N 人协同 / 时间轴入口）+ 步骤三态预占列表。
class _BundleCard extends StatelessWidget {
  final CollabBundleHeader bundle;
  final List<CollabStep> steps;
  final void Function(CollabBundleHeader, CollabStep) onRespond;
  final void Function(CollabBundleHeader)? onOpenTimeline;
  final void Function(CollabBundleHeader)? onOpenDecisions;

  const _BundleCard({
    required this.bundle,
    required this.steps,
    required this.onRespond,
    this.onOpenTimeline,
    this.onOpenDecisions,
  });

  @override
  Widget build(BuildContext context) {
    final tier = _tierOf(bundle);
    return Card(
      elevation: 1,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Chip(
                  label: Text(_proposalLabel(bundle.proposalType)),
                  visualDensity: VisualDensity.compact,
                  backgroundColor: StColors.sparkBg,
                ),
                const SizedBox(width: 8),
                Chip(
                  label: Text(_statusLabel(bundle.status)),
                  visualDensity: VisualDensity.compact,
                  backgroundColor: Colors.grey.shade100,
                ),
                const Spacer(),
                Text(
                  '${bundle.participants.length} 人协同',
                  style: TextStyle(color: StColors.textSecondary, fontSize: 13),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                if (onOpenTimeline != null)
                  TextButton.icon(
                    icon: const Icon(Icons.timeline_outlined, size: 16),
                    label: const Text('时间轴'),
                    onPressed: () => onOpenTimeline!(bundle),
                  ),
                if (onOpenDecisions != null)
                  TextButton.icon(
                    icon: const Icon(Icons.account_tree_outlined, size: 16),
                    label: const Text('决策'),
                    onPressed: () => onOpenDecisions!(bundle),
                  ),
              ],
            ),
            if (steps.isEmpty)
              Text('暂无步骤', style: TextStyle(color: StColors.textSecondary))
            else
              for (final step in steps)
                _StepTile(
                  step: step,
                  tier: tier,
                  onTap: tier == ReservationTier.confirmed
                      ? null
                      : () => onRespond(bundle, step),
                ),
          ],
        ),
      ),
    );
  }
}

/// 单步骤瓦片：三态预占视觉（实底色+状态条 / 主色虚线+待确认 / 浅灰细线+候选）。
class _StepTile extends StatelessWidget {
  final CollabStep step;
  final ReservationTier tier;
  final VoidCallback? onTap;

  const _StepTile({
    required this.step,
    required this.tier,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final title = Text('步骤 ${step.id}', style: const TextStyle(fontSize: 15));
    final badge = switch (tier) {
      ReservationTier.confirmed => _tag('已确认', StColors.sparkStroke),
      ReservationTier.hardTentative => _tag('待确认', StColors.sparkStroke),
      ReservationTier.softCandidate => _tag('候选', StColors.textSecondary),
    };
    final border = switch (tier) {
      ReservationTier.confirmed => Border.all(color: StColors.sparkStroke),
      ReservationTier.hardTentative =>
        Border.all(color: StColors.sparkStroke, width: 1.5),
      ReservationTier.softCandidate => Border.all(color: Colors.grey.shade300),
    };
    final fill = tier == ReservationTier.confirmed
        ? StColors.sparkBg
        : Colors.transparent;
    return InkWell(
      onTap: onTap,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: fill,
          border: border,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          children: [
            Expanded(child: title),
            const SizedBox(width: 8),
            badge,
            if (onTap != null) ...[
              const SizedBox(width: 6),
              Icon(Icons.chevron_right, size: 18, color: StColors.textSecondary),
            ],
          ],
        ),
      ),
    );
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
}

/// 底部一键响应浮条（§11.2 B 页入口）：同意 / 拒绝并说明（拒绝走双维拒绝面板）。
class ResponseSheet extends StatelessWidget {
  final CollabBundleHeader bundle;
  final CollabStep step;
  final Future<void> Function() onAccept;
  final void Function() onReject;

  const ResponseSheet({
    super.key,
    required this.bundle,
    required this.step,
    required this.onAccept,
    required this.onReject,
  });

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
          const Text('回应这一步',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          Text('步骤 ${step.id} · ${_proposalLabel(bundle.proposalType)}',
              style: TextStyle(color: StColors.textSecondary)),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: () => onAccept(),
            child: const Text('同意'),
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: () => onReject(),
            child: const Text('拒绝并说明'),
          ),
        ],
      ),
    );
  }
}
