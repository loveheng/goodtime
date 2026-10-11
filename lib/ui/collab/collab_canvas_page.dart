import 'package:flutter/material.dart';

import '../../collab/freemask.dart' show freeMaskBitwiseAnd;
import '../../data/collab_repository.dart' show CollabBundleRepository, CollabStep;
import '../../theme/tokens.dart';

/// 发起人排期画板（§11.2 A 页）。
///
/// 三件事：①**双轨切换**（团队大盘 / 个人总览）；②**全员共振条**——按 FreeMask
/// 逐个 30min 桶统计「几人有空」，人越多越绿，全员交集桶再高亮描边（Quorum Density
/// Bar，协作设计 §3.6）；③**私事幽灵图层**——发起人自己的私事区间以半透明斜纹投影
/// 在画面上，与候选块重叠时只给琥珀色温和提示，绝不断言冲突（§3.6 铁律：决断权在
/// 发起人，弱占位不参与忙碌回填）。
///
/// 渲染层 [CollabCanvasView] 不持有数据源：时段来自 Phase 4.3 物化回填的
/// `collab_steps.starts_at_ms/ends_at_ms`，掩码由调用方注入。
class CollabCanvasPage extends StatelessWidget {
  final CollabBundleRepository collabRepo;
  final String bundleId;
  final int windowStartMs;
  final int windowEndMs;

  /// 全员 FreeMask（每位成员一份，同区间同长）；为空则共振条降级显示无数据提示。
  final Future<List<String>> Function()? loadMasks;

  /// 发起人私事忙碌区间（幽灵图层数据源）；为空则不投影该图层。
  final Future<List<(int, int)>> Function()? loadPrivateBusy;

  const CollabCanvasPage({
    super.key,
    required this.collabRepo,
    required this.bundleId,
    required this.windowStartMs,
    required this.windowEndMs,
    this.loadMasks,
    this.loadPrivateBusy,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('排期画板')),
      body: FutureBuilder<_CanvasData>(
        future: _load(),
        builder: (context, snap) {
          if (!snap.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final data = snap.data!;
          return CollabCanvasView(
            steps: data.steps,
            masks: data.masks,
            privateBusy: data.privateBusy,
            windowStartMs: windowStartMs,
            windowEndMs: windowEndMs,
          );
        },
      ),
    );
  }

  Future<_CanvasData> _load() async {
    final steps = await collabRepo.listSteps(bundleId);
    final masks = await loadMasks?.call() ?? const <String>[];
    final privateBusy = await loadPrivateBusy?.call() ?? const <(int, int)>[];
    return _CanvasData(steps, masks, privateBusy);
  }
}

class _CanvasData {
  final List<CollabStep> steps;
  final List<String> masks;
  final List<(int, int)> privateBusy;
  const _CanvasData(this.steps, this.masks, this.privateBusy);
}

/// 桶级共振密度：第 i 个桶「几人有空」。
///
/// 与 [freeMaskBitwiseAnd]（全员交集，0/1 二值）互补：共振条要的是**强度**（越绿
/// 人越多），由本函数给每桶人数；全员交集桶另加一圈高亮描边。
/// 掩码长度不齐视为调用方 bug（§3.6 约束①：靶向区间统一），这里取最长防越界。
List<int> quorumDensity(List<String> masks) {
  if (masks.isEmpty) return const [];
  final len = masks.fold<int>(0, (m, s) => s.length > m ? s.length : m);
  return [
    for (var i = 0; i < len; i++)
      masks.fold<int>(0, (n, m) => n + (i < m.length && m[i] == '1' ? 1 : 0)),
  ];
}

/// 画板纯渲染层（不依赖 DB，可单测）。
class CollabCanvasView extends StatefulWidget {
  final List<CollabStep> steps;
  final List<String> masks;
  final List<(int, int)> privateBusy;
  final int windowStartMs;
  final int windowEndMs;

  /// 私事幽灵图层的稳定查找键（便于测试与调试定位，避免误匹配系统自带的 CustomPaint）。
  static const ghostLayerKey = Key('collab-canvas-ghost-layer');

  const CollabCanvasView({
    super.key,
    required this.steps,
    this.masks = const [],
    this.privateBusy = const [],
    required this.windowStartMs,
    required this.windowEndMs,
  });

  @override
  State<CollabCanvasView> createState() => _CollabCanvasViewState();
}

class _CollabCanvasViewState extends State<CollabCanvasView> {
  bool _personal = false; // false=团队大盘，true=个人总览

  @override
  Widget build(BuildContext context) {
    final density = quorumDensity(widget.masks);
    String allFree = '';
    if (widget.masks.isNotEmpty) {
      try {
        allFree = freeMaskBitwiseAnd(widget.masks);
      } catch (_) {
        allFree = ''; // 长度不齐：共振条降级，不阻塞渲染
      }
    }
    final blocks = widget.steps
        .where((s) => s.startsAtMs != null && s.endsAtMs != null)
        .toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: SegmentedButton<bool>(
            segments: const [
              ButtonSegment(value: false, label: Text('团队大盘')),
              ButtonSegment(value: true, label: Text('个人总览')),
            ],
            selected: {_personal},
            onSelectionChanged: (s) => setState(() => _personal = s.first),
          ),
        ),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Text('全员共振条',
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: StColors.textSecondary)),
        ),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: _QuorumBar(
            density: density,
            allFree: allFree,
            memberCount: widget.masks.length,
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: _Canvas(
            blocks: blocks,
            privateBusy: widget.privateBusy,
            windowStartMs: widget.windowStartMs,
            windowEndMs: widget.windowEndMs,
            showPersonal: _personal,
          ),
        ),
      ],
    );
  }
}

/// 全员共振条：按桶着色，有空人数越多越绿；全员交集桶描边高亮。
class _QuorumBar extends StatelessWidget {
  final List<int> density;
  final String allFree;
  final int memberCount;

  const _QuorumBar({
    required this.density,
    required this.allFree,
    required this.memberCount,
  });

  @override
  Widget build(BuildContext context) {
    if (density.isEmpty) {
      return Container(
        height: 24,
        decoration: BoxDecoration(
          color: Colors.grey.shade200,
          borderRadius: BorderRadius.circular(6),
        ),
        alignment: Alignment.center,
        child: Text('暂无成员空闲数据',
            style: TextStyle(fontSize: 12, color: StColors.textSecondary)),
      );
    }
    final max = memberCount <= 0 ? 1 : memberCount;
    return SizedBox(
      height: 24,
      child: Row(
        children: [
          for (var i = 0; i < density.length; i++)
            Expanded(
              child: Container(
                margin: const EdgeInsets.symmetric(horizontal: 0.5),
                decoration: BoxDecoration(
                  color: Colors.green
                      .withValues(alpha: 0.15 + 0.85 * (density[i] / max)),
                  borderRadius: BorderRadius.circular(2),
                  border: i < allFree.length && allFree[i] == '1'
                      ? Border.all(color: StColors.sparkStroke, width: 1.5)
                      : null,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 画布：回填了时段的步骤画成块；个人总览轨叠加私事幽灵图层（半透明斜纹）。
class _Canvas extends StatelessWidget {
  final List<CollabStep> blocks;
  final List<(int, int)> privateBusy;
  final int windowStartMs;
  final int windowEndMs;
  final bool showPersonal;

  const _Canvas({
    required this.blocks,
    required this.privateBusy,
    required this.windowStartMs,
    required this.windowEndMs,
    required this.showPersonal,
  });

  @override
  Widget build(BuildContext context) {
    final span = (windowEndMs - windowStartMs) <= 0
        ? 1
        : (windowEndMs - windowStartMs);
    return Stack(
      children: [
        if (showPersonal && privateBusy.isNotEmpty)
          Positioned.fill(
            child: CustomPaint(
              key: CollabCanvasView.ghostLayerKey,
              painter: _GhostStripesPainter(
                privateBusy: privateBusy,
                windowStartMs: windowStartMs,
                span: span,
              ),
            ),
          ),
        if (blocks.isEmpty)
          Center(
            child: Text('暂无已回填时段的步骤',
                style: TextStyle(color: StColors.textSecondary)),
          )
        else
          ListView(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            children: [
              for (final b in blocks) _StepBlock(step: b),
            ],
          ),
      ],
    );
  }
}

/// 私事幽灵图层：半透明琥珀 + 斜纹。只做视觉投影，不拦截下层交互。
class _GhostStripesPainter extends CustomPainter {
  final List<(int, int)> privateBusy;
  final int windowStartMs;
  final int span;

  _GhostStripesPainter({
    required this.privateBusy,
    required this.windowStartMs,
    required this.span,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0x55E8A33D) // 琥珀，半透明
      ..style = PaintingStyle.fill;
    for (final iv in privateBusy) {
      final x0 = ((iv.$1 - windowStartMs) / span).clamp(0.0, 1.0) * size.width;
      final x1 = ((iv.$2 - windowStartMs) / span).clamp(0.0, 1.0) * size.width;
      if (x1 <= x0) continue;
      canvas.drawRect(Rect.fromLTWH(x0, 0, x1 - x0, size.height), paint);
      // 斜纹
      final stripe = Paint()
        ..color = const Color(0x33B26A00)
        ..strokeWidth = 2;
      for (var x = x0; x < x1; x += 8) {
        canvas.drawLine(Offset(x, size.height), Offset(x + 12, 0), stripe);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _GhostStripesPainter old) =>
      old.privateBusy != privateBusy || old.span != span;
}

class _StepBlock extends StatelessWidget {
  final CollabStep step;
  const _StepBlock({required this.step});

  @override
  Widget build(BuildContext context) {
    final ready = step.status == 'READY';
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        dense: true,
        leading: Icon(Icons.event,
            size: 18, color: ready ? StColors.sparkStroke : Colors.grey),
        title: Text('步骤 ${step.id}',
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        subtitle: Text(_fmt(step.startsAtMs!, step.endsAtMs!),
            style: TextStyle(fontSize: 12, color: StColors.textSecondary)),
        trailing: _tag(step.status, ready ? StColors.sparkStroke : Colors.grey),
      ),
    );
  }
}

String _fmt(int s, int e) {
  String hhmm(int ms) {
    final t = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }

  return '${hhmm(s)}–${hhmm(e)}';
}

Widget _tag(String text, Color color) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(text,
          style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w600)),
    );
